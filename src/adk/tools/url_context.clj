(ns adk.tools.url-context
  "UrlContextTool — grounding nativo do Gemini para buscar e ler o
  conteúdo de URLs mencionadas na conversa. Espelha
  `tools/UrlContextTool.java` (adk-java) / `tools/url_context_tool.py`
  (adk-python). Mesma natureza do `adk.tools.google-search`: resolvida
  inteiramente pelo Gemini do lado do servidor, não por function-calling."
  (:require [adk.tools :as tools]))

(defrecord UrlContextTool []
  tools/Tool
  (tool-name [_] "url_context")
  (tool-description [_] "Busca e lê o conteúdo de URLs mencionadas na conversa (grounding nativo do Gemini).")
  (tool-declaration [_] nil)
  (long-running? [_] false)
  (invoke-tool [_ _args _tool-context]
    (throw (ex-info "url_context é executada pelo próprio Gemini (grounding nativo); nunca deveria ser invocada via function-calling."
                     {:type ::not-invokable})))

  tools/GroundingTool
  (grounding-spec [_] {:url-context {}}))

(def url-context-tool (->UrlContextTool))
