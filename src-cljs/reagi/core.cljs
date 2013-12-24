(ns reagi.core
  (:require-macros [reagi.core :refer (behavior)]
                   [cljs.core.async.macros :refer (go go-loop)])
  (:require [cljs.core :as core]
            [cljs.core.async :refer (alts! chan close! timeout <! >!)])
  (:refer-clojure :exclude [merge cons zip mapcat map filter remove constantly
                            reduce count cycle delay]))

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

(defn- track [head ch]
  (let [out (chan)]
    (go-loop []
      (when-let [m (<! ch)]
        (reset! head m)
        (>! out m)
        (recur)))
    out))

(defprotocol Observable
  (sub [stream channel]
    "Tell the stream to send events to an existing core.async channel.")
  (unsub [stream channel]
    "Tell the stream to stop sending events the the supplied channel."))

(defn- observable [channel]
  (let [observers (atom #{})]
    (go-loop []
      (when-let [m (<! channel)]
        (doseq [o @observers]
          (>! o m))
        (recur)))
    (reify
      Observable
      (sub [_ ch]   (swap! observers conj ch))
      (unsub [_ ch] (swap! observers disj ch)))))

(defn tap
  "Create a core.async channel that receives events from the supplied event
  stream."
  [stream]
  (let [ch (chan)]
    (sub stream ch)
    ch))

(defprotocol Dependencies
  (deps* [x]))

(defprotocol Disposable
  (dispose [x] "Clean up any resources an object has before it goes out of scope."))

(defn evt
  "Create an event suitable to be pushed onto a channel."
  [msg]
  [(.getTime (js/Date.)) msg])

(deftype Events [ch closed? clean-up ob head deps]
  IPending
  (-realized? [_] (not (nil? @head)))
  IDeref
  (-deref [self]
    (if-let [[_ value] @head]
      value
      (throw "Cannot deref an unrealized event stream")))
  IFn
  (-invoke [stream msg]
    (if closed?
      (throw "Cannot push to closed event stream")
      (do (go (>! ch (evt msg)))
          stream)))
  Observable
  (sub [_ c]
    (if-let [hd @head]
      (go (>! c hd)))
    (sub ob c))
  (unsub [_ c] (unsub ob c))
  Dependencies
  (deps* [_] deps)
  Disposable
  (dispose [_] (clean-up)))

(defn- no-op [])

(defn events
  "Create an referential stream of events. The stream may be instantiated from
  an existing core.async channel, otherwise a new one will be created. If the
  stream is closed, it cannot be pushed to.

  A clean-up function may optionally be specified, which is evaluated when the
  dispose function is called on the stream. A list of dependent streams may also
  be included, in order to protect them against premature GC.

  If you're not deriving the event stream from an existing channel or another
  stream, use the no-argument form."
  ([]
     (events (chan)))
  ([ch]
     (events ch false))
  ([ch closed?]
     (events ch closed? no-op))
  ([ch closed? clean-up]
     (events ch closed? clean-up nil))
  ([ch closed? clean-up deps]
     (let [head (atom nil)
           ob   (observable (track head ch))]
       (Events. ch closed? clean-up ob head deps))))

(defn events?
  "Return true if the object is a stream of events."
  [x]
  (instance? Events x))

(defn push!
  "Push one or more messages onto the stream."
  ([stream])
  ([stream msg]
     (stream msg))
  ([stream msg & msgs]
     (doseq [m (core/cons msg msgs)]
       (stream m))))

(defn merge
  "Combine multiple streams into one. All events from the input streams are
  pushed to the returned stream."
  [& streams]
  (let [ch (chan)]
    (doseq [s streams]
      (sub s ch))
    (events ch true #(close! ch) streams)))

(defn cons
  "Return a new event stream with an additional value added to the beginning."
  [value stream]
  (let [ch (tap stream)]
    (go (>! ch (evt value)))
    (events ch true #(close! ch) stream)))

(def ^:private no-value (js/Object.))

(defn- no-value? [x]
  (identical? x no-value))

(defn- zip-ch [ins]
  (let [index (into {} (map-indexed (fn [i x] [x i]) ins))
        out   (chan)]
    (go-loop [value (mapv (core/constantly no-value) ins)]
      (let [[data in] (alts! ins)]
        (if-let [[t v] data]
          (let [value (assoc value (index in) v)]
            (when-not (some no-value? value)
              (>! out [t value]))
            (recur value))
          (close! out))))
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
    (events (zip-ch chs) true #(close-all! chs) streams)))

(defn- mapcat-ch [f in]
  (let [out (chan)]
    (go-loop []
      (if-let [[t msg] (<! in)]
        (let [xs (f msg)]
          (doseq [x xs] (>! out [t x]))
          (recur))
        (close! out)))
    out))

(defn mapcat
  "Mapcat a function over a stream."
  ([f stream]
     (let [ch (tap stream)]
       (events (mapcat-ch f ch) true #(close! ch) stream)))
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

(defn- reduce-ch [f ch]
  (let [out (chan)]
    (go (let [[t init] (<! ch)]
          (>! out [t init])
          (loop [acc init]
            (if-let [[t val] (<! ch)]
              (let [val (f acc val)]
                (>! out [t val])
                (recur val))
              (close! out)))))
    out))

(defn reduce
  "Create a new stream by applying a function to the previous return value and
  the current value of the source stream."
  ([f stream]
     (let [ch (tap stream)]
       (events (reduce-ch f ch) true #(close! ch) stream)))
  ([f init stream]
     (let [ch (tap stream)]
       (go (>! ch (evt init)))
       (events (reduce-ch f ch) true #(close! ch) stream))))

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
    (go-loop [prev no-value]
      (if-let [[t val] (<! in)]
        (do (if (or (no-value? prev) (not= val prev))
              (>! out [t val]))
            (recur val))
        (close! out)))
    out))

(defn uniq
  "Remove any successive duplicates from the stream."
  [stream]
  (let [ch (tap stream)]
    (events (uniq-ch ch) true #(close! ch) stream)))

(defn cycle
  "Incoming events cycle a sequence of values. Useful for switching between
  states."
  [values stream]
  (->> (reduce (fn [xs _] (next xs)) (core/cycle values) stream)
       (map first)))

(defn- throttle-ch [timeout-ms in]
  (let [out (chan)]
    (go-loop [t0 0]
      (if-let [[t1 val] (<! in)]
        (do (if (>= (- t1 t0) timeout-ms)
              (>! out [t1 val]))
            (recur t1))
        (close! out)))
    out))

(defn throttle
  "Remove any events in a stream that occur too soon after the prior event.
  The timeout is specified in milliseconds."
  [timeout-ms stream]
  (let [ch (tap stream)]
    (events (throttle-ch timeout-ms ch) true #(close! ch) stream)))

(defn- run-sampler
  [ch ref interval stop?]
  (go-loop []
    (<! (timeout interval))
    (when-not @stop?
      (>! ch (evt @ref))
      (recur))))

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
    (go-loop []
      (if-let [val (<! ch)]
        (do (<! (timeout delay-ms))
            (>! out val)
            (recur))
        (close! out)))
    out))

(defn delay
  "Delay all events by the specified number of milliseconds."
  [delay-ms stream]
  (let [ch (tap stream)]
    (events (delay-ch delay-ms ch) true #(close! ch) stream)))
