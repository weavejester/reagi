# Reagi

An extremely experimental [FRP][1] library for Clojure. Everything
about this library may change. You have been warned :)

[1]: http://en.wikipedia.org/wiki/Functional_reactive_programming

## Installation

Add the following dependency to your `project.clj` file:

    [reagi "0.1.0-SNAPSHOT"]

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

## License

Copyright © 2013 James Reeves

Distributed under the Eclipse Public License, the same as Clojure.
