(ns reagi.core
  (:import java.lang.ref.WeakReference)
  (:require [clojure.core :as core]
            [clojure.core.async :refer (alts! alts!! chan close! go timeout <! >! <!! >!!)])
  (:refer-clojure :exclude [constantly derive mapcat map filter remove
                            merge reduce cycle count delay]))

(defn behavior-call
  "Takes a zero-argument function and yields a Behavior object that will
  evaluate the function each time it is dereferenced. See: behavior."
  [func]
  (reify
    clojure.lang.IDeref
    (deref [behavior] (func))))

(defmacro behavior
  "Takes a body of expressions and yields a behavior object that will evaluate
  the body each time it is dereferenced."
  [& form]
  `(behavior-call (fn [] ~@form)))

(defn- track [head]
  (let [ch (chan)]
    (go (loop []
          (when-let [m (<! ch)]
            (reset! head m)
            (recur))))
    ch))

(defprotocol ^:no-doc Observable
  (sub [observable channel])
  (unsub [observable channel]))

(defn- observable [channel]
  (let [observers (atom #{})]
    (go (loop []
          (when-let [m (<! channel)]
            (doseq [o @observers]
              (>! o m))
            (recur))))
    (reify
      Observable
      (sub [_ ch]   (swap! observers conj ch))
      (unsub [_ ch] (swap! observers disj ch)))))

(defn tap [ob]
  (let [ch (chan)]
    (sub ob ch)
    ch))

(defn- peek!! [ob time-ms]
  (let [ch (tap ob)]
    (try
      (if time-ms
        (first (alts!! [ch (timeout time-ms)]))
        (<!! ch))
      (finally
        (unsub ob ch)))))

(defn evt
  "Create an event suitable to be pushed onto a channel."
  [msg]
  [(System/currentTimeMillis) msg])

;; reify creates an object twice, leading to the finalize method
;; to be prematurely triggered. For this reason, we use a type.

(defn- deref-events [ob head ms timeout-val]
  (if-let [hd @head]
    (second hd)
    (if-let [val (peek!! ob ms)]
      (second val)
      timeout-val)))

(deftype Events [ch closed? clean-up ob head]
  clojure.lang.IPending
  (isRealized [_] (not (nil? @head)))
  clojure.lang.IDeref
  (deref [self]
    (deref-events ob head nil nil))
  clojure.lang.IBlockingDeref
  (deref [_ ms timeout-val]
    (deref-events ob head ms timeout-val))
  clojure.lang.IFn
  (invoke [stream msg]
    (if closed?
      (throw (UnsupportedOperationException. "Cannot push to closed event stream"))
      (do (>!! ch (evt msg))
          stream)))
  Observable
  (sub [_ c] (sub ob c))
  (unsub [_ c] (unsub ob c))
  Object
  (finalize [_] (clean-up)))

(alter-meta! #'->Events assoc :no-doc true)

(defn- no-op [])

(defn events
  "Create an referential stream of events."
  ([]
     (events (chan)))
  ([ch]
     (events ch false))
  ([ch closed?]
     (events ch closed? no-op))
  ([ch closed? clean-up]
     (let [ob   (observable ch)
           head (atom nil)]
       (sub ob (track head))
       (Events. ch closed? clean-up ob head))))

(defn push!
  "Push one or more messages onto the stream."
  ([stream])
  ([stream msg]
     (stream msg))
  ([stream msg & msgs]
     (doseq [m (cons msg msgs)]
       (stream m))))

(defn merge
  "Combine multiple streams into one. All events from the input streams are
  pushed to the returned stream."
  [& streams]
  (let [ch (chan)]
    (doseq [s streams]
      (sub s ch))
    (events ch true #(close! ch))))

(def ^:private undefined (Object.))

(defn- undefined? [x]
  (identical? x undefined))

(defn- zip-ch [ins]
  (let [index (into {} (map-indexed (fn [i x] [x i]) ins))
        out   (chan)]
    (go (loop [value (mapv (core/constantly undefined) ins)]
          (let [[data in] (alts! ins)]
            (if-let [[t v] data]
              (let [value (assoc value (index in) v)]
                (do (if-not (some undefined? value)
                      (>! out [t value]))
                    (recur value)))
              (close! out)))))
    out))

(defn- close-all! [chs]
  (doseq [ch chs]
    (close! ch)))

(defn zip
  "Combine multiple streams into one. On an event from any input stream, a
  vector will be pushed to the returned stream containing the latest events
  of all input streams."
  [& streams]
  (let [chs (mapv tap streams)]
    (events (zip-ch chs) true #(close-all! chs))))

(defn- mapcat-ch [f in]
  (let [out (chan)]
    (go (loop []
          (if-let [[t msg] (<! in)]
            (let [xs (f msg)]
              (doseq [x xs] (>! out [t x]))
              (recur))
            (close! out))))
    out))

(defn mapcat
  "Mapcat a function over a stream."
  ([f stream]
     (let [ch (tap stream)]
       (events (mapcat-ch f ch) true #(close! ch))))
  ([f stream & streams]
     (mapcat (partial apply f) (apply zip stream streams))))

(defn map
  "Map a function over a stream."
  [f & streams]
  (apply mapcat (comp list f) streams))

(defn filter
  "Filter a stream by a predicate."
  [pred stream]
  (mapcat #(if (pred %) (list %)) stream))

(defn remove
  "Remove all items in a stream the predicate does not match."
  [pred stream]
  (filter (complement pred) stream))

(defn constantly
  "Constantly map the same value over an event stream."
  [value stream]
  (map (core/constantly value) stream))

(defn- reduce-ch [f init in]
  (let [out (chan)]
    (go (loop [acc init]
          (if-let [[t val] (<! in)]
            (if (undefined? acc)
              (recur val)
              (let [val (f acc val)]
                (>! out [t val])
                (recur val)))
            (close! out))))
    out))

(defn reduce
  "Create a new stream by applying a function to the previous return value and
  the current value of the source stream."
  ([f stream]
     (reduce f undefined stream))
  ([f init stream]
     (let [ch (tap stream)]
       (events (reduce-ch f init ch) true #(close! ch)))))

(defn count
  "Return an accumulating count of the items in a stream."
  [stream]
  (reduce (fn [x _] (inc x)) 0 stream))

(defn accum
  "Change an initial value based on an event stream of functions."
  [init stream]
  (reduce #(%2 %1) init stream))

(defn- uniq-ch [in]
  (let [out (chan)]
    (go (loop [prev undefined]
          (if-let [[t val] (<! in)]
            (do (if (or (undefined? prev) (not= val prev))
                  (>! out [t val]))
                (recur val))
            (close! out))))
    out))

(defn uniq
  "Remove any successive duplicates from the stream."
  [stream]
  (let [ch (tap stream)]
    (events (uniq-ch ch) true #(close! ch))))

(defn cycle
  "Incoming events cycle a sequence of values. Useful for switching between
  states."
  [values stream]
  (->> (reduce (fn [vs _] (next vs)) (core/cycle values) stream)
       (map first)))

(defn- throttle-ch [timeout-ms in]
  (let [out (chan)]
    (go (loop [t0 0]
          (if-let [[t1 val] (<! in)]
            (do (if (>= (- t1 t0) timeout-ms)
                  (>! out [t1 val]))
                (recur t1))
            (close! out))))
    out))

(defn throttle
  "Remove any events in a stream that occur too soon after the prior event.
  The timeout is specified in milliseconds."
  [timeout-ms stream]
  (let [ch (tap stream)]
    (events (throttle-ch timeout-ms ch) true #(close! ch))))

(defn- run-sampler
  [ch ref interval stop?]
  (go (loop []
        (<! (timeout interval))
        (when-not @stop?
          (>! ch (evt @ref))
          (recur)))))

(defn sample
  "Turn a reference into an event stream by deref-ing it at fixed intervals.
  The interval time is specified in milliseconds."
  [interval-ms reference]
  (let [ch    (chan)
        stop? (atom false)]
    (run-sampler ch reference interval-ms stop?)
    (events ch true #(reset! stop? true))))

(defn- delay-ch [delay-ms ch]
  (let [out (chan)]
    (go (loop []
          (if-let [val (<! ch)]
            (do (<! (timeout delay-ms))
                (>! out val)
                (recur))
            (close! out))))
    out))

(defn delay
  "Delay all events by the specified number of milliseconds."
  [delay-ms stream]
  (let [ch (tap stream)]
    (events (delay-ch delay-ms ch) true #(close! ch))))
