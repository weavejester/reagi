(ns reagi.core
  (:refer-clojure :exclude [constantly derive mapcat map filter remove ensure
                            merge reduce cycle count delay cons time flatten])
  #+clj
  (:import [clojure.lang IDeref IFn IPending])
  #+clj
  (:require [clojure.core :as core]
            [clojure.core.async :as a :refer [go go-loop <! >! <!! >!!]])
  #+cljs
  (:require [cljs.core :as core]
            [cljs.core.async :as a :refer [<! >!]])
  #+cljs
  (:require-macros [reagi.core :refer (behavior)]
                   [cljs.core.async.macros :refer (go go-loop)]))

(defprotocol ^:no-doc Signal
  (complete? [signal]
    "True if the signal's value will no longer change."))

(defn signal?
  "True if the object is a behavior or event stream."
  [x]
  (satisfies? Signal x))

(defprotocol ^:no-doc Boxed
  (unbox [x] "Unbox a boxed value."))

(deftype Completed [x]
  Boxed
  (unbox [_] x))

#+clj (ns-unmap *ns* '->Completed)

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

#+clj
(extend-protocol Boxed
  Object (unbox [x] x)
  nil    (unbox [x] x))

#+cljs
(extend-protocol Boxed
  default
  (unbox [x] x))

(deftype Behavior [func cache]
  IDeref
  (#+clj deref #+cljs -deref [behavior]
    (unbox (swap! cache #(if (instance? Completed %) % (func)))))
  Signal
  (complete? [_] (instance? Completed @cache)))

#+clj (ns-unmap *ns* '->Behavior)

(defn behavior-call
  "Takes a zero-argument function and yields a Behavior object that will
  evaluate the function each time it is dereferenced. See: behavior."
  [func]
  (Behavior. func (atom nil)))

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
  #+clj (behavior (/ (System/nanoTime) 1000000000.0))
  #+cljs (behavior (/ (.getTime (js/Date.)) 1000.0)))

(defn delta
  "Return a behavior that tracks the time in seconds from when it was created."
  []
  (let [t @time]
    (behavior (- @time t))))

(defn- track! [mult head]
  (let [ch (a/chan)]
    (a/tap mult ch)
    (go-loop []
      (when-let [m (<! ch)]
        (reset! head m)
        (recur)))))

(defprotocol ^:no-doc Observable
  (sub [stream channel]
    "Tell the stream to send events to an existing core.async channel. The
    events sent to the channel are boxed. To send the events unboxed, use the
    sink! function.")
  (unsub [stream channel]
    "Tell the stream to stop sending events the the supplied channel."))

(defn- tap [stream]
  (let [ch (a/chan)]
    (sub stream ch)
    ch))

#+clj
(defn- peek!! [mult time-ms]
  (let [ch (a/chan)]
    (a/tap mult ch)
    (try
      (if time-ms
        (first (a/alts!! [ch (a/timeout time-ms)]))
        (<!! ch))
      (finally
        (a/untap mult ch)))))

(def ^:private dependencies
  (java.util.Collections/synchronizedMap (java.util.WeakHashMap.)))

(defn- depend-on!
  "Protect a collection of child objects from being GCed before the parent."
  [parent children]
  (.put dependencies parent children))

#+clj
(defn- deref-events [mult head ms timeout-val]
  (if-let [hd @head]
    (unbox hd)
    (if-let [val (peek!! mult ms)]
      (unbox val)
      timeout-val)))

#+cljs
(defprotocol Disposable
  (dispose [x] "Clean up any resources an object has before it goes out of scope."))

;; reify creates an object twice, leading to the finalize method
;; to be prematurely triggered. For this reason, we use a type.

(deftype Events [ch complete clean-up mult head]
  IPending
  #+clj (isRealized [_] (not (nil? @head)))
  #+cljs (-realized? [_] (not (nil? @head)))

  IDeref
  #+clj (deref [self] (deref-events mult head nil nil))
  #+cljs (-deref [self]
           (if-let [hd @head]
             (unbox hd)
             js/undefined))
  
  #+clj clojure.lang.IBlockingDeref
  #+clj (deref [_ ms timeout-val] (deref-events mult head ms timeout-val))
  
  IFn
  #+clj (invoke [stream msg] (do (>!! ch (box msg)) stream))
  #+cljs (-invoke [stream msg] (do (go (>! ch (box msg))) stream))

  Observable
  (sub [_ c]
    (if-let [hd @head]
      (go (>! c hd)))    
    (a/tap mult c))
  (unsub [_ c] (a/untap mult c))

  Signal
  (complete? [_] @complete)

  #+clj Object
  #+clj (finalize [_] (clean-up))

  #+cljs Disposable
  #+cljs (dispose [_] (clean-up)))

#+clj (ns-unmap *ns* '->Events)

(defn- no-op [])

(def ^:private no-value
  #+clj (Object.)
  #+cljs (js/Object.))

(defn- no-value? [x]
  (identical? x no-value))

(defn- until-complete [in complete]
  (let [out (a/chan)]
    (go (loop []
          (when-let [m (<! in)]
            (>! out m)
            (if (instance? Completed m)
              (a/close! in)
              (recur))))
        (a/close! out)
        (reset! complete true))
    out))

(defn events
  "Create a referential stream of events. The stream may be instantiated from
  an existing core.async channel, otherwise a new channel will be created.
  Streams instantiated from existing channels are closed by default.

  A map of options may also be specified with the following keys:

    :init    - an optional, initial value for the stream
    :dispose - a function called when the stream is disposed
    :closed? - true if the stream cannot be pushed to, false if it can
    :deps    - a set of dependant streams that should be protected from GC"
  ([]   (events (a/chan)))
  ([ch] (events ch {}))
  ([ch {:keys [init dispose deps]
        :or   {dispose no-op, init no-value}}]
     (let [init     (if (no-value? init) nil (box init))
           head     (atom init)
           complete (atom false)
           mult     (a/mult (until-complete ch complete))]
       (track! mult head)
       (doto (Events. ch complete dispose mult head)
         (depend-on! deps)))))

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
  (sub stream (a/map> unbox channel)))

(defn- close-all! [chs]
  (doseq [ch chs]
    (a/close! ch)))

(defn merge
  "Combine multiple streams into one. All events from the input streams are
  pushed to the returned stream."
  [& streams]
  (let [chs (mapv tap streams)]
    (events (a/merge chs) {:dispose #(close-all! chs), :deps streams})))

#+clj
(defn ensure
  "Block until the first value of the stream becomes available, then return the
  stream."
  [stream]
  (doto stream deref))

(defn- zip-ch [ins]
  (let [index (into {} (map-indexed (fn [i x] [x i]) ins))
        out   (a/chan)]
    (go-loop [value (mapv (core/constantly no-value) ins)
              ins   (set ins)]
      (if (seq ins)
        (let [[data in] (a/alts! (vec ins))]
          (if data
            (let [value (assoc value (index in) (unbox data))]
              (when-not (some no-value? value)
                (>! out (box value)))
              (recur value ins))
            (recur value (disj ins in))))
        (a/close! out)))
    out))

(defn zip
  "Combine multiple streams into one. On an event from any input stream, a
  vector will be pushed to the returned stream containing the latest events
  of all input streams."
  [& streams]
  (let [chs (mapv tap streams)]
    (events (zip-ch chs) {:dispose #(close-all! chs), :deps streams})))

(defn- mapcat-ch [f in]
  (let [out (a/chan)]
    (go-loop []
      (if-let [msg (<! in)]
        (let [xs (f (unbox msg))]
          (doseq [x xs] (>! out (box x)))
          (recur))
        (a/close! out)))
    out))

(defn mapcat
  "Mapcat a function over a stream."
  ([f stream]
     (let [ch (tap stream)]
       (events (mapcat-ch f ch) {:dispose #(a/close! ch), :deps stream})))
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
  (let [out (a/chan)]
    (go-loop [acc init]
      (if-let [msg (<! ch)]
        (let [val (if (no-value? acc)
                    (unbox msg)
                    (f acc (unbox msg)))]
          (>! out (box val))
          (recur val))
        (a/close! out)))
    out))

(defn reduce
  "Create a new stream by applying a function to the previous return value and
  the current value of the source stream."
  ([f stream]
     (reduce f no-value stream))
  ([f init stream]
     (let [ch (tap stream)]
       (events (reduce-ch f init ch)
               {:init init, :dispose #(a/close! ch), :deps stream}))))

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
  #+clj clojure.lang.PersistentQueue/EMPTY
  #+cljs cljs.core.PersistentQueue.EMPTY)

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
  (let [out (a/chan)]
    (go-loop [prev no-value]
      (if-let [msg (<! in)]
        (let [val (unbox msg)]
          (if (or (no-value? prev) (not= val prev))
            (>! out (box val)))
          (recur val))
        (a/close! out)))
    out))

(defn uniq
  "Remove any successive duplicates from the stream."
  [stream]
  (let [ch (tap stream)]
    (events (uniq-ch ch) {:dispose #(a/close! ch), :deps stream})))

(defn cycle
  "Incoming events cycle a sequence of values. Useful for switching between
  states."
  [values stream]
  (->> (reduce (fn [xs _] (next xs)) (core/cycle values) stream)
       (map first)))

(defn- time-ms []
  #+clj (System/currentTimeMillis)
  #+cljs (.getTime (js/Date.)))

(defn- throttle-ch [timeout-ms in]
  (let [out (a/chan)]
    (go-loop [t0 0]
      (if-let [msg (<! in)]
        (let [t1 (time-ms)]
          (if (>= (- t1 t0) timeout-ms)
            (>! out msg))
          (recur t1))
        (a/close! out)))
    out))

(defn throttle
  "Remove any events in a stream that occur too soon after the prior event.
  The timeout is specified in milliseconds."
  [timeout-ms stream]
  (let [ch (tap stream)]
    (events (throttle-ch timeout-ms ch) {:dispose #(a/close! ch), :deps stream})))

(defn- run-sampler
  [ch ref interval stop]
  (go-loop []
    (let [[_ port] (a/alts! [stop (a/timeout interval)])]
      (if (= port stop)
        (a/close! ch)
        #+clj  (do (>! ch (box @ref))
                   (recur))
        #+cljs (let [x @ref]
                 (if-not (undefined? x)
                   (>! ch (box x)))
                 (recur))))))

(defn sample
  "Turn a reference into an event stream by deref-ing it at fixed intervals.
  The interval time is specified in milliseconds."
  [interval-ms reference]
  (let [ch   (a/chan)
        stop (a/chan)]
    (run-sampler ch reference interval-ms stop)
    (events ch {:dispose #(a/close! stop)})))

(defn- delay-ch [delay-ms ch]
  (let [out (a/chan)]
    (go-loop []
      (if-let [msg (<! ch)]
        (do (<! (a/timeout delay-ms))
            (>! out msg)
            (recur))
        (a/close! out)))
    out))

(defn delay
  "Delay all events by the specified number of milliseconds."
  [delay-ms stream]
  (let [ch (tap stream)]
    (events (delay-ch delay-ms ch) {:dispose #(a/close! ch), :deps stream})))

(defn- join-ch [chs]
  (let [out (a/chan)]
    (go (doseq [ch chs]
          (loop []
            (when-let [msg (<! ch)]
              (>! out (box (unbox msg)))
              (recur))))
        (a/close! out))
    out))

(defn join
  "Join several streams together. Events are delivered from the first stream
  until it is completed, then the next stream, until all streams are complete."
  [& streams]
  (let [chs (mapv tap streams)]
    (events (join-ch chs) {:dispose #(close-all! chs), :deps streams})))

(defn- flatten-ch [in valve]
  (let [out (a/chan)]
    (go (loop [chs #{in}]
          (if-not (empty? chs)
            (let [[msg port] (a/alts! (conj (vec chs) valve))]
              (if (identical? port valve)
                (close-all! chs)
                (if msg
                  (if (identical? port in)
                    (recur (conj chs (tap (unbox msg))))
                    (do (>! out (box (unbox msg)))
                        (recur chs)))
                  (recur (disj chs port)))))))
        (a/close! out))
    out))

(defn flatten
  "Flatten a stream of streams into a stream that contains all the values of
  its components."
  [stream]
  (let [valve (a/chan)
        ch    (tap stream)]
    (events (flatten-ch ch valve) {:dispose #(a/close! valve) :deps stream})))
