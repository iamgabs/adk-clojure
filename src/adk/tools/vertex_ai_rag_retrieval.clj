(ns adk.tools.vertex-ai-rag-retrieval
  "VertexAiRagRetrieval — grounding nativo do Gemini contra um ou mais
  corpora do Vertex AI RAG Engine. Espelha
  `memory/VertexAiRagMemoryService.java`-adjacent /
  `tools/retrieval/vertex_ai_rag_retrieval.py` (adk-python). Mesma
  natureza das outras tools deste namespace-família: resolvida pelo Gemini
  do lado do servidor, sem function-calling local.

  NOTA: isto é especificamente a retrieval contra o Vertex AI RAG Engine
  (configuração por nome de corpus) — retrieval local/customizado (ex:
  indexar arquivos locais, LlamaIndex) continua fora do escopo da v0.1."
  (:require [adk.tools :as tools]))

(defrecord VertexAiRagRetrievalTool [config]
  tools/Tool
  (tool-name [_] "vertex_ai_rag_retrieval")
  (tool-description [_] "Busca em corpora do Vertex AI RAG Engine (grounding nativo do Gemini).")
  (tool-declaration [_] nil)
  (long-running? [_] false)
  (invoke-tool [_ _args _tool-context]
    (throw (ex-info "vertex_ai_rag_retrieval é executada pelo próprio Gemini (grounding nativo); nunca deveria ser invocada via function-calling."
                     {:type ::not-invokable})))

  tools/GroundingTool
  (grounding-spec [_] {:vertex-ai-rag-retrieval config}))

(defn vertex-ai-rag-retrieval-tool
  "opts: {:rag-corpora [\"projects/.../ragCorpora/...\" ...]} obrigatório;
  opcionalmente :similarity-top-k e :vector-distance-threshold."
  [{:keys [rag-corpora similarity-top-k vector-distance-threshold] :as opts}]
  (when (empty? rag-corpora)
    (throw (ex-info "vertex-ai-rag-retrieval-tool exige :rag-corpora (pelo menos um)" {:opts opts})))
  (->VertexAiRagRetrievalTool (cond-> {:rag-corpora (vec rag-corpora)}
                                similarity-top-k (assoc :similarity-top-k similarity-top-k)
                                vector-distance-threshold (assoc :vector-distance-threshold vector-distance-threshold))))
