(ns adk.models.registration-test
  "Regressão: só precisar de `adk.agents.llm-agent` (sem requerer
  explicitamente `adk.models.gemini`) já deve bastar para resolver um
  `:model` string tipo \"gemini-2.0-flash\" — era exatamente isso que
  faltava antes (o registro do Gemini só acontecia como efeito colateral de
  um require que nada obrigava a fazer, e qualquer agente construído sem
  esse require explícito falhava com \"Nenhum Llm registrado\").

  Roda num SUBPROCESSO isolado de propósito: dentro do mesmo processo desta
  suíte de testes, `adk.models.gemini-test` já deixa `adk.models.gemini`
  carregado de qualquer jeito, o que mascararia a regressão se testássemos
  in-process."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.shell :as shell]))

(def ^:private probe-code
  "(require 'adk.agents.llm-agent)
   (require 'adk.models)
   (let [llm (adk.models/resolve-model \"gemini-2.0-flash\")]
     (println \"RESOLVED\" (some? llm)))")

(deftest resolving-gemini-model-string-only-requires-llm-agent
  (let [result (shell/sh "clojure" "-M" "-e" probe-code
                          :dir (System/getProperty "user.dir")
                          :env (merge (into {} (System/getenv))
                                      {"GOOGLE_API_KEY" "fake-key-for-registration-test"}))]
    (testing (str "stdout: " (:out result) " / stderr: " (:err result))
      (is (not (re-find #"Nenhum Llm registrado" (:err result)))
          "o modelo deveria resolver sem precisar de require explícito de adk.models.gemini")
      (is (re-find #"RESOLVED true" (:out result))))))
