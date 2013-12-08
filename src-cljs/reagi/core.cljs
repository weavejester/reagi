(ns reagi.core)

(deftype Behavior [func]
  IDeref
  (-deref [behavior] (func)))

(defn behavior-call
  "Takes a zero-argument function and yields a Behavior object that will
  evaluate the function each time it is dereferenced. See: behavior."
  [func]
  (Behavior. func))

(defn behavior?
  "Return true if the object is a behavior."
  [x]
  (instance? Behavior x))
