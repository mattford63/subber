(defproject subber "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure "1.10.1"]
                 [ring-server "0.5.0"]
                 [reagent "0.9.0-rc3"]
                 [reagent-utils "0.3.3"]
                 [ring "1.8.0"]
                 [ring/ring-defaults "0.3.2"]
                 [hiccup "1.0.5"]
                 [yogthos/config "1.1.7"]
                 [org.clojure/clojurescript "1.10.597"
                  :scope "provided"
                  :exclusions [com.google.protobuf/protobuf-java]] ;; need a much later version of protobuf for ovotech/clj-gcp
                 [metosin/reitit "0.3.10"]
                 [pez/clerk "1.0.0"]
                 [venantius/accountant "0.2.5"
                  :exclusions [org.clojure/tools.reader]]
                 [integrant "0.8.0"]
                 [integrant/repl "0.3.1"]
                 [cheshire "5.10.0"]
                 [ovotech/clj-gcp "0.4.2-SNAPSHOT"]
                 [http-kit "2.3.0"]
                 [com.taoensso/sente "1.15.0"]
                 [org.clojure/core.async "0.7.559"]
                 [ring/ring-anti-forgery "1.3.0"]]

  :plugins [[lein-environ "1.1.0"]
            [lein-cljsbuild "1.1.7"]
            [lein-asset-minifier "0.4.6"
             :exclusions [org.clojure/clojure]]]

  :ring {:handler subber.handler/app
         :uberwar-name "subber.war"}

  :min-lein-version "2.5.0"
  :uberjar-name "subber.jar"
  :main subber.system
  :clean-targets ^{:protect false}
  [:target-path
   [:cljsbuild :builds :app :compiler :output-dir]
   [:cljsbuild :builds :app :compiler :output-to]]

  :source-paths ["src/clj" "src/cljc" "src/cljs" "config"]
  :resource-paths ["resources" "target/cljsbuild"]

  :minify-assets
  [[:css {:source "resources/public/css/site.css"
          :target "resources/public/css/site.min.css"}]]

  :cljsbuild
  {:builds {:min
            {:source-paths ["src/cljs" "src/cljc" "env/prod/cljs"]
             :compiler
             {:output-to        "target/cljsbuild/public/js/app.js"
              :output-dir       "target/cljsbuild/public/js"
              :source-map       "target/cljsbuild/public/js/app.js.map"
              :optimizations :advanced
              :infer-externs true
              :pretty-print  false}}
            :app
            {:source-paths ["src/cljs" "src/cljc" "env/dev/cljs"]
             :figwheel {:on-jsload "subber.core/mount-root"}
             :compiler
             {:main "subber.dev"
              :asset-path "/js/out"
              :output-to "target/cljsbuild/public/js/app.js"
              :output-dir "target/cljsbuild/public/js/out"
              :source-map true
              :optimizations :none
              :pretty-print  true}}}}

  :figwheel
  {:http-server-root "public"
   :nrepl-port 7002
   :nrepl-middleware [cider.piggieback/wrap-cljs-repl
                      cider.nrepl/cider-middleware
                      refactor-nrepl.middleware/wrap-refactor
                      ]
   :css-dirs ["resources/public/css"]
   }

  :bin {:name "subber"
        :bin-path "~/bin"
        :jvm-opts ["-server" "-Dfile.enconding=utf-8" "$JVM_OPTS"]}

  :profiles {:dev {:repl-options {:init-ns subber.repl}
                   :dependencies [[cider/piggieback "0.4.2"]
                                  [binaryage/devtools "0.9.11"]
                                  [ring/ring-mock "0.4.0"]
                                  [ring/ring-devel "1.8.0"]
                                  [prone "2019-07-08"]
                                  [figwheel-sidecar "0.5.19"]
                                  [nrepl "0.6.0"]
                                  [pjstadig/humane-test-output "0.10.0"]]
                   :source-paths ["env/dev/clj"]
                   :plugins [[lein-figwheel "0.5.19"]
                             [cider/cider-nrepl "0.24.0-SNAPSHOT"]
                             [org.clojure/tools.namespace "0.3.0-alpha4"
                              :exclusions [org.clojure/tools.reader]]
                             [refactor-nrepl "2.5.0-SNAPSHOT"
                              :exclusions [org.clojure/clojure]]
                             [lein-binplus "0.6.6"]]
                   :injections [(require 'pjstadig.humane-test-output)
                                (pjstadig.humane-test-output/activate!)]
                   :env {:dev true}}

             :uberjar {:hooks [minify-assets.plugin/hooks]
                       :source-paths ["env/prod/clj"]
                       :resource-paths ["config"]
                       :prep-tasks ["compile" ["cljsbuild" "once" "min"]]
                       :env {:production true}
                       :aot :all
                       :omit-source true}})
