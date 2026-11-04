(ns adk.agents.loop-agent
  "LoopAgent — repete os sub-agentes (em sequência, como SequentialAgent) até
  `max-iterations` ou até um Event vir com `actions.escalate=true`
  (convenção: a tool built-in `adk.tools/exit-loop-tool`). Espelha
  `LoopAgent.java`/`loop_agent.py`.

  O corte por escalate acontece sem forçar a realização antecipada do
  restante da sequência: `events-until-escalate` só decide se deve iterar de
  novo depois que o consumidor já drenou a iteração corrente inteira."
  (:require [adk.agents :as agents]))

(def defaults
  {:sub-agents [] :before-agent-callbacks [] :after-agent-callbacks []
   :max-iterations nil})

(defn- events-until-escalate
  "Corta `evs` assim que encontra um Event com escalate=true (inclusive).
  NOTA: usa `rest`, não desestruturação `[ev & more]` — desestruturar com
  `&` chama `next`, que força a realização do elemento SEGUINTE da lazy-seq
  (quebrando a garantia de não disparar, por exemplo, mais uma chamada ao
  LLM depois do evento que já encerrou o loop)."
  [evs escalated?]
  (lazy-seq
    (when (seq evs)
      (let [ev (first evs)]
        (if (get-in ev [:actions :escalate])
          (do (reset! escalated? true) [ev])
          (cons ev (events-until-escalate (rest evs) escalated?)))))))

(defrecord LoopAgent [name description sub-agents max-iterations
                       before-agent-callbacks after-agent-callbacks]
  agents/Agent
  (agent-name* [_] name)
  (agent-description* [_] description)
  (sub-agents* [_] sub-agents)
  (run-agent [this ctx]
    (let [ctx (assoc ctx :agent this)]
      (agents/invoke-with-agent-callbacks this ctx
        (fn [agent ctx]
          (letfn [(loop-iter [n]
                    (lazy-seq
                      (when (or (nil? (:max-iterations agent)) (< n (:max-iterations agent)))
                        (let [escalated? (atom false)
                              iter-events (events-until-escalate
                                            (mapcat #(agents/run-agent % ctx) (:sub-agents agent))
                                            escalated?)]
                          (concat iter-events
                                  (lazy-seq (when-not @escalated? (loop-iter (inc n)))))))))]
            (loop-iter 0)))))))

(defn loop-agent
  [opts]
  (when-not (:name opts)
    (throw (ex-info "loop-agent exige :name" {:type ::missing-name})))
  (map->LoopAgent (merge defaults opts)))
