(ns reagi.core)

(defn behavior-call [f]
  (reify
    clojure.lang.IDeref
    (deref [_] (f))))

(defmacro behavior [& form]
  `(behavior-call (fn [] ~@form)))
