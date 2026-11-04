(ns adk.genai.content
  "Única fronteira de interop para Content/Part (os tipos multimodais do
  SDK `com.google.genai`). O resto do core do adk-clojure só conhece os
  maps simples definidos aqui:

    {:role \"user\"|\"model\"
     :parts [{:text \"...\"}
             {:function-call {:name \"...\" :args {...}}}
             {:function-response {:name \"...\" :response {...}}}
             {:inline-data {:mime-type \"...\" :data <bytes>}}]}

  Nenhum outro namespace do core deve importar `com.google.genai.types.*`
  diretamente — só `adk.genai.schema` e `adk.models.gemini`."
  (:import [com.google.genai.types Content Part FunctionResponsePart]))

(defn- opt
  "Desembrulha um java.util.Optional, ou devolve nil."
  [^java.util.Optional o]
  (when (and o (.isPresent o)) (.get o)))

(defn part->map
  [^Part p]
  (cond
    (opt (.text p)) {:text (opt (.text p))}

    (opt (.functionCall p))
    (let [fc (opt (.functionCall p))]
      {:function-call {:name (opt (.name fc))
                        :args (into {} (or (opt (.args fc)) {}))}})

    (opt (.functionResponse p))
    (let [fr (opt (.functionResponse p))]
      {:function-response {:name (opt (.name fr))
                            :response (into {} (or (opt (.response fr)) {}))}})

    (opt (.inlineData p))
    (let [blob (opt (.inlineData p))]
      {:inline-data {:mime-type (opt (.mimeType blob))
                      :data (opt (.data blob))}})

    :else {}))

(defn map->part
  ^Part [{:keys [text function-call function-response inline-data]}]
  (cond
    text (Part/fromText text)
    function-call (Part/fromFunctionCall (:name function-call) (or (:args function-call) {}))
    function-response (Part/fromFunctionResponse (:name function-response)
                                                  (or (:response function-response) {})
                                                  (make-array FunctionResponsePart 0))
    inline-data (Part/fromBytes (:data inline-data) (:mime-type inline-data))
    :else (.build (Part/builder))))

(defn content->map
  [^Content c]
  {:role (opt (.role c))
   :parts (mapv part->map (or (opt (.parts c)) []))})

(defn map->content
  ^Content [{:keys [role parts]}]
  (let [b (Content/builder)
        b (if role (.role b ^String role) b)
        b (if (seq parts) (.parts b ^java.util.List (mapv map->part parts)) b)]
    (.build b)))
