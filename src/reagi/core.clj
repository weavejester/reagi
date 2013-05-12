(ns reagi.core
  (:refer-clojure :exclude [mapcat map filter]))

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

(defprotocol Stream
  (push! [stream msg] "Push a message onto the stream.")
  (subscribe [stream listener] "Add a listener function to the stream."))

(deftype EventStream [observers head]
  clojure.lang.IDeref
  (deref [_] @head)
  Stream
  (push! [stream msg]
    (reset! head msg)
    (doseq [[observer _] observers]
      (observer msg)))
  (subscribe [stream observer]
    (.put observers observer true)))

(defn push-seq!
  "Push a seq of messages to an event stream."
  [stream msgs]
  (doseq [m msgs]
    (push! stream m)))

(defn event-stream
  "Create a new event stream with an optional initial value."
  ([] (event-stream nil))
  ([init] (EventStream. (java.util.WeakHashMap.) (atom init))))

(defn mapcat
  "Mapcat a function over a stream."
  [f stream]
  (let [stream* (event-stream)]
    (subscribe stream #(push-seq! stream* (f %)))
    stream*))

(defn map
  "Map a function over a stream."
  [f stream]
  (mapcat #(list (f %)) stream))

(defn filter
  "Filter a stream by a predicate."
  [pred stream]
  (mapcat #(if (pred %) (list %)) stream))
