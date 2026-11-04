(ns build
  "Build script for adk-clojure (`clj -T:build <task>`)."
  (:require [clojure.tools.build.api :as b]))

(def lib 'io.github.iamgabs/adk-clojure)
(def version "0.1.0-SNAPSHOT")
(def class-dir "target/classes")
(def basis (delay (b/create-basis {:project "deps.edn"})))
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis @basis
                :src-dirs ["src"]})
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file}))

(defn install
  "Instala o jar no repositório Maven local (~/.m2) desta máquina — depois
  disso, qualquer projeto Clojure local pode depender de adk-clojure via
  {:mvn/version \"0.1.0-SNAPSHOT\"}, sem precisar de :local/root nem de
  clonar o repositório. SEMPRE reconstrói o jar primeiro (não reusa um jar
  antigo em target/), pra nunca instalar uma versão desatualizada do
  código-fonte."
  [opts]
  (jar opts)
  (b/install {:class-dir class-dir
              :lib lib
              :version version
              :basis @basis
              :jar-file jar-file}))
