(ns reagi.core
  (:require-macros [reagi.core :refer (behavior)]
                   [cljs.core.async.macros :refer (go go-loop)])
  (:require [cljs.core :as core]
            [cljs.core.async :as a :refer (alts! chan close! timeout <! >! map>)])
  (:refer-clojure :exclude [merge cons zip mapcat map filter remove constantly
                            reduce count cycle delay]))

(defprotocol Signal
  (closed? [signal]
    "True if the signal accepts no adhoc input. Behaviors are always closed.
    Event streams derived from existing channels or other streams are also
    closed.")
  (complete? [signal]
    "True if the signal's value will no longer change."))

(defn signal?
  "True if the object is a behavior or event stream."
  [x]
  (satisfies? Signal x))

(defprotocol Boxed
  (unbox [x] "Unbox a boxed value."))

(deftype Completed [x]
  Boxed
  (unbox [_] x))

(defn completed
  "Wraps x to guarantee that it will be the last value in a behavior or event
  stream. The value of x will be cached, and any values after x will be
  ignored."
  [x]
  (Completed. x))

(defn box
  "Box a value to ensure it can be sent through a channel."
  [x]
  (if (instance? Completed x)
    x
    (reify Boxed (unbox [_] x))))

(extend-protocol Boxed
  default
  (unbox [x] x))

(deftype Behavior [func cache]
  IDeref
  (-deref [behavior]
    (unbox (swap! cache #(if (instance? Completed %) % (func)))))
  Signal
  (closed? [_] true)
  (complete? [_] (instance? Completed @cache)))

(defn behavior-call
  "Takes a zero-argument function and yields a Behavior object that will
  evaluate the function each time it is dereferenced. See: behavior."
  [func]
  (Behavior. func (atom nil)))

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

(defn- track! [mult head]
  (let [ch (chan)]
    (a/tap mult ch)
    (go-loop []
      (when-let [m (<! ch)]
        (reset! head m)
        (recur)))))

(defprotocol Observable
  (sub [stream channel]
    "Tell the stream to send events to an existing core.async channel. The
    events sent to the channel are boxed. To send the events unboxed, use the
    sink! function.")
  (unsub [stream channel]
    "Tell the stream to stop sending events the the supplied channel."))

(defn- tap [stream]
  (let [ch (chan)]
    (sub stream ch)
    ch))

(defprotocol Dependencies
  (deps* [x]))

(defprotocol Disposable
  (dispose [x] "Clean up any resources an object has before it goes out of scope."))

(deftype Events [ch closed complete clean-up mult head deps]
  IPending
  (-realized? [_] (not (nil? @head)))
  IDeref
  (-deref [self]
    (if-let [hd @head]
      (unbox hd)
      js/undefined))
  IFn
  (-invoke [stream msg]
    (if closed
      (throw (js/Error. "Cannot push to closed event stream"))
      (do (go (>! ch (box msg)))
          stream)))
  Observable
  (sub [_ c]
    (if-let [hd @head]
      (go (>! c hd)))
    (a/tap mult c))
  (unsub [_ c] (a/untap mult c))
  Dependencies
  (deps* [_] deps)
  Signal
  (closed? [_] closed)
  (complete? [_] @complete)
  Disposable
  (dispose [_] (clean-up)))

(defn- no-op [])

(def ^:private no-value (js/Object.))

(defn- no-value? [x]
  (identical? x no-value))

(defn- until-complete [in complete]
  (let [out (chan)]
    (go (loop []
          (when-let [m (<! in)]
            (>! out m)
            (if (instance? Completed m)
              (close! in)
              (recur))))
        (close! out)
        (reset! complete true))
    out))

(defn events
  "Create an referential stream of events. The stream may be instantiated from
  an existing core.async channel, otherwise a new channel will be created.
  Streams instantiated from existing channels are closed by default.

  A map of options may also be specified with the following keys:

    :init    - an optional, initial value for the stream
    :dispose - a function called when the stream is disposed
    :closed? - true if the stream cannot be pushed to, false if it can
    :deps    - a set of dependant streams that should be protected from GC"
  ([]   (events (chan) {:closed? false}))
  ([ch] (events ch {}))
  ([ch {:keys [init dispose closed? deps]
        :or   {dispose no-op, closed? true, init no-value}}]
     (let [init (if (no-value? init) nil (box init))
           head     (atom init)
           complete (atom false)
           mult     (a/mult (until-complete ch complete))]
       (track! mult head)
       (Events. ch closed? complete dispose mult head deps))))

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

(defn sink!
  "Deliver events on an event stream to a core.async channel. The events cannot
  include a nil value."
  [stream channel]
  (sub stream (map> unbox channel)))

(defn- close-all! [chs]
  (doseq [ch chs]
    (close! ch)))

(defn merge
  "Combine multiple streams into one. All events from the input streams are
  pushed to the returned stream."
  [& streams]
  (let [chs (mapv tap streams)]
    (events (a/merge chs) {:dispose #(close-all! chs), :deps streams})))

(defn- zip-ch [ins]
  (let [index (into {} (map-indexed (fn [i x] [x i]) ins))
        out   (chan)]
    (go-loop [value (mapv (core/constantly no-value) ins)
              ins   (set ins)]
      (if (seq ins)
        (let [[data in] (alts! (vec ins))]
          (if data
            (let [value (assoc value (index in) (unbox data))]
              (when-not (some no-value? value)
                (>! out (box value)))
              (recur value ins))
            (recur value (disj ins in))))
        (close! out)))
    out))

(defn zip
  "Combine multiple streams into one. On an event from any input stream, a
  vector will be pushed to the returned stream containing the latest events
  of all input streams."
  [& streams]
  (let [chs (mapv tap streams)]
    (events (zip-ch chs) {:dispose #(close-all! chs), :deps streams})))

(defn- mapcat-ch [f in]
  (let [out (chan)]
    (go-loop []
      (if-let [msg (<! in)]
        (let [xs (f (unbox msg))]
          (doseq [x xs] (>! out (box x)))
          (recur))
        (close! out)))
    out))

(defn mapcat
  "Mapcat a function over a stream."
  ([f stream]
     (let [ch (tap stream)]
       (events (mapcat-ch f ch) {:dispose #(close! ch), :deps stream})))
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

(defn- reduce-ch [f init ch]
  (let [out (chan)]
    (go-loop [acc init]
      (if-let [msg (<! ch)]
        (let [val (if (no-value? acc)
                    (unbox msg)
                    (f acc (unbox msg)))]
          (>! out (box val))
          (recur val))
        (close! out)))
    out))

(defn reduce
  "Create a new stream by applying a function to the previous return value and
  the current value of the source stream."
  ([f stream]
     (reduce f no-value stream))
  ([f init stream]
     (let [ch (tap stream)]
       (events (reduce-ch f init ch)
               {:init init, :dispose #(close! ch), :deps stream}))))


(defn cons
  "Return a new event stream with an additional value added to the beginning."
  [value stream]
  (reduce (fn [_ x] x) value stream))

(defn count
  "Return an accumulating count of the items in a stream."
  [stream]
  (reduce (fn [x _] (inc x)) 0 stream))

(defn accum
  "Change an initial value based on an event stream of functions."
  [init stream]
  (reduce #(%2 %1) init stream))

(def ^:private empty-queue
  cljs.core.PersistentQueue.EMPTY)

(defn buffer
  "Buffer all the events in the stream. A maximum buffer size may be specified,
  in which case the buffer will contain only the last n items. It's recommended
  that a buffer size is specified, otherwise the buffer will grow without limit."
  ([stream]
     (reduce conj empty-queue stream))
  ([n stream]
     (reduce (fn [q x] (conj (if (>= (core/count q) n) (pop q) q) x))
             empty-queue
             stream)))

(defn- uniq-ch [in]
  (let [out (chan)]
    (go-loop [prev no-value]
      (if-let [msg (<! in)]
        (let [val (unbox msg)]
          (if (or (no-value? prev) (not= val prev))
            (>! out (box val)))
          (recur val))
        (close! out)))
    out))

(defn uniq
  "Remove any successive duplicates from the stream."
  [stream]
  (let [ch (tap stream)]
    (events (uniq-ch ch) {:dispose #(close! ch), :deps stream})))

(defn cycle
  "Incoming events cycle a sequence of values. Useful for switching between
  states."
  [values stream]
  (->> (reduce (fn [xs _] (next xs)) (core/cycle values) stream)
       (map first)))

(defn- throttle-ch [timeout-ms in]
  (let [out (chan)]
    (go-loop [t0 0]
      (if-let [msg (<! in)]
        (let [t1 (.getTime (js/Date.))]
          (if (>= (- t1 t0) timeout-ms)
            (>! out msg))
          (recur t1))
        (close! out)))
    out))

(defn throttle
  "Remove any events in a stream that occur too soon after the prior event.
  The timeout is specified in milliseconds."
  [timeout-ms stream]
  (let [ch (tap stream)]
    (events (throttle-ch timeout-ms ch) {:dispose #(close! ch), :deps stream})))

(defn- run-sampler
  [ch ref interval stop]
  (go-loop []
    (let [[_ port] (alts! [stop (timeout interval)])]
      (if (= port stop)
        (close! ch)
        (let [x @ref]
          (if-not (undefined? x)
            (>! ch (box x)))
          (recur))))))

(defn sample
  "Turn a reference into an event stream by deref-ing it at fixed intervals.
  The interval time is specified in milliseconds."
  [interval-ms reference]
  (let [ch   (chan)
        stop (chan)]
    (run-sampler ch reference interval-ms stop)
    (events ch {:dispose #(close! stop)})))

(defn- delay-ch [delay-ms ch]
  (let [out (chan)]
    (go-loop []
      (if-let [msg (<! ch)]
        (do (<! (timeout delay-ms))
            (>! out msg)
            (recur))
        (close! out)))
    out))

(defn delay
  "Delay all events by the specified number of milliseconds."
  [delay-ms stream]
  (let [ch (tap stream)]
    (events (delay-ch delay-ms ch) {:dispose #(close! ch), :deps stream})))
