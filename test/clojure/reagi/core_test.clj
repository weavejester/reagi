(ns reagi.core-test
  (:require [clojure.test :refer :all]
            [reagi.core :as r]
            [clojure.core.async :refer (chan >!! <!! close!)]))

(deftest test-signal?
  (is (r/signal? (r/behavior 1)))
  (is (r/signal? (r/events)))
  (is (not (r/signal? nil)))
  (is (not (r/signal? "foo"))))

(deftest test-behavior
  (let [a (atom 1)
        b (r/behavior (+ 1 @a))]
    (is (= @b 2))
    (swap! a inc)
    (is (= @b 3))))

(deftest test-behavior
  (is (r/behavior? (r/behavior "foo")))
  (is (not (r/behavior? "foo"))))

(deftest test-delta
  (let [d (r/delta)]
    (Thread/sleep 110)
    (is (> @d 0.1))
    (is (< @d 0.2))))

(deftest test-boxed
  (is (= (r/unbox 1) 1))
  (is (= (r/unbox nil) nil))
  (is (not= (r/box nil) nil))
  (is (= (r/unbox (r/box nil)) nil))
  (is (= (r/unbox (r/box 1)) 1)))

(defn- deref! [e]
  (deref e 10000 :timeout))

(deftest test-events
  (testing "push"
    (let [e (r/events)]
      (e 1)
      (Thread/sleep 20)
      (is (= (deref! e) 1))
      (e 2)
      (Thread/sleep 20)
      (is (= (deref! e) 2))))
  (testing "realized?"
    (let [e (r/events)]
      (is (not (realized? e)))
      (e 1)
      (Thread/sleep 20)
      (is (realized? e))))
  (testing "deref"
    (let [e  (r/events)
          t0 (System/currentTimeMillis)]
      (is (= (deref e 100 :missing) :missing))
      (let [t1 (System/currentTimeMillis)]
        (is (and (>= (- t1 t0) 100)
                 (<= (- t1 t0) 110))))))
  (testing "channel"
    (let [e (r/events)]
      (>!! (r/port e):foo)
      (Thread/sleep 20)
      (is (realized? e))
      (is (= (deref! e) :foo)))))

(deftest test-events?
  (is (r/events? (r/events)))
  (is (not (r/events? "foo"))))

(defn- push!! [stream & msgs]
  (apply r/push! stream msgs)
  (Thread/sleep (* 20 (count msgs))))

(deftest test-push!
  (let [e (r/events)]
    (push!! e 1)
    (is (= (deref! e) 1))
    (push!! e 2 3 4)
    (is (= (deref! e) 4))))

(deftest test-completed
  (testing "behaviors"
    (let [a (atom nil)
          b (r/behavior @a)]
      (reset! a 1)
      (is (not (r/complete? b)))
      (is (= @b 1))
      (reset! a (r/completed 2))
      (is (= @b 2))
      (is (r/complete? b))
      (reset! a 3)
      (is (= @b 2))
      (reset! a (r/completed 4))
      (is (= @b 2))))
  (testing "events"
    (let [e (r/events)]
      (push!! e 1)
      (is (= (deref! e) 1))
      (push!! e (r/completed 2))
      (is (= (deref! e) 2))
      (is (r/complete? e))
      (push!! e 3)
      (is (= (deref! e) 2))))
  (testing "derived events"
    (let [e (r/events)
          m (r/map inc e)]
      (push!! e 1)
      (is (= (deref! m) 2))
      (push!! e (r/completed 2))
      (is (= (deref! m) 3))
      (is (r/complete? m))
      (push!! e 3)
      (is (= (deref! m) 3))))
  (testing "closed channel"
    (let [e (r/events)]
      (>!! (r/port e) 1)
      (is (= (deref! e) 1))
      (is (not (r/complete? e)))
      (close! (r/port e))
      (is (= (deref! e) 1))
      (is (r/complete? e)))))

(deftest test-sink!
  (testing "values"
    (let [e  (r/events)
          ch (chan 1)]
      (r/sink! e ch)
      (r/push! e :foo)
      (is (= (<!! ch) :foo))))
  (testing "closing"
    (let [e  (r/events)
          ch (chan)]
      (r/sink! e ch)
      (Thread/sleep 40)
      (close! (r/port e))
      (is (nil? (<!! ch))))))

(deftest test-cons
  (let [e (r/events)
        c (r/cons 5 e)]
    (is (realized? c))
    (is (= (deref! c) 5))
    (push!! e 10)
    (is (= (deref! c) 10))))

(deftest test-merge
  (testing "merged streams"
    (let [e1 (r/events)
          e2 (r/events)
          m  (r/merge e1 e2)]
      (push!! e1 1)
      (is (= (deref! m) 1))
      (push!! e2 2)
      (is (= (deref! m) 2))))
  (testing "closed channels"
    (let [e1  (r/events)
          e2  (r/events)
          m   (r/merge e1 e2)]
      (>!! (r/port e1) 1)
      (is (= (deref! m) 1))
      (close! (r/port e1))
      (>!! (r/port e2) 2)
      (Thread/sleep 20)
      (is (= (deref! m) 2)))))

(deftest test-zip
  (testing "zipped streams"
    (let [e1 (r/events)
          e2 (r/events)
          z  (r/zip e1 e2)]
      (push!! e1 1)
      (push!! e2 2)
      (is (= (deref! z) [1 2]))
      (push!! e1 3)
      (is (= (deref! z) [3 2]))
      (push!! e2 4)
      (is (= (deref! z) [3 4]))))
  (testing "closed channels"
    (let [e1  (r/events)
          e2  (r/events)
          z   (r/zip e1 e2)]
      (>!! (r/port e1) 1)
      (>!! (r/port e2) 2)
      (is (= (deref! z) [1 2]))
      (close! (r/port e1))
      (>!! (r/port e2) 3)
      (Thread/sleep 20)
      (is (= (deref! z) [1 3])))))

(deftest test-map
  (testing "Basic operation"
    (let [s (r/events)
          e (r/map inc s)]
      (push!! s 1)
      (is (= (deref! e) 2))))
  (testing "Multiple streams"
    (let [s1 (r/events)
          s2 (r/events)
          e  (r/map + s1 s2)]
      (push!! s1 4)
      (push!! s2 6)
      (is (= (deref! e) 10)))))

(deftest test-mapcat
  (testing "Basic operation"
    (let [s (r/events)
          e (r/mapcat (comp list inc) s)]
      (push!! s 1)
      (is (= (deref! e) 2))))
  (testing "Multiple streams"
    (let [s1 (r/events)
          s2 (r/events)
          e  (r/mapcat (comp list +) s1 s2)]
      (push!! s1 2)
      (push!! s2 3)
      (is (= (deref! e) 5)))))

(deftest test-filter
  (let [s (r/events)
        e (r/filter even? s)]
    (push!! s 1)
    (is (not (realized? e)))
    (push!! s 2 3)
    (is (= (deref! e) 2))))

(deftest test-remove
  (let [s (r/events)
        e (r/remove even? s)]
    (push!! s 0)
    (is (not (realized? e)))
    (push!! s 1 2)
    (is (= (deref! e) 1))))

(deftest test-reduce
  (testing "no initial value"
    (let [s (r/events)
          e (r/reduce + s)]
      (is (not (realized? e)))
      (push!! s 1)
      (is (realized? e))
      (is (= (deref! e) 1))
      (push!! s 2)
      (is (= (deref! e) 3))
      (push!! s 3 4)
      (is (= (deref! e) 10))))
  (testing "initial value"
    (let [s (r/events)
          e (r/reduce + 0 s)]
      (is (realized? e))
      (is (= (deref! e) 0))
      (push!! s 1)
      (is (= (deref! e) 1))
      (push!! s 2 3)
      (is (= (deref! e) 6))))
  (testing "initial value persists"
    (let [s (r/events)
          e (r/map inc (r/reduce + 0 s))]
      (is (= (deref e 1000 :timeout) 1)))))

(deftest test-buffer
  (testing "unlimited buffer"
    (let [s (r/events)
          b (r/buffer s)]
      (is (empty? (deref! b)))
      (push!! s 1)
      (is (= (deref! b) [1]))
      (push!! s 2 3 4 5)
      (is (= (deref! b) [1 2 3 4 5]))))
  (testing "limited buffer"
    (let [s (r/events)
          b (r/buffer 3 s)]
      (is (empty? (deref! b)))
      (push!! s 1)
      (is (= (deref! b) [1]))
      (push!! s 2 3 4 5)
      (is (= (deref! b) [3 4 5]))))
  (testing "smallest buffer"
    (let [s (r/events)
          b (r/buffer 1 s)]
      (push!! s 2 3 4 5)
      (is (= (deref! b) [5]))))
  (testing "preconditions"
    (is (thrown? AssertionError (r/buffer 0 (r/events))))
    (is (thrown? AssertionError (r/buffer 1.0 (r/events))))))

(deftest test-uniq
  (let [s (r/events)
        e (r/reduce + 0 (r/uniq s))]
    (push!! s 1 1)
    (is (= 1 (deref! e)))
    (push!! s 1 2)
    (is (= 3 (deref! e)))))

(deftest test-count
  (let [e (r/events)
        c (r/count e)]
    (is (= (deref! c) 0))
    (push!! e 1)
    (is (= (deref! c) 1))
    (push!! e 2 3)
    (is (= (deref! c) 3))))

(deftest test-cycle
  (let [s (r/events)
        e (r/cycle [:on :off] s)]
    (is (= :on (deref! e)))
    (push!! s 1)
    (is (= :off (deref! e)))
    (push!! s 1)
    (is (= :on (deref! e)))))

(deftest test-constantly
  (let [s (r/events)
        e (r/constantly 1 s)
        a (r/reduce + 0 e)]
    (push!! s 2 4 5)
    (is (= (deref! e) 1))
    (is (= (deref! a) 3))))

(deftest test-throttle
  (let [s (r/events)
        e (r/throttle 100 s)]
    (r/push! s 1 2)
    (Thread/sleep 20)
    (is (= (deref! e) 1))
    (Thread/sleep 101)
    (r/push! s 3)
    (Thread/sleep 50)
    (r/push! s 4)
    (is (= (deref! e) 3))))

(deftest test-gc
  (testing "Derived maps"
    (let [s (r/events)
          e (r/map inc (r/map inc s))]
      (System/gc)
      (push!! s 1)
      (is (= (deref! e) 3))))
  (testing "Merge"
    (let [s (r/events)
          e (r/merge (r/map inc s))]
      (System/gc)
      (push!! s 1)
      (is (= (deref! e) 2))))
  (testing "Zip"
    (let [s (r/events)
          e (r/zip (r/map inc s) (r/map dec s))]
      (System/gc)
      (push!! s 1)
      (is (= (deref! e) [2 0]))))
  (testing "GC unreferenced streams"
    (let [a (atom nil)
          s (r/events)]
      (r/map #(reset! a %) s)
      (Thread/sleep 100)
      (System/gc)
      (push!! s 1)
      (is (nil? @a)))))

(deftest test-sample
  (testing "Basic sample"
    (let [a (atom 0)
          s (r/sample 100 a)]
      (is (= (deref! s) 0))
      (swap! a inc)
      (is (= (deref! s) 0))
      (Thread/sleep 120)
      (is (= (deref! s) 1))))
  (testing "Thread ends if stream GCed"
    (let [a (atom false)]
      (r/sample 100 (r/behavior (reset! a true)))
      (System/gc)
      (Thread/sleep 100)
      (reset! a false)
      (Thread/sleep 120)
      (is (= @a false))))
  (testing "Thread continues if stream not GCed"
    (let [a (atom false)
          s (r/sample 100 (r/behavior (reset! a true)))]
      (System/gc)
      (Thread/sleep 100)
      (reset! a false)
      (Thread/sleep 120)
      (is (= @a true)))))

(deftest test-join
  (testing "basic sequence"
    (let [e1 (r/events)
          e2 (r/events)
          j  (r/join e1 e2)]
      (push!! e1 1)
      (is (= (deref! j) 1))
      (push!! e1 (r/completed 2))
      (is (= (deref! j) 2))
      (push!! e2 3)
      (is (= (deref! j) 3))))
  (testing "blocking"
    (let [e1 (r/events)
          e2 (r/events)
          j  (r/join e1 e2)
          s  (r/reduce + j)]
      (push!! e1 1)
      (push!! e2 3)
      (is (= (deref! j) 1))
      (is (= (deref! s) 1))
      (push!! e1 (r/completed 2))
      (is (= (deref! j) 3))
      (is (= (deref! s) 6))))
  (testing "complete"
    (let [e1 (r/events)
          e2 (r/events)
          j  (r/join e1 e2)]
      (push!! e1 (r/completed 1))
      (push!! e2 (r/completed 2))
      (is (= (deref! j) 2))
      (is (r/complete? j)))))

(deftest test-flatten
  (testing "basic operation"
    (let [es (r/events)
          f  (r/flatten es)
          e1 (r/events)
          e2 (r/events)]
      (push!! es e1)
      (push!! e1 1)
      (is (realized? f))
      (is (= (deref! f) 1))
      (push!! es e2)
      (push!! e2 2)
      (is (= (deref! f) 2))
      (push!! e1 3)
      (is (= (deref! f) 3))))
  (testing "completion"
    (let [es (r/events)
          f  (r/flatten es)
          e  (r/events)]
      (push!! es (r/completed e))
      (push!! e 1)
      (is (r/complete? es))
      (is (not (r/complete? e)))
      (is (not (r/complete? f)))
      (push!! e (r/completed 2))
      (is (r/complete? e))
      (is (r/complete? f)))))
