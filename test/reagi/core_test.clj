(ns reagi.core-test
  (:use clojure.test
        reagi.core))

(deftest test-behavior
  (let [a (atom 1)
        b (behavior (+ 1 @a))]
    (is (= @b 2))
    (swap! a inc)
    (is (= @b 3))))
