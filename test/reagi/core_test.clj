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

(deftest test-push!
  (let [e (r/event-stream)]
    (r/push! e 1)
    (is (= 1 @e))
    (r/push! e 2 3 4)
    (is (= 4 @e))))

(deftest test-initial
  (let [e1 (r/event-stream :foo)
        e2 (r/initial :bar e1)]
    (is (= @e1 :foo))
    (is (= @e2 :bar))
    (r/push! e1 :baz)
    (is (= @e1 :baz))
    (is (= @e2 :baz))))

(deftest test-zip
  (let [e1 (r/event-stream 0)
        e2 (r/event-stream 0)
        z  (r/zip e1 e2)]
    (is (= @z [0 0]))
    (r/push! e1 1)
    (is (= @z [1 0]))
    (r/push! e2 2)
    (is (= @z [1 2]))
    (r/push! e1 3)
    (r/push! e2 4)
    (is (= @z [3 4]))))

(deftest test-map
  (testing "Basic operation"
    (let [s (r/event-stream 0)
          e (r/map inc s)]
      (is (= 1 @e))
      (r/push! s 1)
      (is (= 2 @e))))
  (testing "Multiple streams"
    (let [s1 (r/event-stream 0)
          s2 (r/event-stream 0)
          e  (r/map + s1 s2)]
      (is (= @e 0))
      (r/push! s1 4)
      (is (= @e 4))
      (r/push! s2 6)
      (is (= @e 10)))))

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
      (is (= 2 @e))))
  (testing "Multiple streams"
    (let [s1 (r/event-stream 0)
          s2 (r/event-stream 0)
          e  (r/mapcat (comp list +) s1 s2)]
      (is (= @e 0))
      (r/push! s1 2)
      (is (= @e 2))
      (r/push! s2 3)
      (is (= @e 5)))))

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
  (testing "Zip"
    (let [s (r/event-stream 0)
          e (r/zip (r/map inc s) (r/map dec s))]
      (System/gc)
      (r/push! s 1)
      (is (= @e [2 0]))))
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
