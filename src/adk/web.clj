(ns adk.web
  "Servidor web mínimo de testes — o equivalente do adk-clojure a `adk web`,
  bem mais simples que o original (sem trace viewer, sem eval authoring,
  sem UI Angular): um console de chat servido numa página só, pra testar
  um agente pelo navegador. Espelha o papel de `cli/fast_api.py` +
  `cli/adk_web_server.py` (adk-python) só na parte de \"rodar o agente e
  ver a conversa\", via Server-Sent Events.

  DEPENDÊNCIA OPCIONAL (não entra em `:deps` do adk-clojure): adicione ao
  seu deps.edn:
    ring/ring-core {:mvn/version \"1.15.6\"}
    ring/ring-jetty-adapter {:mvn/version \"1.15.6\"}
    org.clojure/data.json {:mvn/version \"2.5.1\"}

  Uso:
    (require '[adk.web :as web])
    (def server (web/start-server! {\"my-agent\" my-runner} {:port 8080}))
    ;; abra http://localhost:8080 no navegador
    (web/stop-server! server)

  Rotas:
    GET  /      -> a página do console (HTML+JS embutidos, resources/adk/web/console.html)
    GET  /apps  -> {\"apps\": [\"my-agent\" ...]} — nomes das apps/Runners registradas
    POST /run   -> body {\"app\" \"user_id\" \"session_id\"? \"text\"} -> stream SSE dos
                   Events produzidos (um evento `session` primeiro, com o
                   session_id usado — novo se `session_id` não foi dado —
                   pra o cliente continuar a mesma conversa depois).

  LIMITAÇÃO CONHECIDA: um único processo serve todas as apps registradas
  em memória (sem persistência entre reinícios do servidor — cada Runner
  já carrega seu próprio SessionService, tipicamente in-memory)."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [ring.adapter.jetty :as jetty]
            [ring.core.protocols :as protocols]
            [adk.runner :as runner]
            [adk.events :as events])
  (:import [java.io OutputStreamWriter]))

(defn- json-response [status data]
  {:status status
   :headers {"Content-Type" "application/json; charset=utf-8"}
   :body (json/write-str data)})

(defn- read-json-body [request]
  (with-open [r (io/reader (:body request))]
    (json/read r :key-fn keyword)))

(defn- event->json-safe
  "Prepara um Event pra virar JSON: troca o set `:long-running-tool-ids`
  por um vetor (data.json não serializa sets) e inclui um campo `:text`
  pronto, de conveniência pro console."
  [ev]
  (-> ev
      (update :long-running-tool-ids (fnil vec []))
      (assoc :text (events/text ev))))

(defn- write-sse! [^OutputStreamWriter w event-type data]
  (.write w (str (when event-type (str "event: " event-type "\n"))
                  "data: " (json/write-str data) "\n\n"))
  (.flush w))

(defn- sse-response
  "`events-seq` é consumido PREGUIÇOSAMENTE aqui — cada Event vira uma
  mensagem SSE assim que é produzido pelo Runner, não só no final."
  [session-id events-seq]
  {:status 200
   :headers {"Content-Type" "text/event-stream; charset=utf-8"
             "Cache-Control" "no-cache"
             "X-Accel-Buffering" "no"}
   :body (reify protocols/StreamableResponseBody
           (write-body-to-stream [_ _response output-stream]
             (with-open [w (OutputStreamWriter. output-stream "UTF-8")]
               (write-sse! w "session" {:session_id session-id})
               (try
                 (doseq [ev events-seq]
                   (write-sse! w nil (event->json-safe ev)))
                 (write-sse! w "done" {})
                 (catch Throwable t
                   (write-sse! w "error" {:message (or (.getMessage t) (str t))}))))))})

(defn- console-html []
  (slurp (io/resource "adk/web/console.html")))

(defn- handle-run [apps request]
  (let [{:keys [app user_id session_id text]} (read-json-body request)
        rnr (get apps app)]
    (cond
      (nil? rnr) (json-response 404 {:error (str "app desconhecida: " app)})
      (clojure.string/blank? (str user_id)) (json-response 400 {:error "user_id é obrigatório"})
      :else
      (let [session-id (or session_id (str (random-uuid)))
            events-seq (runner/run-async rnr {:user-id user_id :session-id session-id
                                               :new-message {:role "user" :parts [{:text text}]}})]
        (sse-response session-id events-seq)))))

(defn- make-handler [apps]
  (fn [{:keys [request-method uri] :as request}]
    (cond
      (and (= :get request-method) (= "/" uri))
      {:status 200 :headers {"Content-Type" "text/html; charset=utf-8"} :body (console-html)}

      (and (= :get request-method) (= "/apps" uri))
      (json-response 200 {:apps (vec (keys apps))})

      (and (= :post request-method) (= "/run" uri))
      (handle-run apps request)

      :else
      (json-response 404 {:error "not found"}))))

(defn start-server!
  "`apps`: map {nome-da-app (string) -> Runner (ver `adk.runner/runner`/
  `in-memory-runner`)}. `opts`: {:port (default 8080) :host :join?
  (default false, pra devolver o controle e deixar você parar o servidor
  depois via `stop-server!`)}.

  Devolve o objeto `org.eclipse.jetty.server.Server` — passe pra
  `stop-server!` quando terminar."
  [apps {:keys [port host join?] :or {port 8080 join? false}}]
  (jetty/run-jetty (make-handler apps)
                    (cond-> {:port port :join? join?}
                      host (assoc :host host))))

(defn stop-server!
  [server]
  (.stop server)
  nil)
