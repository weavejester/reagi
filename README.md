# Reagi

An experimental [FRP][1] library for Clojure. The API is not fixed in
stone. Things may change.

[1]: http://en.wikipedia.org/wiki/Functional_reactive_programming

## Installation

Add the following dependency to your `project.clj` file:

    [reagi "0.1.0"]

## Usage

Reagi introduces two new reference types: behaviors and event-streams.

A behavior is evaluated each time you deref it. You can think of it as
being similar to an equation cell on a spreadsheet.

```clojure
user=> (require '[reagi.core :as r])
nil
user=> (def a (atom 1))
#'user/a
user=> (def b (r/behavior (+ @a 1)))
#'user/b
user=> @b
2
user=> (reset! a 5)
5
user=> @b
6
```

An event stream is a discrete stream of events. Calling deref on an
event stream will return the latest event pushed to the stream.

```clojure
user=> (def e (r/event-stream))
#'user/e
user=> (r/push! e 1)
nil
user=> @e
1
user=> (r/push! e 2)
nil
user=> @e
2
```

Perhaps more usefully, you can create new event streams using special
versions of the standard seq functions, like map, filter and reduce:

```clojure
user=> (def plus-1 (r/map inc e))
#'user/plus-1
user=> (r/push! e 3)
nil
user=> @plus-1
4
```

## Documentation

* [API Docs](http://weavejester.github.io/reagi)

## License

Copyright © 2013 James Reeves

Distributed under the Eclipse Public License, the same as Clojure.
