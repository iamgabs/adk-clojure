(ns adk.runner-test
  (:require [clojure.test :refer [deftest testing is]]
            [adk.runner :as runner]
            [adk.sessions :as sessions]
            [adk.agents.llm-agent :as llm-agent]
            [adk.models :as models]
            [adk.test-util.mock-llm :as mock]
            [adk.test-util.helpers :as h]))

(deftest runner-persists-events-and-folds-state
  (let [resp (models/llm-response {:content (h/text-content "model" "oi, tudo bem?")})
        llm (mock/mock-llm [resp])
        agent (llm-agent/llm-agent {:name "greeter" :model llm})
        rnr (runner/in-memory-runner agent "app1")
        evs (runner/run rnr {:user-id "u1" :session-id "s1"
                              :new-message (h/text-content "user" "oi")})]
    (testing "o stream devolvido traz só os eventos do agente (não o próprio user-event injetado)"
      (is (= [["greeter" "oi, tudo bem?"]] (h/simplify-events evs))))
    (let [persisted (sessions/get-session (:session-service rnr)
                                           {:app-name "app1" :user-id "u1" :session-id "s1"})]
      (is (= 2 (count (:events persisted)))))))

(deftest runner-continues-existing-session-with-history
  (let [resp1 (models/llm-response {:content (h/text-content "model" "Prazer, Ana.")})
        resp2 (models/llm-response {:content (h/text-content "model" "Seu nome é Ana.")})
        llm (mock/mock-llm [resp1 resp2])
        agent (llm-agent/llm-agent {:name "bot" :model llm})
        rnr (runner/in-memory-runner agent "app1")]
    (runner/run rnr {:user-id "u1" :session-id "s1" :new-message (h/text-content "user" "sou a Ana")})
    (runner/run rnr {:user-id "u1" :session-id "s1" :new-message (h/text-content "user" "qual é meu nome?")})
    (let [second-request (second (mock/requests-of llm))]
      ;; a 2a invocação deve ver os 3 eventos da 1a (user+model) + seu próprio user event
      (is (= 3 (count (:contents second-request)))))))
