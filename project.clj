(defproject reagi "0.3.1"
  :description "An experimental FRP library"
  :url "https://github.com/weavejester/reagi"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.3.0"]]
  :plugins [[codox "0.6.4"]]
  :profiles
  {:dev {:dependencies [[criterium "0.4.1"]]}
   :1.4 {:dependencies [[org.clojure/clojure "1.4.0"]]}
   :1.5 {:dependencies [[org.clojure/clojure "1.5.1"]]}})
