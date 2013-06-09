(ns reagi.core
  (:refer-clojure :exclude [derive mapcat map filter remove merge reduce cycle count])
  (:require [clojure.core :as core]))

(def ^:dynamic *behaviors* nil)

(defn behavior-call
  "Takes a zero-argument function and yields a Behavior object that will
  evaluate the function each time it is dereferenced. See: behavior."
  [f]
  (reify
    clojure.lang.IDeref
    (deref [_]
      (binding [*behaviors* (or *behaviors* (memoize #(%)))]
        (*behaviors* f)))))

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
  "Create a new event stream with an optional initial value. Calling deref on
  an event stream will return the last value pushed into the event stream, or
  the init value if no values have been pushed."
  ([] (event-stream nil))
  ([init]
     (let [observers (weak-hash-map)
           head      (atom init)]
       (reify
         clojure.lang.IDeref
         (deref [_] @head)
         clojure.lang.IFn
         (invoke [stream msg]
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
  [init func]
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
  ([func stream]
     (derive nil func stream))
  ([init func stream]
     (let [stream* (derived-stream init func)]
       (subscribe stream stream*)
       (freeze stream* [stream]))))

(defn mapcat
  "Mapcat a function over a stream."
  ([f stream]
     (mapcat f nil stream))
  ([f init stream]
     (derive init #(apply push! %1 (f %2)) stream)))

(defn map
  "Map a function over a stream."
  ([f stream]
     (map f nil stream))
  ([f init stream]
     (mapcat #(list (f %)) init stream)))

(defn filter
  "Filter a stream by a predicate."
  ([pred stream]
     (filter pred nil stream))
  ([pred init stream]
     (mapcat #(if (pred %) (list %)) init stream)))

(defn remove
  "Remove all items in a stream the predicate does not match."
  ([pred stream]
     (remove pred nil stream))
  ([pred init stream]
     (filter (complement pred) init stream)))

(defn filter-by
  "Filter a stream by matching part of a map against a message."
  ([partial stream]
     (filter-by partial nil stream))
  ([partial init stream]
     (filter #(= % (core/merge % partial)) init stream)))

(defn merge
  "Merge multiple streams into one."
  [& streams]
  (let [stream* (event-stream)]
    (doseq [stream streams]
      (subscribe stream stream*))
    (freeze stream* streams)))

(defn reduce
  "Reduce a stream with a function."
  ([f stream]
     (reduce f nil stream))
  ([f init stream]
     (let [acc (atom init)]
       (derive init #(push! %1 (swap! acc f %2)) stream))))

(defn count
  "Return an accumulating count of the items in a stream."
  [stream]
  (reduce (fn [x _] (inc x)) 0 stream))

(defn accum
  "Change an initial value based on an event stream of functions."
  ([stream]
     (accum nil stream))
  ([init stream]
      (reduce #(%2 %1) init stream)))

(defn uniq
  "Remove any successive duplicates from the stream."
  ([stream]
     (uniq nil stream))
  ([init stream]
     (->> stream
          (reduce #(if (= (peek %1) %2) (conj %1 %2) [%2]) [])
          (filter #(= (core/count %) 1))
          (map first init))))

(defn cycle
  "Incoming events cycle a sequence of values. Useful for switching between
  states."
  [values stream]
  (let [vs (atom (core/cycle values))]
    (map (fn [_] (first (swap! vs next)))
         (first values)
         stream)))
