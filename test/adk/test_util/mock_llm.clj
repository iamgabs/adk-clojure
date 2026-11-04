(ns adk.test-util.mock-llm
  "Um Llm falso que devolve respostas pré-programadas de uma fila e registra
  as LlmRequests recebidas — o jeito do próprio adk-python de testar
  agents/flows/tools sem chamar a API real (ver `testing_utils.MockModel`)."
  (:require [adk.models :as models]))

(defn- pop-response!
  [responses-queue]
  (let [popped (atom nil)]
    (swap! responses-queue
           (fn [q]
             (if (seq q)
               (do (reset! popped (first q)) (rest q))
               (throw (ex-info "MockLlm: fila de respostas esgotada"
                                {:type ::exhausted})))))
    @popped))

(defrecord MockLlm [responses-queue requests-log]
  models/Llm
  (model-name [_] "mock")
  (generate-content [_ llm-request _stream?]
    (swap! requests-log conj llm-request)
    (let [next-resp (pop-response! responses-queue)]
      (if (sequential? next-resp) next-resp [next-resp]))))

(defn mock-llm
  "`responses`: seq de LlmResponse maps (uma por chamada a generate-content),
  ou vetores de LlmResponse maps para simular streaming (N parciais seguidas
  de uma final) numa única chamada."
  [responses]
  (->MockLlm (atom (seq responses)) (atom [])))

(defn requests-of
  "As LlmRequests recebidas por esta MockLlm, na ordem em que chegaram."
  [mock]
  @(:requests-log mock))
