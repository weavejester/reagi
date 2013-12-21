(ns reagi.core
  (:require-macros [reagi.core :refer (behavior)]))

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

(def time
  "A behavior that tracks the current time in seconds."
  (behavior (/ (.getTime (js/Date.)) 1000.0)))

(defn delta
  "Return a behavior that tracks the time in seconds from when it was created."
  []
  (let [t @time]
    (behavior (- @time t))))
