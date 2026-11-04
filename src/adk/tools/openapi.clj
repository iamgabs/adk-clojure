(ns adk.tools.openapi
  "OpenAPI toolset — espelha `tools/openapi_tool/...` (adk-python) /
  `OpenApiSpecParser`+`RestApiTool` (adk-java): parseia uma spec OpenAPI
  3.x e gera uma `adk.tools/Tool` por operação (path+método), que ao ser
  invocada faz uma chamada HTTP de verdade pro endpoint descrito.

  DEPENDÊNCIA OPCIONAL (não entra em `:deps` do adk-clojure): adicione ao
  seu deps.edn:
    org.clojure/data.json {:mvn/version \"2.5.1\"}
  (o cliente HTTP em si é `java.net.http.HttpClient`, já embutido na JDK —
  nenhuma lib de HTTP extra é necessária, só o parser/encoder JSON.)

  Uso:
    (require '[adk.tools.openapi :as openapi])
    (def pet-tools (openapi/openapi-tools spec {:base-url \"https://api.example.com\"
                                                 :auth {:type :bearer :token \"...\"}}))
    (llm-agent/llm-agent {:name \"a\" :model \"gemini-2.0-flash\" :tools pet-tools})

  `spec` é a spec OpenAPI já parseada como um map Clojure (chaves string,
  o jeito natural de um `(json/read-str json-str)`) — este namespace não
  faz parsing de YAML; se sua spec é YAML, parseie com a lib de sua
  escolha antes de chamar `openapi-tools`.

  ESCOPO v0.1 (limitações conhecidas):
  - Só corpos de requisição/resposta JSON (`application/json`) — sem
    multipart/form-data.
  - Só um servidor (`spec[\"servers\"][0][\"url\"]`, ou `:base-url`
    explícito nas opts) — sem variáveis de servidor (`{var}` em server
    URLs).
  - Resolução de `$ref` só LOCAL (`#/components/...`, dentro da mesma
    spec) e com profundidade limitada (10 níveis) — sem `$ref` pra outro
    arquivo/URL, e schemas muito recursivos ficam truncados além dessa
    profundidade.
  - Auth: só API-key (header ou query, estática) ou Bearer token
    estático — sem fluxo OAuth2/OIDC completo."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [clojure.data.json :as json]
            [adk.tools :as tools])
  (:import [java.net URI URLEncoder]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]))

;; ---------------------------------------------------------------------------
;; Resolução de $ref local + extração de parâmetros/corpo de uma operação
;; ---------------------------------------------------------------------------

(defn- ref-segments [ref-str]
  (-> ref-str (str/replace #"^#/" "") (str/split #"/")))

(defn- resolve-schema
  "Resolve `$ref` locais recursivamente (em si mesmo, em `properties`, e em
  `items`), com `depth` níveis de profundidade — evita loop infinito em
  schemas recursivos (ex: um nó de árvore que referencia a si mesmo)."
  [spec schema depth]
  (cond
    (<= depth 0) (or schema {})
    (not (map? schema)) schema
    (contains? schema "$ref")
    (recur spec (get-in spec (ref-segments (get schema "$ref"))) (dec depth))
    :else
    (cond-> schema
      (map? (get schema "properties"))
      (update "properties"
              (fn [props] (into {} (map (fn [[k v]] [k (resolve-schema spec v (dec depth))])) props)))
      (some? (get schema "items"))
      (update "items" #(resolve-schema spec % (dec depth))))))

(defn- resolve-param [spec param]
  (if (contains? param "$ref")
    (resolve-param spec (get-in spec (ref-segments (get param "$ref"))))
    param))

(defn- collect-params
  "-> seq de {:name :in :required :schema}, juntando parâmetros do
  path-item com os da operação (a operação tem precedência em caso de
  mesmo nome+in, igual ao OpenAPI especifica)."
  [spec path-item operation]
  (let [resolved (->> (concat (get path-item "parameters" []) (get operation "parameters" []))
                       (map #(resolve-param spec %)))
        by-key (into {} (map (fn [p] [[(get p "name") (get p "in")] p])) resolved)]
    (for [p (vals by-key)]
      {:name (get p "name")
       :in (get p "in")
       :required (boolean (get p "required"))
       :schema (resolve-schema spec (get p "schema" {"type" "string"}) 10)})))

(defn- body-fields
  "-> {:fields [{:name :required :schema}] :whole? bool}. `:whole? true`
  quando o corpo não é um objeto JSON (schema de array/escalar) — nesse
  caso vira um único campo sintético \"body\"."
  [spec operation]
  (when-let [raw-schema (get-in operation ["requestBody" "content" "application/json" "schema"])]
    (let [schema (resolve-schema spec raw-schema 10)]
      (if (= "object" (get schema "type"))
        (let [required (set (get schema "required" []))]
          {:fields (for [[k v] (get schema "properties" {})]
                     {:name k :required (contains? required k) :schema v})
           :whole? false})
        {:fields [{:name "body"
                   :required (boolean (get-in operation ["requestBody" "required"]))
                   :schema schema}]
         :whole? true}))))

(defn- field-specs
  "-> seq de {:name :in :required :schema}, com :in um de
  :path/:query/:header/:cookie/:body/:body-whole."
  [spec path-item operation]
  (let [params (map #(update % :in keyword) (collect-params spec path-item operation))
        {:keys [fields whole?]} (body-fields spec operation)]
    (concat params (map #(assoc % :in (if whole? :body-whole :body)) fields))))

(defn- build-schema-and-routing
  "-> [json-schema routing], onde routing é {nome-do-campo (keyword) ->
  :in} — usado por `invoke-tool` pra saber pra onde mandar cada argumento."
  [fields]
  (loop [fs fields props {} required-names [] routing {}]
    (if-let [{:keys [name required schema in]} (first fs)]
      (recur (rest fs)
             (assoc props (keyword name) (walk/keywordize-keys schema))
             (if required (conj required-names name) required-names)
             (assoc routing (keyword name) in))
      [{:type "object" :properties props :required (vec required-names)} routing])))

;; ---------------------------------------------------------------------------
;; Construção da URL / requisição HTTP
;; ---------------------------------------------------------------------------

(defn- url-encode [s] (URLEncoder/encode (str s) "UTF-8"))

(defn- substitute-path [path path-params]
  (reduce (fn [p [k v]] (str/replace p (str "{" k "}") (url-encode v))) path path-params))

(defn- build-url [base-url path path-params query-params]
  (let [base (str/replace base-url #"/$" "")
        full-path (substitute-path path path-params)
        query (when (seq query-params)
                (str "?" (str/join "&" (for [[k v] query-params] (str (url-encode k) "=" (url-encode v))))))]
    (str base full-path query)))

(defn- apply-auth [{:keys [headers query]} auth]
  (case (:type auth)
    :bearer [(assoc headers "Authorization" (str "Bearer " (:token auth))) query]
    :api-key (if (= :query (:in auth))
               [headers (assoc query (:name auth) (:value auth))]
               [(assoc headers (:name auth) (:value auth)) query])
    nil [headers query]
    [headers query]))

(def ^:private shared-http-client (delay (HttpClient/newHttpClient)))

(defn- send-request!
  [{:keys [method url headers body]}]
  (let [has-body? (some? body)
        body-publisher (if has-body?
                          (HttpRequest$BodyPublishers/ofString body)
                          (HttpRequest$BodyPublishers/noBody))
        headers (cond-> headers has-body? (assoc "Content-Type" "application/json"))
        b (-> (HttpRequest/newBuilder (URI/create url))
              (.method method body-publisher))
        b (reduce (fn [acc [k v]] (.header acc (str k) (str v))) b headers)
        request (.build b)
        response (.send ^HttpClient @shared-http-client request (HttpResponse$BodyHandlers/ofString))
        response-body (.body response)]
    {:status (.statusCode response)
     :body (try (json/read-str response-body :key-fn keyword)
                (catch Exception _ response-body))}))

;; ---------------------------------------------------------------------------
;; A Tool em si
;; ---------------------------------------------------------------------------

(defn- arg-val
  [args field-kw]
  (let [k (name field-kw)]
    (if-let [e (find args k)] (val e) (get args field-kw))))

(defrecord OpenApiTool [tool-name-str description json-schema base-url auth http-method path routing]
  tools/Tool
  (tool-name [_] tool-name-str)
  (tool-description [_] description)
  (tool-declaration [_] {:name tool-name-str :description description :parameters json-schema})
  (long-running? [_] false)
  (invoke-tool [_ args _tool-context]
    (let [path-params (atom {}) query-params (atom {}) headers (atom {})
          body-fields (atom {}) whole-body (atom nil)]
      (doseq [[field-kw in] routing
              :let [v (arg-val args field-kw)]
              :when (some? v)]
        (case in
          :path (swap! path-params assoc (name field-kw) v)
          :query (swap! query-params assoc (name field-kw) v)
          :header (swap! headers assoc (name field-kw) v)
          :cookie (swap! headers update "Cookie" #(str (when % (str % "; ")) (name field-kw) "=" v))
          :body (swap! body-fields assoc (name field-kw) v)
          :body-whole (reset! whole-body v)
          nil))
      (let [[headers* query*] (apply-auth {:headers @headers :query @query-params} auth)
            url (build-url base-url path @path-params query*)
            body-payload (if (some? @whole-body) @whole-body @body-fields)
            body-json (when (or (seq @body-fields) (some? @whole-body)) (json/write-str body-payload))]
        (send-request! {:method http-method :url url :headers headers* :body body-json})))))

(defn- default-operation-id [method path]
  (str (str/lower-case method) "_"
       (-> path (str/replace #"[{}]" "") (str/replace #"[^a-zA-Z0-9]+" "_") (str/replace #"^_|_$" ""))))

(defn- sanitize-tool-name [s]
  (-> s
      (str/replace #"([a-z0-9])([A-Z])" "$1_$2")
      str/lower-case
      (str/replace #"[^a-z0-9]+" "_")
      (str/replace #"^_|_$" "")))

(def ^:private http-methods #{"get" "put" "post" "delete" "options" "head" "patch" "trace"})

(defn- build-operation-tool [spec base-url auth path method operation]
  (let [path-item (get-in spec ["paths" path])
        fields (field-specs spec path-item operation)
        [json-schema routing] (build-schema-and-routing fields)
        op-id (or (get operation "operationId") (default-operation-id method path))
        description (or (get operation "summary") (get operation "description") "")]
    (->OpenApiTool (sanitize-tool-name op-id) description json-schema base-url auth
                    (str/upper-case method) path routing)))

(defn openapi-tools
  "Gera uma `adk.tools/Tool` por operação da spec OpenAPI `spec` (map
  Clojure, chaves string).

  opts:
    :base-url — obrigatório se a spec não tiver `servers`, opcional
                (sobrescreve) caso contrário. Default: `(get-in spec
                [\"servers\" 0 \"url\"])`.
    :auth     — nil (default, sem auth), ou
                {:type :bearer :token \"...\"}, ou
                {:type :api-key :in :header|:query :name \"X-Api-Key\" :value \"...\"}.

  Devolve um vetor de Tool — passe direto em `:tools` de um LlmAgent."
  [spec {:keys [base-url auth]}]
  (let [base-url (or base-url (get-in spec ["servers" 0 "url"]))]
    (when-not base-url
      (throw (ex-info "openapi-tools: spec sem \"servers\" e nenhum :base-url informado" {})))
    (vec
      (for [[path path-item] (get spec "paths" {})
            [method operation] path-item
            :when (contains? http-methods method)]
        (build-operation-tool spec base-url auth path method operation)))))
