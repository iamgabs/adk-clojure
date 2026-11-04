(ns adk.agents.llm-agent
  "LlmAgent — o agente que de fato fala com um modelo. Espelha
  `agents/LlmAgent.java` (adk-java) / `agents/llm_agent.py` (adk-python).

  `:tools` é um vetor de implementações de `adk.tools/Tool` (construídas via
  `adk.tools.function-tool/function-tool`, `adk.tools.agent-tool/agent-tool`,
  ou um `defrecord` próprio) — nenhuma função crua é auto-envolvida.

  `:sub-agents`, quando presente, não é executado diretamente por este
  agente (diferente de SequentialAgent/ParallelAgent/LoopAgent) — serve só
  como alvos possíveis de `transfer_to_agent` via AutoFlow.

  O require de `adk.models.gemini` abaixo é só pelo efeito colateral: é ele
  que registra os padrões \"gemini-.*\"/\"gemma-.*\" em `adk.models/resolve-model`
  (ver `adk.models/register-llm!`). Sem isso, `:model \"gemini-2.0-flash\"`
  (uma string) nunca resolveria pra nenhum Llm — e esse namespace é o ponto
  de entrada natural de quem só quer construir um LlmAgent, então garantimos
  aqui que o Gemini já vem pronto para uso, igual ao comportamento do
  adk-python/adk-java (lá o Gemini é embutido, não um plugin separado que
  você precisa lembrar de importar)."
  (:require [adk.agents :as agents]
            [adk.flows :as flows]
            [adk.flows.single-flow :as single-flow]
            [adk.flows.auto-flow :as auto-flow]
            [adk.models.gemini]))

(def defaults
  {:sub-agents []
   :before-agent-callbacks [] :after-agent-callbacks []
   :tools []
   :include-contents :all
   :disallow-transfer-to-parent false
   :disallow-transfer-to-peers false
   :before-model-callbacks [] :after-model-callbacks [] :on-model-error-callbacks []
   :before-tool-callbacks [] :after-tool-callbacks [] :on-tool-error-callbacks []})

(defn- select-flow
  "AutoFlow quando há sub-agentes (possíveis alvos de transferência) e a
  transferência não foi explicitamente desabilitada nos dois sentidos;
  SingleFlow caso contrário. Ver limitação conhecida no README/plano: não há
  transferência para pai/irmãos de um agente sem sub-agentes, já que este
  port não mantém um ponteiro de agente-pai."
  [agent]
  (if (and (seq (:sub-agents agent))
           (not (and (:disallow-transfer-to-parent agent) (:disallow-transfer-to-peers agent))))
    auto-flow/flow
    single-flow/flow))

(declare map->LlmAgent)

(defrecord LlmAgent [name description sub-agents
                      before-agent-callbacks after-agent-callbacks
                      model instruction global-instruction tools
                      generate-content-config include-contents
                      disallow-transfer-to-parent disallow-transfer-to-peers
                      output-key input-schema output-schema
                      before-model-callbacks after-model-callbacks on-model-error-callbacks
                      before-tool-callbacks after-tool-callbacks on-tool-error-callbacks]
  agents/Agent
  (agent-name* [_] name)
  (agent-description* [_] description)
  (sub-agents* [_] sub-agents)
  (run-agent [this ctx]
    (let [ctx (assoc ctx :agent this)]
      (agents/invoke-with-agent-callbacks this ctx
        (fn [agent ctx] (flows/run-flow (select-flow agent) agent ctx))))))

(defn llm-agent
  "Constrói um LlmAgent. Campos obrigatórios: :name, :model. Todo o resto
  tem default razoável (ver `defaults`)."
  [opts]
  (when-not (:name opts)
    (throw (ex-info "llm-agent exige :name" {:type ::missing-name})))
  (when-not (:model opts)
    (throw (ex-info "llm-agent exige :model" {:type ::missing-model})))
  (map->LlmAgent (merge defaults opts)))
