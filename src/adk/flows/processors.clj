(ns adk.flows.processors
  "Request processors reutilizáveis — funções puras `(fn [request ctx] ->
  request)` combinadas por `adk.flows/apply-request-processors`. Espelham o
  pipeline de `flows/llm_flows/*` do adk-java/adk-python (versão reduzida ao
  necessário para a v0.1: instructions, contents, tools).

  LIMITAÇÃO CONHECIDA (v0.1): `instructions` só resolve instruction/
  global-instruction do agente corrente — não percorre a cadeia de agentes
  ancestrais como o ADK original (onde global-instruction do agente raiz é
  herdado por todos os descendentes). Ver riscos no plano de implementação."
  (:require [clojure.string :as str]
            [adk.context :as context]
            [adk.tools :as tools]))

(defn- resolve-instruction
  [instruction ctx]
  (cond
    (string? instruction) instruction
    (ifn? instruction) (instruction ctx)
    :else nil))

(defn instructions
  [request ctx]
  (let [agent (:agent ctx)
        combined (->> [(resolve-instruction (:global-instruction agent) ctx)
                       (resolve-instruction (:instruction agent) ctx)]
                      (remove str/blank?)
                      (str/join "\n\n"))]
    (cond-> request
      (not (str/blank? combined)) (assoc :system-instruction combined))))

(defn contents
  "Monta o histórico de Content a partir dos eventos visíveis no branch
  corrente (`adk.context/events-on-branch`), respeitando
  `:include-contents` (:all por padrão; :none zera o histórico, para
  agentes que devem responder só com base na instruction/args atuais)."
  [request ctx]
  (let [agent (:agent ctx)
        include (get agent :include-contents :all)]
    (assoc request :contents
           (if (= include :none)
             []
             (->> (context/events-on-branch ctx) (keep :content) vec)))))

(defn tools-declaration
  "Popula `:tools` do LlmRequest a partir de `agent.tools` (já resolvidas
  como implementações de `adk.tools/Tool` — nenhum wrapping automático de
  função crua acontece aqui, ver `adk.tools.function-tool`)."
  [request ctx]
  (let [agent (:agent ctx)
        tool-map (into {} (map (juxt tools/tool-name identity)) (:tools agent))]
    (update request :tools merge tool-map)))
