(defproject com.vitalreactor/probe "1.0.0"
  :description "A library for interacting with dynamic program state"
  :url "http://github.com/vitalreactor/probe"
  :license {:name "MIT License" :url "http://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [org.clojure/core.memoize "0.5.6"]]
  :profiles {:dev {:dependencies [[midje "1.6.3"]]}
             :repl {:plugins [[cider/cider-nrepl "0.40.0"]]}}
  :plugins [[lein-midje "3.0.0"]])


