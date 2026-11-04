(ns adk.web-test
  "Testes de `adk.web` contra um servidor Jetty REAL numa porta efêmera,
  com HTTP de verdade via `java.net.http.HttpClient` — mesmo estilo de
  `adk.tools.openapi-test` (nenhum mock de servidor/handler, só o Runner
  por trás é alimentado por `MockLlm` pra não precisar de rede externa)."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [adk.web :as web]
            [adk.runner :as runner]
            [adk.agents.llm-agent :as llm-agent]
            [adk.models :as models]
            [adk.test-util.mock-llm :as mock]
            [adk.test-util.helpers :as h])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]))

(def ^:dynamic *base-url* nil)
(def ^:dynamic *llm* nil)

(defn- build-runner []
  (let [llm (mock/mock-llm [(models/llm-response {:content (h/text-content "model" "oi, tudo bem?")})
                             (models/llm-response {:content (h/text-content "model" "de novo?")})])
        agent (llm-agent/llm-agent {:name "test-agent" :model llm})]
    [llm (runner/in-memory-runner agent "test-agent")]))

(use-fixtures :each
  (fn [f]
    (let [[llm rnr] (build-runner)
          server (web/start-server! {"test-agent" rnr} {:port 0})
          port (.getLocalPort (first (.getConnectors server)))]
      (binding [*base-url* (str "http://localhost:" port) *llm* llm]
        (try (f) (finally (web/stop-server! server)))))))

(def ^:private http-client (HttpClient/newHttpClient))

(defn- http-get [path]
  (.send http-client
         (-> (HttpRequest/newBuilder (URI/create (str *base-url* path))) .GET .build)
         (HttpResponse$BodyHandlers/ofString)))

(defn- http-post-json [path body]
  (.send http-client
         (-> (HttpRequest/newBuilder (URI/create (str *base-url* path)))
             (.header "Content-Type" "application/json")
             (.POST (HttpRequest$BodyPublishers/ofString (json/write-str body)))
             .build)
         (HttpResponse$BodyHandlers/ofString)))

(defn- parse-sse [body]
  "-> seq de {:event string-ou-nil :data parsed-json}"
  (for [part (str/split body #"\n\n") :when (seq (str/trim part))]
    (let [lines (str/split-lines part)
          event (some #(when (str/starts-with? % "event: ") (subs % 7)) lines)
          data (some #(when (str/starts-with? % "data: ") (subs % 6)) lines)]
      {:event event :data (when data (json/read-str data :key-fn keyword))})))

(deftest serves-console-html
  (let [resp (http-get "/")]
    (is (= 200 (.statusCode resp)))
    (is (str/includes? (.body resp) "adk-clojure"))))

(deftest lists-registered-apps
  (let [resp (http-get "/apps")]
    (is (= 200 (.statusCode resp)))
    (is (= {:apps ["test-agent"]} (json/read-str (.body resp) :key-fn keyword)))))

(deftest run-streams-session-event-then-agent-response-then-done
  (let [resp (http-post-json "/run" {:app "test-agent" :user_id "u1" :text "oi"})
        sse (parse-sse (.body resp))]
    (is (= 200 (.statusCode resp)))
    (testing "primeiro evento: a sessão usada"
      (is (= "session" (:event (first sse))))
      (is (string? (get-in (first sse) [:data :session_id]))))
    (testing "evento do agente, com o texto real da resposta do MockLlm"
      (let [agent-ev (second sse)]
        (is (nil? (:event agent-ev)))
        (is (= "test-agent" (get-in agent-ev [:data :author])))
        (is (= "oi, tudo bem?" (get-in agent-ev [:data :text])))))
    (testing "último evento: done"
      (is (= "done" (:event (last sse)))))))

(deftest run-reuses-session-across-calls-and-preserves-history
  (let [first-resp (http-post-json "/run" {:app "test-agent" :user_id "u1" :text "oi"})
        session-id (get-in (first (parse-sse (.body first-resp))) [:data :session_id])
        _ (http-post-json "/run" {:app "test-agent" :user_id "u1" :session_id session-id :text "de novo?"})
        second-request (second (mock/requests-of *llm*))]
    (testing "a 2a chamada ao modelo já viu o histórico da 1a (user+model)"
      (is (= 3 (count (:contents second-request)))))))

(deftest run-with-unknown-app-returns-404
  (let [resp (http-post-json "/run" {:app "nope" :user_id "u1" :text "oi"})]
    (is (= 404 (.statusCode resp)))
    (is (str/includes? (.body resp) "desconhecida"))))

(deftest run-without-user-id-returns-400
  (let [resp (http-post-json "/run" {:app "test-agent" :text "oi"})]
    (is (= 400 (.statusCode resp)))))
