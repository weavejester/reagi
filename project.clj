(defproject reagi "0.6.3"
  :description "An experimental FRP library"
  :url "https://github.com/weavejester/reagi"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/core.async "0.1.242.0-44b1e3-alpha"]
                 [org.clojure/clojurescript "0.0-2080"]]
  :plugins [[codox "0.6.6"]
            [lein-cljsbuild "1.0.0"]]
  :cljsbuild
  {:builds [{:source-paths ["src-cljs"]
             :compiler {:output-to "target/main.js"}}]}
  :profiles
  {:dev {:dependencies [[criterium "0.4.2"]]}})
