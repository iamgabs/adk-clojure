(ns adk.flows
  "O flow loop — equivalente a `flows/llm_flows/BaseLlmFlow.java`/
  `base_llm_flow.py`: monta o LlmRequest via request processors, chama o
  modelo (com before/after/on-error-model callbacks), e se a resposta
  trouxer function-calls, executa as tools e repete, até uma resposta final
  ou `run-config.max-llm-calls`.

  Tudo aqui é preguiçoso por padrão (lazy-seq/mapcat); a única concorrência
  real do sistema vive em `adk.concurrency`, usada pelo Runner/ParallelAgent
  no topo da pilha — nenhuma thread é criada dentro deste namespace."
  (:require [adk.models :as models]
            [adk.events :as events]
            [adk.tools :as tools]
            [adk.context :as context]))

(defn- first-truthy [fns & args]
  (some #(apply % args) fns))

(defn apply-request-processors [processors request ctx]
  (reduce (fn [req proc] (proc req ctx)) request processors))

(defn apply-response-processors [processors response ctx]
  (reduce (fn [resp proc] (proc resp ctx)) response processors))

(defn- base-request [agent llm]
  {:model (models/model-name llm)
   :contents []
   :system-instruction nil
   :config (or (:generate-content-config agent) {})
   :tools {}
   :grounding-tools []})

(defn- response->event [response ctx author]
  (events/event {:invocation-id (:invocation-id ctx)
                 :author author
                 :content (:content response)
                 :branch (:branch ctx)
                 :partial (:partial response)
                 :turn-complete (:turn-complete response)}))

(defn- call-model
  [agent request ctx llm stream?]
  (if-let [override (first-truthy (:before-model-callbacks agent) agent request ctx)]
    [override]
    (do
      (context/inc-llm-call-count! ctx)
      (try
        (models/generate-content llm request stream?)
        (catch Throwable t
          (if-let [handled (first-truthy (:on-model-error-callbacks agent) agent request ctx t)]
            [handled]
            (throw t)))))))

(defn- run-after-model [agent response ctx]
  (if-let [override (first-truthy (:after-model-callbacks agent) agent response ctx)]
    override
    response))

(defn- single-step
  "Uma chamada ao modelo: monta o request, chama o modelo, pós-processa
  cada LlmResponse e devolve {:events [...] :tools {nome -> Tool}}.
  `:events` tem mais de 1 elemento em modo streaming (todos `:partial true`
  exceto o último). `:tools` é o `:tools` do LlmRequest DEPOIS dos request
  processors — inclui tanto `agent.tools` quanto tools sintéticas injetadas
  por um processor (ex: transfer_to_agent do AutoFlow) — é o conjunto
  correto a usar para executar as function-calls da resposta."
  [{:keys [request-processors response-processors]} agent llm ctx]
  (let [stream? (= :sse (get-in ctx [:run-config :streaming-mode]))
        request (apply-request-processors request-processors (base-request agent llm) ctx)
        responses (call-model agent request ctx llm stream?)
        processed (->> responses
                       (map #(apply-response-processors response-processors % ctx))
                       (map #(run-after-model agent % ctx)))]
    {:events (mapv #(response->event % ctx (:name agent)) processed)
     :tools (:tools request)}))

(defn- tool-exec-config [agent tools-by-name]
  {:tools-by-name tools-by-name
   :before-tool-callbacks (:before-tool-callbacks agent)
   :after-tool-callbacks (:after-tool-callbacks agent)
   :on-tool-error-callbacks (:on-tool-error-callbacks agent)})

(defn- finalize-events
  "Se `output-key` estiver presente, anexa {output-key texto} ao state-delta
  do Event verdadeiramente final de `step-events` — espelha
  LlmAgent.outputKey do adk-java/adk-python."
  [step-events output-key]
  (if (and output-key (seq step-events))
    (let [last-ev (peek step-events)]
      (if (events/final-response? last-ev)
        (conj (pop step-events)
              (update-in last-ev [:actions :state-delta] assoc output-key (events/text last-ev)))
        step-events))
    step-events))

(defn run-flow
  "Executa o loop completo de `flow` para `agent` sob `ctx` e devolve uma
  seq preguiçosa de Event.

  Cada Event é anexado ao atom de sessão vivo da invocação (`adk.context/
  append-event!`) IMEDIATAMENTE ao ser produzido — antes do passo seguinte
  do loop ser sequer avaliado — para que o processor `contents` do próximo
  passo (ex: depois de uma function-call) já veja o histórico atualizado."
  [flow agent ctx]
  (let [llm (models/resolve-model (:model agent))
        output-key (:output-key agent)]
    (letfn [(step []
              (lazy-seq
                (let [{raw-events :events tools-by-name :tools} (single-step flow agent llm ctx)
                      tools-by-name (or tools-by-name {})
                      last-ev (peek raw-events)]
                  (if (and last-ev (not (:partial last-ev)) (events/pending-function-call? last-ev))
                    (do
                      (run! #(context/append-event! ctx %) raw-events)
                      (let [tool-events (tools/run-tool-calls (tool-exec-config agent tools-by-name) last-ev ctx)]
                        (run! #(context/append-event! ctx %) tool-events)
                        (lazy-cat raw-events tool-events (step))))
                    (let [final-events (finalize-events raw-events output-key)]
                      (run! #(context/append-event! ctx %) final-events)
                      final-events)))))]
      (step))))
