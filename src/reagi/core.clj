(ns reagi.core
  (:import java.lang.ref.WeakReference)
  (:require [clojure.core :as core]
            [clojure.core.async :refer (alts! chan close! go timeout <! >! <!! >!!)])
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

(defn- peek!! [ob]
  (let [ch (chan)]
    (sub ob ch)
    (try (<!! ch)
         (finally (unsub ob ch)))))

(defn evt
  "Create an event suitable to be pushed onto a channel."
  [msg]
  [(System/nanoTime) msg])

;; reify creates an object twice, leading to the finalize method
;; to be prematurely triggered. For this reason, we use a type.

(deftype Events [ch closed? clean-up ob head]
  clojure.lang.IDeref
  (deref [_]
    (if-let [hd @head]
      (second hd)
      (second (peek!! ob))))
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

(comment

(defn- map-chan [f in]
  (let [out (chan)]
    (go (loop []
          (if-let [[msg] (<! in)]
            (do (>! out [(f msg)])
                (recur))
            (close! out))))
    out))


(deftype EventStream [head channel stream]
  clojure.lang.IDeref
  (deref [_] @head)
  clojure.lang.IFn
  (invoke [_ msg]
    (>!! channel [msg])
    msg)
  Observable
  (subscribe [_ ch]
    (subscribe stream ch))
  Object
  (finalize [_]
    (close! channel)))

(alter-meta! #'->EventStream assoc :no-doc true)

(defn event-stream
  "Create a new event stream with an initial value. Calling deref on an event
  stream will return the last value pushed into the event stream, or the initial
  value if no values have been pushed."
  [init]
  (let [channel (chan)
        head    (atom init)
        stream  (observable (track-head head channel))]
    (EventStream. head channel stream)))

(defn push!
  "Push one or more messages onto the stream."
  ([stream])
  ([stream msg]
     (stream msg))
  ([stream msg & msgs]
     (doseq [m (cons msg msgs)]
       (stream m))))

(deftype DerivedEventStream [head stream channels]
  clojure.lang.IDeref
  (deref [_] @head)
  Observable
  (subscribe [_ ch]
    (subscribe stream ch))
  Object
  (finalize [_]
    (doseq [c channels]
      (close! c))))

(alter-meta! #'->DerivedEventStream assoc :no-doc true)

(defn derive
  "Derive a new event stream from a handler function, an initial value, and one
  or more parent streams. The handler should expect to receive an input channel
  for each stream as its argument, and should return an output channel."
  [handler init & parents]
  (let [inputs   (into {} (for [p parents] [p (chan)]))
        output   (apply handler (vals inputs))
        head     (atom init)
        stream   (observable (track-head head output))
        channels (cons output (vals inputs))]
    (doseq [[p i] inputs]
      (subscribe p i))
    (DerivedEventStream. head stream channels)))

(defn initial
  "Give the event stream a new initial value."
  [init stream]
  (derive #(map-chan identity %) init stream))

(defn- merge-chan [& ins]
  (let [out (chan)]
    (go (loop []
          (let [[msg _] (alts! ins)]
            (if msg
              (do (>! out msg)
                  (recur))
              (close! out)))))
    out))

(defn merge
  "Combine multiple streams into one. All events from the input streams are
  pushed to the returned stream."
  [& streams]
  (apply derive merge-chan nil streams))

(defn- zip-chan [init & ins]
  (let [index (into {} (map-indexed (fn [i x] [x i]) ins))
        out   (chan)]
    (go (loop [value init]
          (let [[data in] (alts! ins)]
            (if-let [[msg] data]
              (let [value (assoc value (index in) msg)]
                (do (>! out [value])
                    (recur value)))
              (close! out)))))
    out))

(defn zip
  "Combine multiple streams into one. On an event from any input stream, a
  vector will be pushed to the returned stream containing the latest events
  of all input streams."
  [& streams]
  (let [init (mapv deref streams)]
    (apply derive #(apply zip-chan init %&) init streams)))

(defn map
  "Map a function over a stream."
  ([f stream]
     (derive #(map-chan f %) (f @stream) stream))
  ([f stream & streams]
     (map (partial apply f) (apply zip stream streams))))

(defn constantly
  "Constantly map the same value over an event stream."
  [value stream]
  (map (core/constantly value) stream))

(defn- mapcat-chan [f in]
  (let [out (chan)]
    (go (loop []
          (if-let [[msg] (<! in)]
            (let [xs (f msg)]
              (doseq [x xs] (>! out [x]))
              (recur))
            (close! out))))
    out))

(defn mapcat
  "Mapcat a function over a stream."
  ([f stream]
     (derive #(mapcat-chan f %) (last (f @stream)) stream))
  ([f stream & streams]
     (mapcat (partial apply f) (apply zip stream streams))))

(defn filter
  "Filter a stream by a predicate."
  [pred stream]
  (mapcat #(if (pred %) (list %)) stream))

(defn remove
  "Remove all items in a stream the predicate does not match."
  [pred stream]
  (filter (complement pred) stream))

(defn- reduce-chan [f init in]
  (let [out (chan)]
    (go (loop [val init]
          (if-let [[msg] (<! in)]
            (let [val (f val msg)]
              (>! out [val])
              (recur val))
            (close! out))))
    out))

(defn reduce
  "Create a new stream by applying a function to the previous return value and
  the current value of the source stream."
  ([f stream]
     (reduce f @stream stream))
  ([f init stream]
     (derive #(reduce-chan f init %) init stream)))

(defn count
  "Return an accumulating count of the items in a stream."
  [stream]
  (reduce (fn [x _] (inc x)) 0 stream))

(defn accum
  "Change an initial value based on an event stream of functions."
  [init stream]
  (reduce #(%2 %1) init stream))

(defn- uniq-chan [init in]
  (let [out (chan)]
    (go (loop [prev init]
          (if-let [[msg] (<! in)]
            (do (when (not= msg prev)
                  (>! out [msg]))
                (recur msg))
            (close! out))))
    out))

(defn uniq
  "Remove any successive duplicates from the stream."
  [stream]
  (let [init @stream]
    (derive #(uniq-chan init %) init stream)))

(defn cycle
  "Incoming events cycle a sequence of values. Useful for switching between
  states."
  [values stream]
  (->> (reduce (fn [vs _] (next vs)) (core/cycle values) stream)
       (map first)))

(defn throttle
  "Remove any events in a stream that occur too soon after the prior event.
  The timeout is specified in milliseconds."
  [timeout-ms stream]
  (->> stream
       (map (fn [x] [(System/currentTimeMillis) x]))
       (reduce (fn [[t0 _] [t1 x]] [(- t1 t0) x]) [0 nil])
       (remove (fn [[dt _]] (>= timeout-ms dt)))
       (map second)))

(defn- start-sampler
  [channel reference interval ^WeakReference stream-ref]
  (go (loop []
        (<! (timeout interval))
        (when (.get stream-ref)
          (>! channel [@reference])
          (recur)))))

(defn sample
  "Turn a reference into an event stream by deref-ing it at fixed intervals.
  The interval time is specified in milliseconds."
  [interval-ms reference]
  (let [head     (atom @reference)
        channel  (chan)
        observed (observable (track-head head channel))
        stream   (DerivedEventStream. head observed nil)]
    (start-sampler channel reference interval-ms (WeakReference. stream))
    stream))

(defn- delay-chan [delay-ms in]
  (let [out (chan)]
    (go (loop []
          (if-let [msg (<! in)]
            (do (<! (timeout delay-ms))
                (>! out msg)
                (recur))
            (close! out))))
    out))

(defn delay
  "Delay all events by the specified number of milliseconds."
  [delay-ms stream]
  (derive #(delay-chan delay-ms %) @stream stream))

)
