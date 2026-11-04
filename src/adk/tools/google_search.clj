(ns adk.tools.google-search
  "GoogleSearchTool — grounding nativo do Gemini com a Busca do Google.
  Espelha `tools/GoogleSearchTool.java` (adk-java) /
  `tools/google_search_tool.py` (adk-python): diferente de uma tool comum,
  ela não é chamada por nós via function-calling — só pede pro próprio
  Gemini ativar a busca do lado do servidor, então `invoke-tool` nunca é
  de fato usado (a execução acontece inteiramente na API do Google).

  Uso:
    (llm-agent/llm-agent {:name \"agent\" :model \"gemini-2.0-flash\"
                           :tools [google-search-tool]})"
  (:require [adk.tools :as tools]))

(defrecord GoogleSearchTool []
  tools/Tool
  (tool-name [_] "google_search")
  (tool-description [_] "Busca na web com a Busca do Google (grounding nativo do Gemini).")
  (tool-declaration [_] nil)
  (long-running? [_] false)
  (invoke-tool [_ _args _tool-context]
    (throw (ex-info "google_search é executada pelo próprio Gemini (grounding nativo); nunca deveria ser invocada via function-calling."
                     {:type ::not-invokable})))

  tools/GroundingTool
  (grounding-spec [_] {:google-search {}}))

(def google-search-tool (->GoogleSearchTool))
