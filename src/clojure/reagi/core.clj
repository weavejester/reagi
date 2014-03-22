(ns reagi.core
  (:require [clojure.core :as core]
            [clojure.core.async :refer (alts! alts!! chan close! go go-loop timeout
                                        <! >! <!! >!! map>)])
  (:refer-clojure :exclude [constantly derive mapcat map filter remove ensure
                            merge reduce cycle count delay cons time]))

(deftype Behavior [func]
  clojure.lang.IDeref
  (deref [behavior] (func)))

(ns-unmap *ns* '->Behavior)

(defn behavior-call
  "Takes a zero-argument function and yields a Behavior object that will
  evaluate the function each time it is dereferenced. See: behavior."
  [func]
  (Behavior. func))

(defmacro behavior
  "Takes a body of expressions and yields a behavior object that will evaluate
  the body each time it is dereferenced."
  [& form]
  `(behavior-call (fn [] ~@form)))

(defn behavior?
  "Return true if the object is a behavior."
  [x]
  (instance? Behavior x))

(def time
  "A behavior that tracks the current time in seconds."
  (behavior (/ (System/nanoTime) 1000000000.0)))

(defn delta
  "Return a behavior that tracks the time in seconds from when it was created."
  []
  (let [t @time]
    (behavior (- @time t))))

(defprotocol ^:no-doc Boxed
  (unbox [x] "Unbox a boxed value."))

(defn box
  "Box a value to ensure it can be sent through a channel."
  [x]
  (reify Boxed (unbox [_] x)))

(extend-protocol Boxed
  Object (unbox [x] x)
  nil    (unbox [x] x))

(defn- track [head ch]
  (let [out (chan)]
    (go-loop []
      (when-let [m (<! ch)]
        (reset! head m)
        (>! out m)
        (recur)))
    out))

(defprotocol ^:no-doc Observable
  (sub [stream channel]
    "Tell the stream to send events to an existing core.async channel. The
    events sent to the channel are boxed. To send the events unboxed, use the
    sink! function.")
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

(defn- tap [stream]
  (let [ch (chan)]
    (sub stream ch)
    ch))

(defn- peek!! [ob time-ms]
  (let [ch (tap ob)]
    (try
      (if time-ms
        (first (alts!! [ch (timeout time-ms)]))
        (<!! ch))
      (finally
        (unsub ob ch)))))

(defprotocol ^:no-doc Dependencies
  (^:no-doc deps* [x]))

(defn- deref-events [ob head ms timeout-val]
  (if-let [hd @head]
    (unbox hd)
    (if-let [val (peek!! ob ms)]
      (unbox val)
      timeout-val)))

;; reify creates an object twice, leading to the finalize method
;; to be prematurely triggered. For this reason, we use a type.

(deftype Events [ch closed clean-up ob head deps]
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
    (if closed
      (throw (UnsupportedOperationException. "Cannot push to closed event stream"))
      (do (>!! ch (box msg))
          stream)))
  Observable
  (sub [_ c]
    (if-let [hd @head]
      (go (>! c hd)))    
    (sub ob c))
  (unsub [_ c] (unsub ob c))
  Dependencies
  (deps* [_] deps)
  Object
  (finalize [_] (clean-up)))

(ns-unmap *ns* '->Events)

(defn- no-op [])

(def ^:private no-value (Object.))

(defn- no-value? [x]
  (identical? x no-value))

(defn events
  "Create a referential stream of events. The stream may be instantiated from
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
           head (atom init)
           ob   (observable (track head ch))]
       (Events. ch closed? dispose ob head deps))))

(defn events?
  "Return true if the object is a stream of events."
  [x]
  (instance? Events x))

(defn closed?
  "Returns true if the supplied stream is closed. Closed streams cannot be
  pushed to."
  [stream]
  (and (events? stream) (.closed stream)))

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

(defn merge
  "Combine multiple streams into one. All events from the input streams are
  pushed to the returned stream."
  [& streams]
  (let [ch (chan)]
    (doseq [s streams]
      (sub s ch))
    (events ch {:dispose #(close! ch), :deps streams})))

(defn ensure
  "Block until the first value of the stream becomes available, then return the
  stream."
  [stream]
  (doto stream deref))

(defn- zip-ch [ins]
  (let [index (into {} (map-indexed (fn [i x] [x i]) ins))
        out   (chan)]
    (go-loop [value (mapv (core/constantly no-value) ins)]
      (let [[data in] (alts! ins)]
        (if data
          (let [value (assoc value (index in) (unbox data))]
            (when-not (some no-value? value)
              (>! out (box value)))
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
  clojure.lang.PersistentQueue/EMPTY)

(defn buffer
  "Buffer all the events in the stream. A maximum buffer size may be specified,
  in which case the buffer will contain only the last n items. It's recommended
  that a buffer size is specified, otherwise the buffer will grow without limit."
  ([stream]
     (reduce conj empty-queue stream))
  ([n stream]
     {:pre [(integer? n) (pos? n)]}
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
        (let [t1 (System/currentTimeMillis)]
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
  [ch ref interval stop?]
  (go-loop []
    (<! (timeout interval))
    (when-not @stop?
      (>! ch (box @ref))
      (recur))))

(defn sample
  "Turn a reference into an event stream by deref-ing it at fixed intervals.
  The interval time is specified in milliseconds."
  [interval-ms reference]
  (let [ch    (chan)
        stop? (atom false)]
    (run-sampler ch reference interval-ms stop?)
    (events ch {:dispose #(reset! stop? true)})))

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
