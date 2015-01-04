(defproject com.lemondronor/turboshrimp "0.3.6-SNAPSHOT"
  :description "Clojure API for the Parrot AR.Drone."
  :url "https://github.com/wiseman/turboshrimp"
  :license {:name "MIT License"}
  :deploy-repositories [["releases" :clojars]]
  :dependencies [[de.kotka/lazymap "3.1.1"]
                 [gloss "0.2.4"]
                 [org.clojure/clojure "1.6.0"]
                 [org.clojure/tools.logging "0.3.1"]]
  :java-source-paths ["src/java"]
  :profiles {:dev {:dependencies [[com.lemondronor/turboshrimp-xuggler "0.0.2"]
                                  [com.lemonodor/gflags "0.7.1"]
                                  [com.lemonodor/xio "0.2.2"]
                                  [org.clojars.echo/test.mock "0.1.2"]
                                  [seesaw "1.4.4"]]
                   :source-paths ["examples"]
                   :plugins [[lein-cloverage "1.0.2"]]}
             :uberjar {:aot :all}})
