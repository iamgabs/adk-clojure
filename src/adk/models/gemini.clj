(ns adk.models.gemini
  "Implementação de `adk.models/Llm` sobre o SDK oficial
  `com.google.genai:google-genai`. É o único namespace, junto com
  `adk.genai.*`, que conhece tipos Java do SDK — todo o resto do core
  trabalha só com os maps definidos em `adk.models`/`adk.genai.content`.

  NOTA v0.1: a conversão de `:config` (temperature/max-output-tokens/
  response-modalities) e de tools (via `adk.genai.schema`) cobre o
  suficiente para uso comum; estender conforme necessário (safety settings,
  thinking config, etc. ainda não são mapeados)."
  (:require [clojure.string :as str]
            [adk.env :as env]
            [adk.models :as models]
            [adk.genai.content :as content]
            [adk.genai.schema :as schema]
            [adk.tools :as tools]
            [adk.concurrency :as concurrency])
  (:import [com.google.genai Client]
           [com.google.genai.types GenerateContentConfig Tool GenerateContentResponse]))

(defn- opt [^java.util.Optional o] (when (and o (.isPresent o)) (.get o)))

(defn- env-api-key []
  (or (env/getenv "GOOGLE_API_KEY") (env/getenv "GEMINI_API_KEY")))

(defn- env-vertex-ai? []
  (= "true" (some-> (env/getenv "GOOGLE_GENAI_USE_VERTEXAI") str/lower-case)))

(defn make-client
  "`api-key`/`vertex-ai?` não dados explicitamente caem de volta para
  `adk.env/getenv` (que consulta um `.env` carregado via `adk.env/load-env!`
  antes da variável de ambiente real do processo) — assim um `.env` com
  GOOGLE_API_KEY funciona tanto para uso direto de `gemini-llm` quanto para
  o caminho default `(llm-agent {:model \"gemini-2.0-flash\"})`, sem precisar
  de `export` manual no shell."
  ^Client [{:keys [api-key vertex-ai? project location]}]
  (let [api-key (or api-key (env-api-key))
        vertex-ai? (if (some? vertex-ai?) vertex-ai? (env-vertex-ai?))
        b (Client/builder)
        b (if api-key (.apiKey b ^String api-key) b)
        b (if vertex-ai? (.vertexAI b true) b)
        b (if project (.project b ^String project) b)
        b (if location (.location b ^String location) b)]
    (.build b)))

(defn- request->config
  ^GenerateContentConfig [{:keys [system-instruction config tools]}]
  (let [{:keys [temperature max-output-tokens response-modalities]} config
        b (GenerateContentConfig/builder)
        b (if system-instruction
            (.systemInstruction b (content/map->content {:role "user" :parts [{:text system-instruction}]}))
            b)
        b (if temperature (.temperature b (float temperature)) b)
        b (if max-output-tokens (.maxOutputTokens b (int max-output-tokens)) b)
        b (if (seq response-modalities) (.responseModalities b ^java.util.List (vec response-modalities)) b)
        b (if (seq tools)
            (.tools b ^java.util.List
                    [(-> (Tool/builder)
                         (.functionDeclarations
                           ^java.util.List
                           (mapv (fn [[_ t]] (schema/declaration->function-declaration (tools/tool-declaration t)))
                                 tools))
                         .build)])
            b)]
    (.build b)))

(defn- finish-reason->kw [fr]
  (when fr (keyword (str/lower-case (str (.knownEnum fr))))))

(defn- usage-metadata->map [um]
  (when um
    {:prompt-token-count (opt (.promptTokenCount um))
     :candidates-token-count (opt (.candidatesTokenCount um))
     :total-token-count (opt (.totalTokenCount um))}))

(defn- response->llm-response
  "Converte uma GenerateContentResponse (completa ou um chunk de streaming)
  para o nosso LlmResponse. `partial?` é decidido pelo chamador, já que o
  SDK não marca isso explicitamente por chunk."
  [^GenerateContentResponse resp partial?]
  (let [candidates (or (opt (.candidates resp)) [])
        first-candidate (first candidates)]
    (models/llm-response
      {:content (when first-candidate (some-> (opt (.content first-candidate)) content/content->map))
       :finish-reason (when first-candidate (finish-reason->kw (opt (.finishReason first-candidate))))
       :usage-metadata (usage-metadata->map (opt (.usageMetadata resp)))
       :partial partial?
       :turn-complete (not partial?)})))

(defn- merge-text-responses
  "Agrega o texto de uma seq de LlmResponse parciais numa resposta final
  única — aproximação simples o bastante para uso comum; NÃO faz merge de
  argumentos de function-call fragmentados entre chunks (ver riscos no
  plano de implementação: streaming + function-calling fragmentado é uma
  limitação conhecida da v0.1)."
  [responses]
  (let [texts (keep #(some->> % :content :parts (keep :text) seq (apply str)) responses)
        last-resp (last responses)]
    (-> last-resp
        (assoc :partial false :turn-complete true)
        (assoc :content (when (seq texts) {:role "model" :parts [{:text (apply str texts)}]})))))

(defrecord GeminiLlm [client model]
  models/Llm
  (model-name [_] model)
  (generate-content [_ llm-request stream?]
    (let [contents (mapv content/map->content (:contents llm-request))
          cfg (request->config llm-request)]
      (if stream?
        (concurrency/thread-seq
          (fn [put!]
            (let [chunks (atom [])]
              (with-open [stream (.generateContentStream (.models client) model contents cfg)]
                (doseq [chunk stream]
                  (let [r (response->llm-response chunk true)]
                    (swap! chunks conj r)
                    (put! r))))
              (put! (merge-text-responses @chunks)))))
        [(response->llm-response (.generateContent (.models client) model contents cfg) false)]))))

(defn gemini-llm
  "Constrói uma GeminiLlm. `client-opts`: {:api-key? :vertex-ai? :project?
  :location?} — veja `make-client`. Sem `client-opts`, o SDK usa suas
  próprias convenções de default (variáveis de ambiente GOOGLE_API_KEY /
  GOOGLE_GENAI_USE_VERTEXAI etc)."
  [{:keys [model client-opts]}]
  (->GeminiLlm (make-client (or client-opts {})) model))

(models/register-llm! #"^gemini-.*" (fn [name] (gemini-llm {:model name})))
(models/register-llm! #"^gemma-.*" (fn [name] (gemini-llm {:model name})))
