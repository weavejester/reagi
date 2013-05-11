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
