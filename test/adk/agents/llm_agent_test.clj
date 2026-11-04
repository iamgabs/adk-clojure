(ns adk.agents.llm-agent-test
  (:require [clojure.test :refer [deftest testing is]]
            [adk.agents :as agents]
            [adk.agents.llm-agent :as llm-agent]
            [adk.context :as context]
            [adk.events :as events]
            [adk.models :as models]
            [adk.test-util.mock-llm :as mock]
            [adk.test-util.helpers :as h]
            [adk.tools.function-tool :as ft]))

(deftest single-turn-no-tools
  (let [resp (models/llm-response {:content (h/text-content "model" "Olá!")})
        llm (mock/mock-llm [resp])
        agent (llm-agent/llm-agent {:name "greeter" :model llm :output-key "greeting"})
        session {:id "s1" :app-name "app" :user-id "u1" :state {} :events []}
        ctx (context/invocation-context {:session-service nil :session session :agent agent})
        evs (doall (agents/run-agent agent ctx))]
    (is (= [["greeter" "Olá!"]] (h/simplify-events evs)))
    (is (= "Olá!" (get-in (last evs) [:actions :state-delta "greeting"])))
    (testing "o evento também foi anexado ao atom de sessão vivo da invocação"
      (is (= evs (:events (context/current-session ctx)))))))

(defn get-weather
  "Retorna o clima atual de uma cidade."
  {:adk/tool {:params {'city [:string {:description "Nome da cidade"}]}}}
  [city]
  {:city city :forecast "ensolarado"})

(deftest function-call-then-final-response
  (let [weather-tool (ft/function-tool #'get-weather)
        call-resp (models/llm-response {:content (h/function-call-content "get_weather" {"city" "Paris"})})
        final-resp (models/llm-response {:content (h/text-content "model" "Está ensolarado em Paris.")})
        llm (mock/mock-llm [call-resp final-resp])
        agent (llm-agent/llm-agent {:name "weather-agent" :model llm :tools [weather-tool]})
        session {:id "s1" :app-name "app" :user-id "u1" :state {} :events []}
        ctx (context/invocation-context {:session-service nil :session session :agent agent})
        evs (doall (agents/run-agent agent ctx))]
    (is (= 3 (count evs)))
    (testing "evento 1: a function-call do modelo"
      (is (events/pending-function-call? (first evs))))
    (testing "evento 2: a function-response, com o resultado real da tool"
      (is (= [{:function-response {:name "get_weather"
                                    :response {:city "Paris" :forecast "ensolarado"}}}]
             (events/parts (second evs)))))
    (testing "evento 3: a resposta final de texto"
      (is (events/final-response? (last evs)))
      (is (= "Está ensolarado em Paris." (events/text (last evs)))))
    (testing "a 2a chamada ao modelo já viu a function-call/response no histórico"
      (let [second-request (second (mock/requests-of llm))]
        (is (= 2 (count (:contents second-request))))))))

(deftest before-agent-callback-short-circuits
  (let [llm (mock/mock-llm [])
        agent (llm-agent/llm-agent
                {:name "guarded" :model llm
                 :before-agent-callbacks [(fn [_agent _ctx] (h/text-content "model" "bloqueado"))]})
        session {:id "s1" :app-name "app" :user-id "u1" :state {} :events []}
        ctx (context/invocation-context {:session-service nil :session session :agent agent})
        evs (doall (agents/run-agent agent ctx))]
    (is (= [["guarded" "bloqueado"]] (h/simplify-events evs)))
    (is (empty? (mock/requests-of llm)))))

(deftest before-model-callback-skips-real-call
  (let [llm (mock/mock-llm [])
        agent (llm-agent/llm-agent
                {:name "mocked" :model llm
                 :before-model-callbacks
                 [(fn [_agent _req _ctx] (models/llm-response {:content (h/text-content "model" "fake")}))]})
        session {:id "s1" :app-name "app" :user-id "u1" :state {} :events []}
        ctx (context/invocation-context {:session-service nil :session session :agent agent})
        evs (doall (agents/run-agent agent ctx))]
    (is (= [["mocked" "fake"]] (h/simplify-events evs)))
    (is (empty? (mock/requests-of llm)))))
