(ns adk.cli.create-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [adk.cli.create :as create]))

(defn- temp-dir! []
  (.toFile (java.nio.file.Files/createTempDirectory "adk-create-test" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- delete-recursively! [^java.io.File f]
  (when (.isDirectory f) (run! delete-recursively! (.listFiles f)))
  (.delete f))

(deftest generates-expected-files-and-content
  (let [base (temp-dir!)
        dir (io/file base "my-agent")]
    (try
      (let [{:keys [agent-ns]} (create/create-agent! "My Agent!"
                                                       {:dir (str dir)
                                                        :model "gemini-2.0-flash"
                                                        :adk-clojure-root "/fake/root"})]
        (is (= 'my-agent.agent agent-ns))
        (testing "deps.edn aponta pro :local/root informado"
          (is (str/includes? (slurp (io/file dir "deps.edn")) "/fake/root")))
        (testing "o namespace do agente foi gerado no caminho certo"
          (let [agent-file (io/file dir "src" "my_agent" "agent.clj")]
            (is (.exists agent-file))
            (let [content (slurp agent-file)]
              (is (str/includes? content "(ns my-agent.agent"))
              (is (str/includes? content "(def root-agent"))
              (is (str/includes? content "\"gemini-2.0-flash\""))
              (is (str/includes? content "\"my-agent\""))
              (testing "carrega .env automaticamente, sem precisar de export manual"
                (is (str/includes? content "[adk.env :as env]"))
                (is (str/includes? content "(env/load-env!)"))))))
        (testing "README, .env.example e .gitignore também são gerados"
          (is (.exists (io/file dir "README.md")))
          (is (.exists (io/file dir ".env.example")))
          (is (str/includes? (slurp (io/file dir ".env.example")) "GOOGLE_API_KEY="))
          (testing ".gitignore nunca deixa o .env de verdade vazar pro git"
            (is (str/includes? (slurp (io/file dir ".gitignore")) ".env")))))
      (finally (delete-recursively! base)))))

(deftest refuses-to-overwrite-non-empty-dir
  (let [base (temp-dir!)
        dir (io/file base "existing")]
    (try
      (.mkdirs dir)
      (spit (io/file dir "sentinel.txt") "x")
      (is (thrown? clojure.lang.ExceptionInfo
                   (create/create-agent! "existing" {:dir (str dir) :adk-clojure-root "/fake/root"})))
      (finally (delete-recursively! base)))))
