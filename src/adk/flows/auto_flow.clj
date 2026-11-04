(ns adk.flows.auto-flow
  "AutoFlow = SingleFlow + a tool sintética `transfer_to_agent`, que permite
  ao LLM pedir para passar o controle da conversa para outro agente (pelo
  nome). Espelha `AutoFlow.java`/`auto_flow.py`.

  Quem efetivamente resolve o nome para um agente da árvore e continua a
  execução a partir dele é o Runner (`adk.runner/run-with-transfers`) — este
  namespace só injeta a tool e deixa `EventActions.transfer-to-agent` setado
  no Event de function-response correspondente."
  (:require [adk.flows.single-flow :as single]
            [adk.tools :as tools]
            [adk.context :as context]))

(defrecord TransferToAgentTool []
  tools/Tool
  (tool-name [_] "transfer_to_agent")
  (tool-description [_]
    "Transfere o controle da conversa para outro agente (sub-agente, pai ou irmão) pelo nome.")
  (tool-declaration [_]
    {:name "transfer_to_agent"
     :description "Transfere o controle da conversa para outro agente pelo nome."
     :parameters {:type "object"
                  :properties {:agent_name {:type "string" :description "Nome do agente alvo."}}
                  :required [:agent_name]}})
  (long-running? [_] false)
  (invoke-tool [_ args tool-context]
    (let [target (or (get args "agent_name") (get args :agent_name))]
      (context/request-transfer! tool-context target)
      {:result (str "Transferindo para " target)})))

(def transfer-to-agent-tool (->TransferToAgentTool))

(defn- transfer-processor
  [request _ctx]
  (update request :tools assoc "transfer_to_agent" transfer-to-agent-tool))

(def request-processors (conj single/request-processors transfer-processor))

(def flow {:request-processors request-processors
           :response-processors single/response-processors})
