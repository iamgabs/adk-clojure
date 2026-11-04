(ns adk.models
  "Protocolo Llm + registry de resolução de nome-de-modelo -> implementação,
  espelhando `models/BaseLlm.java`+`LlmRegistry.java` (adk-java) e
  `models/base_llm.py`+`registry.py` (adk-python).

  LlmRequest: {:model :contents [Content...] :system-instruction :config {...}
               :tools {nome -> Tool} :grounding-tools [{:google-search {}} ...]}
  LlmResponse: {:content Content :finish-reason :usage-metadata {...}
                :partial bool :turn-complete bool :error-code :error-message}

  `:grounding-tools` são specs genéricas de tools \"built-in\" que não
  passam por function-calling (ver `adk.tools/GroundingTool`) — cada
  implementação de Llm interpreta as chaves que conhece (ex:
  `adk.models.gemini` sabe converter `:google-search`).")

(defprotocol Llm
  (model-name [llm])
  (generate-content [llm llm-request stream?]
    "-> seq de LlmResponse. Não-streaming emite exatamente 1 resposta final
    (:partial false); streaming emite N respostas parciais (:partial true)
    seguidas de uma final agregada (:partial false)."))

(defn llm-request
  [{:keys [model contents system-instruction config tools grounding-tools]
    :or {contents [] config {} tools {} grounding-tools []}}]
  {:model model
   :contents contents
   :system-instruction system-instruction
   :config config
   :tools tools
   :grounding-tools grounding-tools})

(defn llm-response
  [{:keys [content finish-reason usage-metadata partial turn-complete
           error-code error-message]
    :or {partial false turn-complete true}}]
  {:content content
   :finish-reason finish-reason
   :usage-metadata usage-metadata
   :partial partial
   :turn-complete turn-complete
   :error-code error-code
   :error-message error-message})

;; ---------------------------------------------------------------------------
;; Registry: resolve uma string de nome de modelo (ex: "gemini-2.0-flash")
;; para uma instância de Llm, por casamento de regex — igual ao LlmRegistry
;; do adk-java (primeiro padrão que casa, na ordem de registro, vence).
;; ---------------------------------------------------------------------------

(defonce ^:private registry (atom []))

(defn register-llm!
  "Registra uma factory `(fn [model-name] -> Llm)` para nomes de modelo que
  casem com `pattern` (regex)."
  [pattern factory-fn]
  (swap! registry conj [pattern factory-fn]))

(defn resolve-model
  "Resolve `model-ref` (uma instância de Llm já construída, ou uma string de
  nome de modelo) para uma implementação de Llm."
  [model-ref]
  (cond
    (satisfies? Llm model-ref) model-ref
    (string? model-ref)
    (if-let [[_ factory] (first (filter (fn [[re _]] (re-find re model-ref)) @registry))]
      (factory model-ref)
      (throw (ex-info "Nenhum Llm registrado para este nome de modelo"
                       {:type ::unknown-model :model model-ref})))
    :else (throw (ex-info "model inválido: precisa ser uma string ou uma implementação de Llm"
                           {:type ::invalid-model :model model-ref}))))

(defn reset-registry!
  "Limpa o registry — útil em testes para isolar registros feitos por outros
  namespaces (ex: adk.models.gemini registra \"gemini-.*\" ao carregar)."
  []
  (reset! registry []))
