(ns adk.genai.schema
  "Conversão do JSON Schema simplificado usado por `adk.tools/tool-declaration`
  (ver docstring de `adk.tools`) para `com.google.genai.types.Schema` /
  `FunctionDeclaration` — a outra metade da fronteira de interop (junto com
  `adk.genai.content`)."
  (:require [clojure.string :as str])
  (:import [com.google.genai.types Schema FunctionDeclaration]))

(defn- schema-type-string
  "O SDK aceita o nome do tipo como String diretamente (\"OBJECT\",
  \"STRING\", etc). Aceita tanto string quanto keyword de entrada, em
  qualquer caixa."
  [t]
  (str/upper-case (name t)))

(defn map->schema
  "Converte um JSON Schema simplificado (como o produzido por
  `malli.json-schema/transform`, usado em `adk.tools.function-tool`) para
  `Schema`. Suporta object/properties/required, tipos primitivos, array/
  items e enum — o bastante para declarar parâmetros de tool; não cobre o
  JSON Schema inteiro (ex: oneOf/anyOf, $ref)."
  ^Schema [m]
  (if (nil? m)
    (.build (Schema/builder))
    (let [{:keys [type description properties required items enum]} m
          b (Schema/builder)
          b (if type (.type b (schema-type-string type)) b)
          b (if description (.description b ^String description) b)
          b (if (seq properties)
              (.properties b ^java.util.Map
                           (into {} (map (fn [[k v]] [(name k) (map->schema v)])) properties))
              b)
          b (if (seq required) (.required b ^java.util.List (mapv name required)) b)
          b (if items (.items b (map->schema items)) b)
          b (if (seq enum) (.enum_ b ^java.util.List (mapv str enum)) b)]
      (.build b))))

(defn declaration->function-declaration
  "`decl`: {:name :description :parameters <json-schema>} — o shape devolvido
  por `adk.tools/tool-declaration`."
  ^FunctionDeclaration [{:keys [name description parameters]}]
  (let [b (FunctionDeclaration/builder)
        b (.name b ^String name)
        b (if description (.description b ^String description) b)
        b (if parameters (.parameters b (map->schema parameters)) b)]
    (.build b)))
