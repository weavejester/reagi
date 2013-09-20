(ns reagi.core
  (:import java.lang.ref.WeakReference)
  (:require [clojure.core :as core]
            [clojure.core.async :as async :refer (chan close! go <! >! <!! >!!)])
  (:refer-clojure :exclude [constantly derive mapcat map filter remove
                            merge reduce cycle count]))

(defn behavior-call
  "Takes a zero-argument function and yields a Behavior object that will
  evaluate the function each time it is dereferenced. See: behavior."
  [func]
  (reify
    clojure.lang.IDeref
    (deref [behavior] (func))))

(defmacro behavior
  "Takes a body of expressions and yields a behavior object that will evaluate
  the body each time it is dereferenced."
  [& form]
  `(behavior-call (fn [] ~@form)))

(defprotocol Observable
  (subscribe [stream channel]
    "Assign a core.async channel to receive messages from a source of events."))

(defn- ^java.util.Map weak-hash-map []
  (java.util.Collections/synchronizedMap (java.util.WeakHashMap.)))

(defn- distribute [input outputs]
  (go (loop []
        (when-let [[msg] (<! input)]
          (doseq [out outputs]
            (>! out [msg]))
          (recur)))))

(defn- observable [channel]
  (let [observers (weak-hash-map)]
    (distribute channel (core/map key observers))
    (reify
      Observable
      (subscribe [_ ch]
        (.put observers ch true)))))

(defn- map-chan [f in]
  (let [out (chan)]
    (go (loop []
          (if-let [[msg] (<! in)]
            (do (>! out [(f msg)])
                (recur))
            (close! out))))
    out))

(defn- track-head [head channel]
  (map-chan (fn [msg] (reset! head msg) msg)
            channel))

(defn event-stream
  "Create a new event stream with an optional initial value, which may be a
  delay. Calling deref on an event stream will return the last value pushed
  into the event stream, or the initial value if no values have been pushed."
  ([] (event-stream nil))
  ([init]
     (let [channel (chan)
           head    (atom init)
           stream  (observable (track-head head channel))]
       (reify
         clojure.lang.IDeref
         (deref [_] @head)
         clojure.lang.IFn
         (invoke [_ msg]
           (go (>! channel [msg]))
           msg)
         Observable
         (subscribe [_ ch]
           (subscribe stream ch))
         Object
         (finalize [_]
           (close! channel))))))

(defn push!
  "Push one or more messages onto the stream."
  ([stream])
  ([stream msg]
     (stream msg))
  ([stream msg & msgs]
     (doseq [m (cons msg msgs)]
       (stream m))))

(defn derive
  "Derive a new event stream from a parent stream, an initial value, and a
  handler function. The handler should expect to receive an input channel as its
  argument, and return an output channel."
  [handler init parent]
  (let [input  (chan)
        output (handler input)
        head   (atom init)
        stream (observable (track-head head output))]
    (subscribe parent input)
    (reify
      clojure.lang.IDeref
      (deref [_] parent @head)
      Observable
      (subscribe [_ ch]
        (subscribe stream ch))
      Object
      (finalize [_]
        (close! input)
        (close! output)))))

(defn map* [f init stream]
  (derive #(map-chan f %) init stream))

(defn initial
  "Give the event stream a new initial value."
  [init stream]
  (map* identity init stream))

(comment

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
  "Create a new stream by applying a function to the previous return value and
  the current value of the source stream."
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
       (reduce #(if (= (peek %1) %2) [(peek %1) %2] [%2]) [])
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

)
