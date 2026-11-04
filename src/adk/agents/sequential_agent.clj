(ns adk.agents.sequential-agent
  "SequentialAgent — roda os sub-agentes em ordem, todos sob o MESMO
  InvocationContext/Session (histórico e estado compartilhados). Espelha
  `SequentialAgent.java`/`sequential_agent.py`."
  (:require [adk.agents :as agents]))

(def defaults
  {:sub-agents [] :before-agent-callbacks [] :after-agent-callbacks []})

(defrecord SequentialAgent [name description sub-agents
                             before-agent-callbacks after-agent-callbacks]
  agents/Agent
  (agent-name* [_] name)
  (agent-description* [_] description)
  (sub-agents* [_] sub-agents)
  (run-agent [this ctx]
    (let [ctx (assoc ctx :agent this)]
      (agents/invoke-with-agent-callbacks this ctx
        (fn [agent ctx] (mapcat #(agents/run-agent % ctx) (:sub-agents agent)))))))

(defn sequential-agent
  [opts]
  (when-not (:name opts)
    (throw (ex-info "sequential-agent exige :name" {:type ::missing-name})))
  (map->SequentialAgent (merge defaults opts)))
