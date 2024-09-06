(defproject io.spinney/lambdaconnect-sync "1.0.27"
  :description "Synchronisation library"
  :url "https://github.com/spinneyio/lambdaconnect-sync"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/spec.alpha "0.5.238"]
                 [org.clojure/clojure "1.11.4"]
                 [org.clojure/test.check "1.1.1"]
                 [io.spinney/lambdaconnect-model "1.0.23"]
                 [thheller/shadow-cljs "2.28.12"]]
  :target-path "target/%s"
  :profiles {:uberjar {:aot :none
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}
             :dev           [:project/dev]
             :test          [:project/dev :project/test]

             :project/test {:dependencies [[com.datomic/peer "1.0.7075"]]}
             :project/dev {:plugins [[com.jakemccrary/lein-test-refresh "0.25.0"]]}})
