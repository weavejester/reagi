(ns reagi.core-test
  (:use clojure.test)
  (:require [reagi.core :as r]))

(deftest test-behavior
  (let [a (atom 1)
        b (r/behavior (+ 1 @a))]
    (is (= @b 2))
    (swap! a inc)
    (is (= @b 3))))

(deftest test-events
  (testing "push"
    (let [e (r/events)]
      (e 1)
      (Thread/sleep 20)
      (is (= 1 @e))
      (e 2)
      (Thread/sleep 20)
      (is (= 2 @e))))
  (testing "realized?"
    (let [e (r/events)]
      (is (not (realized? e)))
      (e 1)
      (Thread/sleep 10)
      (is (realized? e))))
  (testing "deref"
    (let [e  (r/events)
          t0 (System/currentTimeMillis)]
      (is (= (deref e 100 :missing) :missing))
      (let [t1 (System/currentTimeMillis)]
        (is (and (>= (- t1 t0) 100)
                 (<= (- t1 t0) 110)))))))

(defn- push!! [stream & msgs]
  (apply r/push! stream msgs)
  (Thread/sleep (* 10 (count msgs))))

(deftest test-push!
  (let [e (r/events)]
    (push!! e 1)
    (is (= 1 @e))
    (push!! e 2 3 4)
    (is (= 4 @e))))

(deftest test-zip
  (let [e1 (r/events)
        e2 (r/events)
        z  (r/zip e1 e2)]
    (push!! e1 1)
    (push!! e2 2)
    (is (= @z [1 2]))
    (push!! e1 3)
    (is (= @z [3 2]))
    (push!! e2 4)
    (is (= @z [3 4]))))

(deftest test-map
  (testing "Basic operation"
    (let [s (r/events)
          e (r/map inc s)]
      (push!! s 1)
      (is (= 2 @e))))
  (testing "Multiple streams"
    (let [s1 (r/events)
          s2 (r/events)
          e  (r/map + s1 s2)]
      (push!! s1 4)
      (push!! s2 6)
      (is (= @e 10)))))

(deftest test-mapcat
  (testing "Basic operation"
    (let [s (r/events)
          e (r/mapcat (comp list inc) s)]
      (push!! s 1)
      (is (= 2 @e))))
  (testing "Multiple streams"
    (let [s1 (r/events)
          s2 (r/events)
          e  (r/mapcat (comp list +) s1 s2)]
      (push!! s1 2)
      (push!! s2 3)
      (is (= @e 5)))))

(deftest test-uniq
  (let [s (r/events)
        e (r/reduce + 0 (r/uniq s))]
    (push!! s 1 1)
    (is (= 1 @e))
    (push!! s 1 2)
    (is (= 3 @e))))

(deftest test-count
  (let [e (r/events)
        c (r/count e)]
    (is (= @c 0))
    (push!! e 1)
    (is (= @c 1))
    (push!! e 2 3)
    (is (= @c 3))))

(deftest test-cycle
  (let [s (r/events)
        e (r/cycle [:on :off] s)]
    (push!! s 1)
    (is (= :on @e))
    (push!! s 1)
    (is (= :off @e))))

(deftest test-constantly
  (let [s (r/events)
        e (r/constantly 1 s)
        a (r/reduce + 0 e)]
    (push!! s 2 4 5)
    (is (= @e 1))
    (is (= @a 3))))

(deftest test-throttle
  (let [s (r/events)
        e (r/throttle 100 s)]
    (r/push! s 1 2)
    (Thread/sleep 20)
    (is (= @e 1))
    (Thread/sleep 101)
    (r/push! s 3)
    (Thread/sleep 50)
    (r/push! s 4)
    (is (= @e 3))))

(deftest test-gc
  (testing "Derived maps"
    (let [s (r/events)
          e (r/map inc (r/map inc s))]
      (System/gc)
      (push!! s 1)
      (is (= @e 3))))
  (testing "Merge"
    (let [s (r/events)
          e (r/merge (r/map inc s))]
      (System/gc)
      (push!! s 1)
      (is (= @e 2))))
  (testing "Zip"
    (let [s (r/events)
          e (r/zip (r/map inc s) (r/map dec s))]
      (System/gc)
      (push!! s 1)
      (is (= @e [2 0]))))
  (testing "GC unreferenced streams"
    (let [a (atom nil)
          s (r/events)]
      (r/map #(reset! a %) s)
      (System/gc)
      (push!! s 1)
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
