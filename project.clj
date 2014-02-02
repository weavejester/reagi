(defproject reagi "0.7.1"
  :description "An FRP library for Clojure and ClojureScript"
  :url "https://github.com/weavejester/reagi"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/core.async "0.1.267.0-0d7780-alpha"]
                 [org.clojure/clojurescript "0.0-2156"]]
  :plugins [[codox "0.6.6"]
            [com.cemerick/austin "0.1.3"]
            [lein-cljsbuild "1.0.1"]]
  :source-paths ["src/clojure" "src/cljs"]
  :cljsbuild
  {:builds [{:source-paths ["src/clojure" "src/cljs"]
             :compiler {:output-to "target/main.js"}}]}
  :profiles
  {:dev {:dependencies [[criterium "0.4.2"]]}})
