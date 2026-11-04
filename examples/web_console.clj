(ns web-console
  "Exemplo de uso do `adk.web` — sobe o console de teste no navegador pra
  um agente simples. Requer GOOGLE_API_KEY (via `.env` na raiz do projeto,
  ou variável de ambiente real).

  Rode com:
    clojure -A:repl -M -e \"(require 'web-console) (web-console/-main)\"

  e abra http://localhost:8080 no navegador. Ctrl+C encerra."
  (:require [adk.env :as env]
            [adk.agents.llm-agent :as llm-agent]
            [adk.runner :as runner]
            [adk.web :as web]))

(env/load-env!)

(defn -main [& _args]
  (let [agent (llm-agent/llm-agent
                {:name "assistant" :model "gemini-2.0-flash"
                 :instruction "Você é um assistente útil. Responda de forma breve e direta."})
        rnr (runner/in-memory-runner agent "assistant")
        server (web/start-server! {"assistant" rnr} {:port 8080})]
    (println "Console de teste em http://localhost:8080 (Ctrl+C para encerrar)")
    (.join server)))
