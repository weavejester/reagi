(ns reagi.core-test
  (:use clojure.test)
  (:require [reagi.core :as r]))

(deftest test-behavior
  (let [a (atom 1)
        b (r/behavior (+ 1 @a))]
    (is (= @b 2))
    (swap! a inc)
    (is (= @b 3))))

(deftest test-event-stream
  (testing "Initial value"
    (is (nil? @(r/event-stream)))
    (is (= 1 @(r/event-stream 1))))
  (testing "Push"
    (let [e (r/event-stream)]
      (r/push! e 1)
      (is (= 1 @e))
      (r/push! e 2)
      (is (= 2 @e)))))

(deftest test-map
  (let [s (r/event-stream)
        e (r/map inc s)]
    (r/push! s 1)
    (is (= 2 @e))))

(deftest test-cycle
  (let [s (r/event-stream)
        e (r/cycle [:on :off] s)]
    (r/push! s 1) (is (= :on @e))
    (r/push! s 1) (is (= :off @e))
    (r/push! s 1) (is (= :on @e))))
