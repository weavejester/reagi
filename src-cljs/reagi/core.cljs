(ns reagi.core
  (:require-macros [reagi.core :refer (behavior)]
                   [cljs.core.async.macros :refer (go go-loop)])
  (:require [cljs.core.async :refer (alts! chan close! timeout <! >!)]))

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
