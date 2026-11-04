(ns adk.tools.openapi-test
  "Testes de `adk.tools.openapi` contra um servidor HTTP REAL rodando em
  localhost (via `com.sun.net.httpserver.HttpServer`, puro JDK — sem rede
  externa, sem dependência nova) — exercita o round-trip completo
  (path/query/header/body params, auth, parse de resposta JSON) de
  verdade, não só a lógica de montagem de URL/schema isoladamente."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.data.json :as json]
            [adk.tools :as tools]
            [adk.tools.openapi :as openapi])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress]))

(defn- read-body [^HttpExchange ex]
  (slurp (.getRequestBody ex)))

(defn- send-json! [^HttpExchange ex status body]
  (let [bytes (.getBytes (json/write-str body) "UTF-8")]
    (.add (.getResponseHeaders ex) "Content-Type" "application/json")
    (.sendResponseHeaders ex status (count bytes))
    (doto (.getResponseBody ex) (.write bytes) (.close))))

(defn- handler [f]
  (reify HttpHandler
    (handle [_ ex] (f ex))))

(defn- start-fake-server! []
  (let [server (HttpServer/create (InetSocketAddress. "localhost" 0) 0)]
    (.createContext server "/pets"
      (handler
        (fn [^HttpExchange ex]
          (let [method (.getRequestMethod ex)
                path (.getPath (.getRequestURI ex))
                query (.getQuery (.getRequestURI ex))]
            (cond
              (and (= "GET" method) (re-matches #"/pets/\d+" path))
              (send-json! ex 200 {:id (Long/parseLong (last (clojure.string/split path #"/")))
                                   :name "Rex"})

              (and (= "GET" method) (= "/pets" path))
              (send-json! ex 200 {:items [] :limit (some-> query (clojure.string/replace #".*limit=(\d+).*" "$1"))})

              (and (= "POST" method) (= "/pets" path))
              (let [body (json/read-str (read-body ex) :key-fn keyword)]
                (send-json! ex 201 (assoc body :id 42)))

              :else (send-json! ex 404 {:error "not found"}))))))
    (.createContext server "/secure"
      (handler
        (fn [^HttpExchange ex]
          (let [auth-header (.getFirst (.getRequestHeaders ex) "Authorization")]
            (if (= "Bearer secret-token" auth-header)
              (send-json! ex 200 {:ok true})
              (send-json! ex 401 {:error "unauthorized"}))))))
    (.setExecutor server nil)
    (.start server)
    server))

(def ^:dynamic *server* nil)
(def ^:dynamic *base-url* nil)

(use-fixtures :each
  (fn [f]
    (let [server (start-fake-server!)
          port (.getPort (.getAddress server))]
      (binding [*server* server *base-url* (str "http://localhost:" port)]
        (try (f) (finally (.stop server 0)))))))

(def spec
  {"openapi" "3.0.0"
   "info" {"title" "Fake Pets API" "version" "1.0.0"}
   "paths"
   {"/pets/{petId}"
    {"get" {"operationId" "getPetById"
            "summary" "Get a pet by id"
            "parameters" [{"name" "petId" "in" "path" "required" true "schema" {"type" "integer"}}]
            "responses" {"200" {"description" "ok"}}}}
    "/pets"
    {"get" {"operationId" "listPets"
            "summary" "List pets"
            "parameters" [{"name" "limit" "in" "query" "required" false "schema" {"type" "integer"}}]
            "responses" {"200" {"description" "ok"}}}
     "post" {"operationId" "createPet"
             "summary" "Create a pet"
             "requestBody" {"required" true
                            "content" {"application/json" {"schema" {"$ref" "#/components/schemas/NewPet"}}}}
             "responses" {"201" {"description" "created"}}}}
    "/secure"
    {"get" {"operationId" "getSecure"
            "summary" "A protected endpoint"
            "responses" {"200" {"description" "ok"} "401" {"description" "unauthorized"}}}}}
   "components"
   {"schemas"
    {"NewPet" {"type" "object"
               "properties" {"name" {"type" "string" "description" "Pet's name"}
                              "tag" {"type" "string"}}
               "required" ["name"]}}}})

(defn- find-tool [tools-vec op-name]
  (first (filter #(= op-name (tools/tool-name %)) tools-vec)))

(deftest generates-one-tool-per-operation
  (let [generated (openapi/openapi-tools spec {:base-url *base-url*})]
    (is (= 4 (count generated)))
    (is (= #{"get_pet_by_id" "list_pets" "create_pet" "get_secure"}
           (set (map tools/tool-name generated))))))

(deftest get-with-path-param-round-trips-against-real-server
  (let [tool (find-tool (openapi/openapi-tools spec {:base-url *base-url*}) "get_pet_by_id")
        result (tools/invoke-tool tool {"petId" 7} nil)]
    (is (= 200 (:status result)))
    (is (= {:id 7 :name "Rex"} (:body result)))))

(deftest get-with-query-param-round-trips
  (let [tool (find-tool (openapi/openapi-tools spec {:base-url *base-url*}) "list_pets")
        result (tools/invoke-tool tool {"limit" 5} nil)]
    (is (= 200 (:status result)))
    (is (= "5" (:limit (:body result))))))

(deftest post-with-ref-resolved-body-schema-round-trips
  (let [tools-vec (openapi/openapi-tools spec {:base-url *base-url*})
        tool (find-tool tools-vec "create_pet")]
    (testing "o $ref do requestBody foi resolvido e as propriedades viraram parâmetros top-level"
      (is (= #{:name :tag} (set (keys (:properties (:parameters (tools/tool-declaration tool)))))))
      (is (= ["name"] (:required (:parameters (tools/tool-declaration tool))))))
    (let [result (tools/invoke-tool tool {"name" "Fido" "tag" "dog"} nil)]
      (is (= 201 (:status result)))
      (is (= {:name "Fido" :tag "dog" :id 42} (:body result))))))

(deftest bearer-auth-is-applied-to-every-request
  (let [tool (find-tool (openapi/openapi-tools spec {:base-url *base-url*
                                                       :auth {:type :bearer :token "secret-token"}})
                         "get_secure")
        result (tools/invoke-tool tool {} nil)]
    (is (= 200 (:status result)))
    (is (= {:ok true} (:body result)))))

(deftest missing-or-wrong-auth-gets-rejected-by-the-real-server
  (let [tool-no-auth (find-tool (openapi/openapi-tools spec {:base-url *base-url*}) "get_secure")
        tool-wrong-auth (find-tool (openapi/openapi-tools spec {:base-url *base-url*
                                                                  :auth {:type :bearer :token "nope"}})
                                    "get_secure")]
    (is (= 401 (:status (tools/invoke-tool tool-no-auth {} nil))))
    (is (= 401 (:status (tools/invoke-tool tool-wrong-auth {} nil))))))

(deftest requires-base-url-or-servers
  (is (thrown? clojure.lang.ExceptionInfo (openapi/openapi-tools (dissoc spec "servers") {}))))
