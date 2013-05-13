(ns reagi.core
  (:refer-clojure :exclude [mapcat map filter merge reduce])
  (:require [clojure.core :as core]))

(def ^:dynamic *behaviors* nil)

(deftype Behavior [f]
  clojure.lang.IDeref
  (deref [_]
    (binding [*behaviors* (or *behaviors* (memoize #(%)))]
      (*behaviors* f))))

(defn behavior-call
  "Takes a zero-argument function and yields a Behavior object that will
  evaluate the function each time it is dereferenced. See: behavior."
  [f]
  (Behavior. f))

(defmacro behavior
  "Takes a body of expressions and yields a Behavior object that will evaluate
  the body each time it is dereferenced. All derefs of behaviors that happen
  inside a containing behavior will be consistent."
  [& form]
  `(behavior-call (fn [] ~@form)))

(defprotocol Pushable
  (push! [stream msg] "Push a message onto the stream."))

(defprotocol Observable
  (subscribe [stream observer] "Add an observer function to the stream."))

(defrecord EventStream [observers head]
  clojure.lang.IDeref
  (deref [_] @head)
  Pushable
  (push! [stream msg]
    (reset! head msg)
    (doseq [observer @observers]
      (observer msg)))
  Observable
  (subscribe [stream observer]
    (swap! observers conj observer)))

(defn event-stream
  "Create a new event stream with an optional initial value. Calling deref on
  an event stream will return the last value pushed into the event stream, or
  the init value if no values have been pushed."
  ([] (event-stream nil))
  ([init] (EventStream. (atom #{}) (atom init))))

(deftype FrozenEventStream [stream]
  clojure.lang.IDeref
  (deref [_] @stream)
  Observable
  (subscribe [_ observer] (subscribe stream observer)))

(defn freeze
  "Return a stream that can no longer be pushed to."
  [stream]
  (FrozenEventStream. stream))

(defn frozen?
  "Returns true if the stream cannot be pushed to."
  [stream]
  (instance? FrozenEventStream stream))

(defn push-seq!
  "Push a seq of messages to an event stream."
  [stream msgs]
  (doseq [m msgs]
    (push! stream m)))

(defn mapcat
  "Mapcat a function over a stream."
  [f stream]
  (let [stream* (event-stream)]
    (subscribe stream #(push-seq! stream* (f %)))
    (freeze stream*)))

(defn map
  "Map a function over a stream."
  [f stream]
  (mapcat #(list (f %)) stream))

(defn filter
  "Filter a stream by a predicate."
  [pred stream]
  (mapcat #(if (pred %) (list %)) stream))

(defn filter-by
  "Filter a stream by matching part of a map against a message."
  [partial stream]
  (filter #(= % (core/merge % partial)) stream))

(defn merge
  "Merge multiple streams into one."
  [& streams]
  (let [stream* (event-stream)]
    (doseq [stream streams]
      (subscribe stream #(push! stream* %)))
    (freeze stream*)))

(defn reduce
  "Reduce a stream with a function."
  [f init stream]
  (let [acc     (atom init)
        stream* (event-stream)]
    (subscribe stream #(push! stream* (swap! acc f %)))
    (freeze stream*)))
