# Reagi

[![Build Status](https://travis-ci.org/weavejester/reagi.png?branch=master)](https://travis-ci.org/weavejester/reagi)

An experimental [FRP][1] library for Clojure based on [core.async][2].

Note that the API is not yet fixed in stone, and may change.

[1]: http://en.wikipedia.org/wiki/Functional_reactive_programming
[2]: https://github.com/clojure/core.async

## Installation

Add the following dependency to your `project.clj` file:

    [reagi "0.6.1"]

## Overview

Reagi introduces two new reference types: behaviors and event streams.

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
expicitly pushed onto the stream. Dereferencing the event stream
returns the last value pushed onto the stream.

```clojure
user=> (def e (r/events))
#'user/e
user=> (r/push! e 1)
#<Events@66d278af: 1>
user=> @e
1
```

If the event stream is empty, dereferencing it will block the running
thread, much like a promise.

Reagi provides a number of functions for transforming event streams,
which mimic many of the standard Clojure functions for dealing with
seqs:

```clojure
user=> (def incremented (r/map inc e))
#'user/m
user=> (r/push! e 2)
#<Events@66d278af: 2>
user=> @incremented
3
```

Finally, behaviors can be converted to event streams by sampling them
at a specified interval:

```clojure
user=> (def et (r/sample 1000 t))
#'user/et
user=> @et
1380475969885
```

## Documentation

* [API Docs](http://weavejester.github.io/reagi/reagi.core.html)

## License

Copyright © 2013 James Reeves

Distributed under the Eclipse Public License, the same as Clojure.
