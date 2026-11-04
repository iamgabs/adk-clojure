(ns adk.tools.vertex-ai-search
  "VertexAiSearchTool — grounding nativo do Gemini usando um datastore ou
  engine do Vertex AI Search (Discovery Engine). Espelha
  `tools/VertexAiSearchTool.java` (adk-java) /
  `tools/vertex_ai_search_tool.py` (adk-python). Mesma natureza das outras
  tools deste namespace-família: resolvida pelo Gemini do lado do servidor.

  Diferente de google-search/url-context/code-execution, exige
  configuração — um `:datastore` ou `:engine` do Vertex AI Search (os dois
  são mutuamente exclusivos, igual ao original)."
  (:require [adk.tools :as tools]))

(defrecord VertexAiSearchTool [config]
  tools/Tool
  (tool-name [_] "vertex_ai_search")
  (tool-description [_] "Busca num datastore/engine do Vertex AI Search (grounding nativo do Gemini).")
  (tool-declaration [_] nil)
  (long-running? [_] false)
  (invoke-tool [_ _args _tool-context]
    (throw (ex-info "vertex_ai_search é executada pelo próprio Gemini (grounding nativo); nunca deveria ser invocada via function-calling."
                     {:type ::not-invokable})))

  tools/GroundingTool
  (grounding-spec [_] {:vertex-ai-search config}))

(defn vertex-ai-search-tool
  "opts: {:datastore \"projects/.../dataStores/...\"} OU
         {:engine \"projects/.../engines/...\"}
  (mutuamente exclusivos), mais opcionalmente :filter e :max-results."
  [{:keys [datastore engine filter max-results] :as opts}]
  (when-not (or datastore engine)
    (throw (ex-info "vertex-ai-search-tool exige :datastore ou :engine" {:opts opts})))
  (when (and datastore engine)
    (throw (ex-info "vertex-ai-search-tool aceita :datastore OU :engine, não os dois" {:opts opts})))
  (->VertexAiSearchTool (cond-> {}
                          datastore (assoc :datastore datastore)
                          engine (assoc :engine engine)
                          filter (assoc :filter filter)
                          max-results (assoc :max-results max-results))))
