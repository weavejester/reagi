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

