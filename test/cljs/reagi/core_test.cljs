(ns reagi.core-test
  (:require-macros [cemerick.cljs.test :refer (is deftest testing)])
  (:require [cemerick.cljs.test :as t]
            [reagi.core :as r :include-macros true]))

(deftest test-behavior
  (let [a (atom 1)
        b (r/behavior (+ 1 @a))]
    (is (= @b 2))
    (swap! a inc)
    (is (= @b 3))))

(deftest test-behavior
  (is (r/behavior? (r/behavior "foo")))
  (is (not (r/behavior? "foo"))))
