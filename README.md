# Reagi

[![Build Status](https://travis-ci.org/weavejester/reagi.png?branch=master)](https://travis-ci.org/weavejester/reagi)

Reagi is an [FRP][1] library for Clojure and ClojureScript, built on
top of [core.async][2]. It provides tools to model and manipulation
values that change over time.

[1]: http://en.wikipedia.org/wiki/Functional_reactive_programming
[2]: https://github.com/clojure/core.async

## Installation

Add the following dependency to your `project.clj` file:

    [reagi "0.10.1"]

## Overview

Reagi introduces two new reference types, behaviors and event streams,
which are collectively known as signals.

```clojure
user=> (require '[reagi.core :as r])
nil
```

A behavior models continuous change, and is evaluated each time you
dereference it.

```clojure
user=> (def t (r/behavior (System/currentTimeMillis)))
#'user/t
user=> @t
1380475144266
user=> @t
1380475175587
```

An event stream models a series of discrete changes. Events must be
expicitly delivered to the stream. Dereferencing the event stream
returns the last value pushed onto the stream.

```clojure
user=> (def e (r/events))
#'user/e
user=> (r/deliver e 1)
#<Events@66d278af: 1>
user=> @e
1
```

If the event stream is empty (i.e. unrealized), dereferencing it will
block the running thread, much like a promise.

Unlike promises, event streams can have more than one value delivered
to them, and may be constructed with an initial value:

```clojure
user=> (def e (r/events 1))
#'user/e
user=> @e
1
```

Reagi provides a number of functions for transforming event streams,
which mimic many of the standard Clojure functions for dealing with
seqs:

```clojure
user=> (def incremented (r/map inc e))
#'user/m
user=> (r/deliver e 2)
#<Events@66d278af: 2>
user=> @incremented
3
```

For a full list, see the [API docs](http://weavejester.github.io/reagi/reagi.core.html).

Event streams can interoperate with core.async channels using `port`
and `subscribe`. The `port` function returns a write-only stream that
will deliver values to the stream:

```clojure
user=> (>!! (r/port e) 3)
true
user=> @e
3
```

The `subscribe` function allows an existing channel to be registered
as an output for the stream.

```clojure
user=> (def ch (async/chan 1))
#'user/ch
user=> (r/subscribe e ch)
#<ManyToManyChannel clojure.core.async.impl.channels.ManyToManyChannel@3b4df45f>
user=> (r/deliver e 4)
#<Events@66d278af: 4>
user=> (<!! ch)
4
```

Note that this may cause the source stream to block if you don't
consume the items in the channel, or provide a sufficient buffer.

Behaviors can be converted to event streams by sampling them at a
specified interval in milliseconds:

```clojure
user=> (def et (r/sample 1000 t))
#'user/et
user=> @et
1380475969885
```

Signals can be completed with the `completed` function, which acts in
a similar fashion to `clojure.core/reduced`. Signals that are
completed will always deref to the same value. Any values pushed to a
completed event stream will be ignored.

```clojure
user=> (def e (r/events))
#'user/e
user=> (r/deliver e (r/completed 1))
#<Events@66d278af: 1>
user=> (r/deliver e 2)
#<Events@66d278af: 1>
user=> @e
1
user=> (r/complete? e)
true
```


## Documentation

* [API Docs](http://weavejester.github.io/reagi/reagi.core.html)


## Differences in ClojureScript version

The ClojureScript version of Reagi has two main differences from the
Clojure version:

1. Trying to deref an unrealized stream blocks the current thread in
   Clojure, but returned js/undefined in ClojureScript.

2. Event streams that fall out of scope in Clojure are automatically
   cleaned up. In ClojureScript, the `reagi.core/dispose` function
   must be manually called on streams before they fall out of scope.


## License

Copyright © 2014 James Reeves

Distributed under the Eclipse Public License, the same as Clojure.
