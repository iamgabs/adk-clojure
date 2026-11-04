(ns adk.env-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.java.io :as io]
            [adk.env :as env]))

(use-fixtures :each (fn [f] (env/reset-env!) (f) (env/reset-env!)))

(defn- temp-env-file! [content]
  (let [f (java.io.File/createTempFile "adk-env-test" ".env")]
    (spit f content)
    (.deleteOnExit f)
    f))

(deftest loads-key-value-pairs
  (let [f (temp-env-file! "GOOGLE_API_KEY=abc123\nFOO=bar\n")]
    (env/load-env! (str f))
    (is (= "abc123" (env/getenv "GOOGLE_API_KEY")))
    (is (= "bar" (env/getenv "FOO")))))

(deftest ignores-comments-blank-lines-and-strips-quotes
  (let [f (temp-env-file! "# comentário\n\nQUOTED=\"hello world\"\nSINGLE='x'\n")]
    (env/load-env! (str f))
    (is (= "hello world" (env/getenv "QUOTED")))
    (is (= "x" (env/getenv "SINGLE")))))

(deftest missing-file-is-a-silent-noop
  (is (nil? (env/load-env! "/does/not/exist/.env")))
  (is (nil? (env/getenv "WHATEVER_KEY"))))

(deftest falls-back-to-real-process-env-when-key-not-in-dotenv
  ;; Não dá pra setar uma env var real de dentro da JVM de forma portátil,
  ;; então validamos indiretamente: PATH quase certamente existe no
  ;; ambiente real e não está em nenhum .env carregado aqui.
  (let [f (temp-env-file! "ONLY_IN_DOTENV=yes\n")]
    (env/load-env! (str f))
    (is (= "yes" (env/getenv "ONLY_IN_DOTENV")))
    (is (some? (env/getenv "PATH")))
    (is (nil? (env/getenv "DEFINITELY_NOT_SET_ANYWHERE_XYZ")))))
