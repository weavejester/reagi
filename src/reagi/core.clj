(ns reagi.core
  (:require [clojure.core :as core])
  (:import java.lang.ref.WeakReference)
  (:refer-clojure :exclude [constantly derive mapcat map filter remove
                            merge reduce cycle count dosync ensure]))

(def ^:dynamic *cache* nil)

(defmacro dosync
  "Any event stream or behavior deref'ed within this block is guaranteed to
  always return the same value."
  [& body]
  `(binding [*cache* (atom {})]
     ~@body))

(defn- cache-hit [cache key get-value]
  (if (contains? cache key)
    cache
    (assoc cache key (get-value))))

(defn- cache-lookup! [cache key get-value]
  (-> (swap! cache cache-hit key get-value)
      (get key)))

(defn ensure
  "Fix the value of a behavior of event stream within a dosync. Returns its
  input."
  [behavior-or-stream]
  (when *cache* (deref behavior-or-stream))
  behavior-or-stream)

(defn behavior-call
  "Takes a zero-argument function and yields a Behavior object that will
  evaluate the function each time it is dereferenced. See: behavior."
  [func]
  (reify
    clojure.lang.IDeref
    (deref [behavior]
      (if *cache*
        (cache-lookup! *cache* behavior func)
        (func)))))

(defmacro behavior
  "Takes a body of expressions and yields a Behavior object that will evaluate
  the body each time it is dereferenced. All derefs of behaviors that happen
  inside a containing behavior will be consistent."
  [& form]
  `(behavior-call (fn [] ~@form)))

(defprotocol Observable
  (subscribe [stream observer] "Add an observer function to the stream."))

(defn- weak-hash-map []
  (java.util.Collections/synchronizedMap (java.util.WeakHashMap.)))

(defn event-stream
  "Create a new event stream with an optional initial value, which may be a
  delay. Calling deref on an event stream will return the last value pushed
  into the event stream, or the initial value if no values have been pushed."
  ([] (event-stream nil))
  ([init]
     (let [observers    (weak-hash-map)
           undefined    (Object.)
           head         (atom undefined)
           resolve-head #(let [value @head]
                           (if (identical? value undefined)
                             (force init)
                             value))]
       (reify
         clojure.lang.IDeref
         (deref [stream]
           (if *cache*
             (cache-lookup! *cache* stream resolve-head)
             (resolve-head)))
         clojure.lang.IFn
         (invoke [stream msg]
           (ensure stream)
           (reset! head msg)
           (doseq [[observer _] observers]
             (observer msg)))
         Observable
         (subscribe [stream observer]
           (.put observers observer true))))))

(defn push!
  "Push one or more messages onto the stream."
  ([stream])
  ([stream msg]
     (stream msg))
  ([stream msg & msgs]
     (doseq [m (cons msg msgs)]
       (stream m))))

(defn freeze
  "Return a stream that can no longer be pushed to."
  ([stream] (freeze stream nil))
  ([stream parent-refs]
     (reify
       clojure.lang.IDeref
       (deref [_] parent-refs @stream)
       Observable
       (subscribe [_ observer] (subscribe stream observer)))))

(defn frozen?
  "Returns true if the stream cannot be pushed to."
  [stream]
  (not (ifn? stream)))

(defn- derived-stream
  "Derive an event stream from a function."
  [func init]
  (let [stream (event-stream init)]
    (reify
      clojure.lang.IDeref
      (deref [_] @stream)
      clojure.lang.IFn
      (invoke [_ msg] (func stream msg))
      Observable
      (subscribe [_ observer] (subscribe stream observer)))))

(defn derive
  "Derive a new event stream from another and a function. The function will be
  called each time the existing stream receives a message, and will have the
  new stream and the message as arguments."
  [func init stream]
  (let [stream* (derived-stream func init)]
    (subscribe stream stream*)
    (freeze stream* [stream])))

(defn- map* [f stream]
  (derive #(%1 (f %2)) (delay (f @stream)) stream))

(defn merge
  "Combine multiple streams into one. All events from the input streams are
  pushed to the returned stream."
  [& streams]
  (let [stream* (event-stream)]
    (doseq [stream streams]
      (subscribe stream stream*))
    (freeze stream* streams)))

(defn zip
  "Combine multiple streams into one. On an event from any input stream, a
  vector will be pushed to the returned stream containing the latest events
  of all input streams."
  [& streams]
  (let [indexed (core/map-indexed (fn [i s] (map* (fn [x] [i x]) s)) streams)
        head    (atom (vec (core/map deref streams)))
        stream* (derived-stream (fn [s [i x]] (s (swap! head assoc i x))) @head)]
    (doseq [stream indexed]
      (subscribe stream stream*))
    (freeze stream* indexed)))

(defn map
  "Map a function over a stream."
  ([f stream]
     (map* f stream))
  ([f stream & streams]
     (map* (partial apply f) (apply zip stream streams))))

(defn mapcat
  "Mapcat a function over a stream."
  ([f stream]
     (derive #(apply push! %1 (f %2))
             (delay (last (f @stream)))
             stream))
  ([f stream & streams]
     (mapcat (partial apply f) (apply zip stream streams))))

(defn filter
  "Filter a stream by a predicate."
  [pred stream]
  (mapcat #(if (pred %) (list %)) stream))

(defn remove
  "Remove all items in a stream the predicate does not match."
  [pred stream]
  (filter (complement pred) stream))

(defn filter-by
  "Filter a stream by matching part of a map against a message."
  [partial stream]
  (filter #(= % (core/merge % partial)) stream))

(defn reduce
  "Reduce a stream with a function."
  ([f stream]
     (reduce f @stream stream))
  ([f init stream]
     (let [acc (atom init)]
       (derive #(push! %1 (swap! acc f %2)) init stream))))

(defn count
  "Return an accumulating count of the items in a stream."
  [stream]
  (reduce (fn [x _] (inc x)) 0 stream))

(defn accum
  "Change an initial value based on an event stream of functions."
  [init stream]
  (reduce #(%2 %1) init stream))

(defn uniq
  "Remove any successive duplicates from the stream."
  [stream]
  (->> stream
       (reduce #(if (= (peek %1) %2) (conj %1 %2) [%2]) [])
       (filter #(= (core/count %) 1))
       (map first)))

(defn cycle
  "Incoming events cycle a sequence of values. Useful for switching between
  states."
  [values stream]
  (let [vs (atom (cons nil (core/cycle values)))]
    (map (fn [_] (first (swap! vs next)))
         stream)))

(defn constantly
  "Constantly map the same value over an event stream."
  [value stream]
  (map (core/constantly value) stream))

(defn throttle
  "Remove any events in a stream that occur too soon after the prior event.
  The timeout is specified in milliseconds."
  [timeout-ms stream]
  (->> stream
       (map (fn [x] [(System/currentTimeMillis) x]))
       (reduce (fn [[t0 _] [t1 x]] [(- t1 t0) x]) [0 nil])
       (remove (fn [[dt _]] (>= timeout-ms dt)))
       (map second)))

(defn- start-sampler
  [interval reference ^WeakReference stream-ref]
  (future
    (loop []
      (when-let [stream (.get stream-ref)]
        (push! stream @reference)
        (Thread/sleep interval)
        (recur)))))

(defn sample
  "Turn a reference into an event stream by deref-ing it at fixed intervals.
  The interval time is specified in milliseconds. A background thread is started
  by this function that will persist until the return value is GCed."
  [interval-ms reference]
  (let [stream (event-stream @reference)]
    (start-sampler interval-ms reference (WeakReference. stream))
    stream))
