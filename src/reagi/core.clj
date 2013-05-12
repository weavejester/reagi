(ns reagi.core
  (:refer-clojure :exclude [reduce map filter merge]))

(def ^:dynamic *behaviors* nil)

(defn behavior-call
  "Takes a zero-argument function and yields a behavior object that will
  evaluate the function each time it is dereferenced. See: behavior."
  [f]
  (reify
    clojure.lang.IDeref
    (deref [_]
      (binding [*behaviors* (or *behaviors* (memoize #(%)))]
        (*behaviors* f)))))

(defmacro behavior
  "Takes a body of expressions and yields a behavior object that will evaluate
  the body each time it is dereferenced. All derefs of behaviors that happen
  inside a containing behavior will be consistent."
  [& form]
  `(behavior-call (fn [] ~@form)))

(defrecord EventStream [observers head]
  clojure.lang.IDeref
  (deref [_] @head))

(defn event-stream [init]
  (EventStream. (java.util.WeakHashMap.) (atom init)))

(defn push! [stream value]
  (reset! (:head stream) value)
  (doseq [[observer _] (:observers stream)]
    (observer value)))

(defn- subscribe [stream f]
  (.put (:observers stream) f true))

(defn reduce
  [f init stream]
  (let [reduced (event-stream init)]
    (subscribe stream #(push! reduced (f @(:head reduced) %)))
    reduced))
