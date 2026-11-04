(ns adk.tools.agent-tool
  "AgentTool — envolve um Agent inteiro como uma `adk.tools/Tool` de outro
  agente (o mecanismo por trás de \"agente como ferramenta\"). Espelha
  `tools/AgentTool.java`/`agent_tool.py`.

  Cada chamada roda o agente envolvido numa sessão isolada e nova (via um
  InMemoryRunner interno) e devolve seu texto de resposta final como
  function-response — o sub-agente não compartilha sessão/estado com quem
  o chamou."
  (:require [adk.tools :as tools]
            [adk.runner :as runner]
            [adk.agents :as agents]
            [adk.events :as events]))

(defrecord AgentTool [agent name description]
  tools/Tool
  (tool-name [_] name)
  (tool-description [_] description)
  (tool-declaration [_]
    {:name name
     :description description
     :parameters {:type "object"
                  :properties {:request {:type "string" :description "Mensagem para o agente."}}
                  :required [:request]}})
  (long-running? [_] false)
  (invoke-tool [_ args _tool-context]
    (let [text-in (or (get args "request") (get args :request) "")
          rnr (runner/in-memory-runner agent name)
          evs (runner/run rnr {:user-id "agent-tool"
                                :session-id (str (random-uuid))
                                :new-message {:role "user" :parts [{:text text-in}]}})
          final-ev (last evs)]
      {:result (if final-ev (events/text final-ev) "")})))

(defn agent-tool
  "Envolve `agent` como uma Tool. `:name`/`:description` default para os do
  próprio agente."
  ([agent] (agent-tool agent {}))
  ([agent {:keys [name description]}]
   (->AgentTool agent
                (or name (agents/agent-name* agent))
                (or description (agents/agent-description* agent) ""))))
