(ns two-agent-pipeline
  "Exemplo mínimo de ponta a ponta: um SequentialAgent com dois LlmAgents —
  o primeiro extrai o nome da pessoa e grava em `output-key`, o segundo lê
  esse valor do estado da sessão (via `adk.context/current-session`) para
  compor sua instrução e cumprimentar a pessoa pelo nome.

  Requer GOOGLE_API_KEY (API key do Gemini — ver
  https://aistudio.google.com/apikey), vindo de um `.env` na raiz do
  projeto (copie `.env.example` para `.env` e preencha) ou de uma variável
  de ambiente real. Rode com:

    clojure -M -e \"(require 'two-agent-pipeline) (two-agent-pipeline/-main)\"

  ou abra um REPL (`clj -A:repl`) e chame `(-main)` interativamente."
  (:require [adk.env :as env]
            [adk.agents.llm-agent :as llm-agent]
            [adk.agents.sequential-agent :as sequential-agent]
            [adk.context :as context]
            [adk.runner :as runner]
            [adk.events :as events]))

(env/load-env!)

(defn- build-pipeline []
  (let [extractor (llm-agent/llm-agent
                     {:name "extractor"
                      :model "gemini-2.0-flash"
                      :instruction "Extraia só o primeiro nome da pessoa na mensagem do usuário. Responda só com o nome."
                      :output-key "user-name"})
        greeter (llm-agent/llm-agent
                  {:name "greeter"
                   :model "gemini-2.0-flash"
                   :instruction (fn [ctx]
                                  (str "Cumprimente a pessoa chamada "
                                       (get (:state (context/current-session ctx)) "user-name")
                                       " de forma calorosa, numa frase só."))})]
    (sequential-agent/sequential-agent {:name "pipeline" :sub-agents [extractor greeter]})))

(defn -main [& _args]
  (let [pipeline (build-pipeline)
        rnr (runner/in-memory-runner pipeline "two-agent-pipeline-example")
        evs (runner/run rnr {:user-id "demo-user" :session-id "demo-session"
                              :new-message {:role "user" :parts [{:text "Oi, meu nome é Marina."}]}})]
    (doseq [ev evs]
      (println (str "[" (:author ev) "] " (events/text ev))))))
