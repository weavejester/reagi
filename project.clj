(defproject reagi "0.6.3"
  :description "An experimental FRP library"
  :url "https://github.com/weavejester/reagi"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/core.async "0.1.242.0-44b1e3-alpha"]
                 [org.clojure/clojurescript "0.0-2120"]]
  :plugins [[codox "0.6.6"]
            [com.cemerick/austin "0.1.3"]
            [lein-cljsbuild "1.0.0"]]
  :source-paths ["src/clojure" "src/cljs"]
  :cljsbuild
  {:builds [{:source-paths ["src/clojure" "src/cljs"]
             :compiler {:output-to "target/main.js"}}]}
  :profiles
  {:dev {:dependencies [[criterium "0.4.2"]]}})
