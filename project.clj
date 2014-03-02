(defproject reagi "0.8.1"
  :description "An FRP library for Clojure and ClojureScript"
  :url "https://github.com/weavejester/reagi"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/core.async "0.1.267.0-0d7780-alpha"]
                 [org.clojure/clojurescript "0.0-2156"]]
  :plugins [[codox "0.6.6"]
            [lein-cljsbuild "1.0.2"]]
  :source-paths ["src/clojure" "src/cljs"]
  :test-paths ["test/clojure"]
  :jvm-opts ["-Xmx1g"]
  :cljsbuild
  {:builds [{:source-paths ["src/clojure" "src/cljs"]
             :compiler {:output-to "target/main.js"}}]}
  :profiles
  {:dev  {:plugins [[com.cemerick/austin "0.1.4"]]
          :dependencies [[criterium "0.4.2"]]}
   :test {:plugins [[com.cemerick/clojurescript.test "0.3.0-SNAPSHOT"]]
          :cljsbuild
          {:builds ^:replace [{:source-paths ["src/clojure" "src/cljs" "test/cljs"]
                               :compiler {:output-to "target/test.js"}}]
           :test-commands {"unit-tests" ["phantomjs" :runner "target/test.js"]}}}}
  :aliases
  {"test-cljs" ["with-profile" "test" "cljsbuild" "test"]
   "test-all"  ["do" "test," "test-cljs"]})
