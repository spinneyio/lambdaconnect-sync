(defproject io.spinney/lambdaconnect-sync "1.0.10"
  :description "Synchronisation library"
  :url "https://github.com/spinneyio/lambdaconnect-sync"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.3"]
                 [org.clojure/algo.generic "0.1.3"]
                 [io.spinney/lambdaconnect-model "1.0.3"]]
  :main ^:skip-aot lambdaconnect-sync.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}})
