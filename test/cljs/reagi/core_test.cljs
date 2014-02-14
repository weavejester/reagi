(ns reagi.core-test
  (:require-macros [cemerick.cljs.test :refer (is deftest testing done)]
                   [cljs.core.async.macros :refer (go)])
  (:require [cemerick.cljs.test :as t]
            [cljs.core.async :refer (<! >! timeout)]
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

(deftest ^:async test-delta
  (let [d (r/delta)]
    (go (<! (timeout 110))
        (is (> @d 0.1))
        (is (< @d 0.2))
        (done))))

(deftest test-boxed
  (is (= (r/unbox 1) 1))
  (is (= (r/unbox nil) nil))
  (is (not= (r/box nil) nil))
  (is (= (r/unbox (r/box nil)) nil))
  (is (= (r/unbox (r/box 1)) 1)))

(deftest ^:async test-event-push
  (let [e (r/events)]
    (go (e 1)
        (<! (timeout 20))
        (is (= 1 @e))
        (e 2)
        (<! (timeout 20))
        (is (= 2 @e))
        (done))))

(deftest ^:async test-event-realized
  (let [e (r/events)]
    (go (is (not (realized? e)))
        (e 1)
        (<! (timeout 20))
        (is (realized? e))
        (done))))

(deftest test-events?
  (is (r/events? (r/events)))
  (is (not (r/events? "foo"))))
