(ns reagi.core)

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

(defn delta
  "Constructs a behavior from an initial value and a function that describes an
  accumulative change to that value over time. The function should take two
  arguments, the current value and the number of seconds since it was last
  called, and return a new value."
  [f init]
  (let [last-time-ms (atom (System/currentTimeMillis))
        last-result  (atom init)]
    (behavior
     (let [time-ms   (System/currentTimeMillis)
           change-ms (- time-ms @last-time-ms)
           result    (f @last-result (/ change-ms 1000.0))]
       (reset! last-time-ms time-ms)
       (reset! last-result result)))))