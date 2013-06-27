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
      (e 1)
      (is (= 1 @e))
      (e 2)
      (is (= 2 @e)))))

(deftest test-dosync
  (testing "Behaviors"
    (let [b (r/behavior (rand))]
      (is (not= @b @b))
      (r/dosync
       (is (= @b @b)))))
  (testing "Event streams"
    (let [e (r/event-stream)]
      (r/dosync
        (r/push! e 1)
        (let [x @e]
          (r/push! e 2)
          (is (= x @e))))
      (is (= @e 2)))))

(deftest test-push!
  (let [e (r/event-stream)]
    (r/push! e 1)
    (is (= 1 @e))
    (r/push! e 2 3 4)
    (is (= 4 @e))))

(deftest test-freeze
  (let [f (r/freeze (r/event-stream))]
    (is (r/frozen? f))
    (is (thrown? ClassCastException (r/push! f 1)))))

(deftest test-mapcat
  (testing "Basic operation"
    (let [s (r/event-stream 0)
          e (r/mapcat (comp list inc) s)]
      (is (= 1 @e))
      (r/push! s 1)
      (is (= 2 @e))))
  (testing "No initial value"
    (let [s (r/event-stream)
          e (r/mapcat (comp list inc) s)]
      (r/push! s 1)
      (is (= 2 @e)))))

(deftest test-map
  (let [s (r/event-stream 0)
        e (r/map inc s)]
    (is (= 1 @e))
    (r/push! s 1)
    (is (= 2 @e))))

(deftest test-filter-by
  (let [s (r/event-stream)
        e (r/filter-by {:type :key-pressed} s)]
    (r/push! s {:type :key-pressed :key :a})
    (r/push! s {:type :key-released :key :a})
    (is (= @e {:type :key-pressed :key :a}))))

(deftest test-uniq
  (let [s (r/event-stream)
        e (r/reduce + 0 (r/uniq s))]
    (r/push! s 1 1)
    (is (= 1 @e))
    (r/push! s 1 2)
    (is (= 3 @e))))

(deftest test-count
  (let [e (r/event-stream)
        c (r/count e)]
    (is (= @c 0))
    (r/push! e 1)
    (is (= @c 1))
    (r/push! e 2 3)
    (is (= @c 3))))

(deftest test-cycle
  (let [s (r/event-stream)
        e (r/cycle [:on :off] s)]
    (is (= :on @e))
    (r/push! s 1)
    (is (= :off @e))
    (r/push! s 1)
    (is (= :on @e))))

(deftest test-constantly
  (let [s (r/event-stream)
        e (r/constantly 1 s)
        a (r/reduce + 0 e)]
    (is (= @e 1))
    (r/push! s 2 4 5)
    (is (= @e 1))
    (is (= @a 3))))

(deftest test-throttle
  (let [s (r/event-stream)
        e (r/throttle 100 s)]
    (r/push! s 1 2)
    (is (= @e 1))
    (Thread/sleep 101)
    (r/push! s 3)
    (Thread/sleep 50)
    (r/push! s 4)
    (is (= @e 3))))

(deftest test-gc
  (testing "Derived maps"
    (let [s (r/event-stream 0)
          e (r/map inc (r/map inc s))]
      (System/gc)
      (r/push! s 1)
      (is (= @e 3))))
  (testing "Merge"
    (let [s (r/event-stream 0)
          e (r/merge (r/map inc s))]
      (System/gc)
      (r/push! s 1)
      (is (= @e 2))))
  (testing "GC unreferenced streams"
    (let [a (atom nil)
          s (r/event-stream)]
      (r/map #(reset! a %) s)
      (System/gc)
      (r/push! s 1)
      (is (nil? @a)))))

(deftest test-sample
  (testing "Basic sample"
    (let [a (atom 0)
          s (r/sample 100 a)]
      (is (= @s 0))
      (swap! a inc)
      (is (= @s 0))
      (Thread/sleep 120)
      (is (= @s 1))))
  (testing "Thread ends if stream GCed"
    (let [a (atom false)]
      (r/sample 100 (r/behavior (reset! a true)))
      (System/gc)
      (reset! a false)
      (Thread/sleep 120)
      (is (= @a false))))
  (testing "Thread continues if stream not GCed"
    (let [a (atom false)
          s (r/sample 100 (r/behavior (reset! a true)))]
      (System/gc)
      (reset! a false)
      (Thread/sleep 120)
      (is (= @a true)))))
