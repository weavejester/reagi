(ns reagi.core)

(def ^:dynamic *behaviors* nil)

(defn behavior-call [f]
  (reify
    clojure.lang.IDeref
    (deref [_]
      (binding [*behaviors* (or *behaviors* (memoize #(%)))]
        (*behaviors* f)))))

(defmacro behavior [& form]
  `(behavior-call (fn [] ~@form)))

(defn delta [f init]
  (let [last-time-ms (atom (System/currentTimeMillis))
        last-result  (atom init)]
    (behavior
     (let [time-ms   (System/currentTimeMillis)
           change-ms (- time-ms @last-time-ms)
           result    (f @last-result (/ change-ms 1000.0))]
       (reset! last-time-ms time-ms)
       (reset! last-result result)))))