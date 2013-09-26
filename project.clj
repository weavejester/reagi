(defproject reagi "0.5.0"
  :description "An experimental FRP library"
  :url "https://github.com/weavejester/reagi"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/core.async "0.1.222.0-83d0c2-alpha"]]
  :plugins [[codox "0.6.6"]]
  :profiles
  {:dev {:dependencies [[criterium "0.4.2"]]}})
