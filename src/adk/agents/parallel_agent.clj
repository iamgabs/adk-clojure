(ns adk.agents.parallel-agent
  "ParallelAgent — roda os sub-agentes concorrentemente, cada um num branch
  isolado (não vê o histórico dos irmãos — ver `adk.context/events-on-branch`),
  mas compartilhando a MESMA Session (estado: last-write-wins). Espelha
  `ParallelAgent.java`/`parallel_agent.py`."
  (:require [adk.agents :as agents]
            [adk.context :as context]
            [adk.concurrency :as concurrency]))

(def defaults
  {:sub-agents [] :before-agent-callbacks [] :after-agent-callbacks []})

(defrecord ParallelAgent [name description sub-agents
                           before-agent-callbacks after-agent-callbacks]
  agents/Agent
  (agent-name* [_] name)
  (agent-description* [_] description)
  (sub-agents* [_] sub-agents)
  (run-agent [this ctx]
    (let [ctx (assoc ctx :agent this)]
      (agents/invoke-with-agent-callbacks this ctx
        (fn [agent ctx]
          (let [per-branch (map (fn [sub]
                                   (agents/run-agent
                                     sub (context/child-context
                                           ctx {:branch-segment (agents/agent-name* sub)})))
                                 (:sub-agents agent))]
            (concurrency/merge-by-arrival per-branch)))))))

(defn parallel-agent
  [opts]
  (when-not (:name opts)
    (throw (ex-info "parallel-agent exige :name" {:type ::missing-name})))
  (map->ParallelAgent (merge defaults opts)))
