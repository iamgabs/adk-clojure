(ns adk.agents
  "Protocolo Agent + o comportamento comum a todos os tipos de agente
  (before/after-agent callbacks), espelhando `agents/BaseAgent.java`
  (adk-java) e `agents/base_agent.py` (adk-python).

  Convenções de callback usadas em toda a base (sempre: primeira função da
  lista que devolver um valor truthy \"vence\" e encerra a busca — espelha a
  semântica de lista de callbacks do ADK original):

    before-agent-callback: (fn [agent ctx] -> Content-ou-nil)
      truthy -> pula a execução do agente; o Content vira o único Event.
    after-agent-callback:  (fn [agent ctx] -> Content-ou-nil)
      truthy -> um Event extra com esse Content é anexado ao final.
    before-model-callback: (fn [agent llm-request ctx] -> LlmResponse-ou-nil)
      truthy -> pula a chamada real ao modelo, usa essa resposta.
    after-model-callback:  (fn [agent llm-response ctx] -> LlmResponse-ou-nil)
      truthy -> substitui a resposta do modelo.
    on-model-error-callback: (fn [agent llm-request ctx erro] -> LlmResponse-ou-nil)
      truthy -> recupera do erro com essa resposta; senão o erro é relançado.
    before-tool-callback: (fn [tool args tool-context] -> resultado-ou-nil)
    after-tool-callback:  (fn [tool args tool-context resultado] -> resultado-ou-nil)
    on-tool-error-callback: (fn [tool args tool-context erro] -> resultado-ou-nil)"
  (:require [adk.events :as events]
            [adk.context :as context]))

(defprotocol Agent
  (agent-name* [this])
  (agent-description* [this])
  (sub-agents* [this])
  (run-agent [this ctx]
    "Executa este agente sob o InvocationContext `ctx` e retorna uma
    sequência preguiçosa de Event."))

(defn- first-truthy [fns & args]
  (some #(apply % args) fns))

(defn invoke-with-agent-callbacks
  "Envolve `run-impl-fn` (fn [agent ctx] -> seq de Event, a implementação
  real de `_run_async_impl`) com a semântica de before/after-agent-callback.
  Toda implementação de `run-agent` deve passar por aqui, em vez de duplicar
  essa lógica em cada defrecord de agente."
  [{:keys [before-agent-callbacks after-agent-callbacks] :as agent} ctx run-impl-fn]
  (lazy-seq
    (if-let [override (first-truthy before-agent-callbacks agent ctx)]
      [(context/append-event! ctx
         (events/event {:invocation-id (:invocation-id ctx) :author (:name agent)
                         :content override :branch (:branch ctx)}))]
      (concat
        (run-impl-fn agent ctx)
        (lazy-seq
          (when-let [after-content (first-truthy after-agent-callbacks agent ctx)]
            [(context/append-event! ctx
               (events/event {:invocation-id (:invocation-id ctx) :author (:name agent)
                               :content after-content :branch (:branch ctx)}))]))))))
