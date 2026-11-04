(ns adk.flows.auto-flow-test
  (:require [clojure.test :refer [deftest testing is]]
            [adk.agents.llm-agent :as llm-agent]
            [adk.runner :as runner]
            [adk.events :as events]
            [adk.models :as models]
            [adk.test-util.mock-llm :as mock]
            [adk.test-util.helpers :as h]))

(deftest coordinator-transfers-to-specialist
  (let [coordinator-llm (mock/mock-llm
                           [(models/llm-response
                              {:content (h/function-call-content
                                          "transfer_to_agent" {"agent_name" "specialist"})})])
        specialist-llm (mock/mock-llm
                          [(models/llm-response {:content (h/text-content "model" "Claro, vou ajudar!")})])
        specialist (llm-agent/llm-agent {:name "specialist" :model specialist-llm})
        coordinator (llm-agent/llm-agent {:name "coordinator" :model coordinator-llm
                                           :sub-agents [specialist]})
        rnr (runner/in-memory-runner coordinator "app1")
        evs (runner/run rnr {:user-id "u1" :session-id "s1"
                              :new-message (h/text-content "user" "preciso de ajuda especializada")})]
    (is (= 3 (count evs)))
    (testing "evento 1: coordinator pede a transferência (function-call pendente)"
      (is (= "coordinator" (:author (first evs))))
      (is (events/pending-function-call? (first evs))))
    (testing "evento 2: a function-response do coordinator carrega transfer-to-agent"
      (is (= "specialist" (get-in (second evs) [:actions :transfer-to-agent]))))
    (testing "evento 3: a execução continuou a partir do specialist"
      (is (= "specialist" (:author (last evs))))
      (is (= "Claro, vou ajudar!" (events/text (last evs)))))))

(deftest unknown-transfer-target-ends-stream-gracefully
  (let [coordinator-llm (mock/mock-llm
                           [(models/llm-response
                              {:content (h/function-call-content
                                          "transfer_to_agent" {"agent_name" "ninguem"})})])
        other (llm-agent/llm-agent {:name "other" :model (mock/mock-llm [])})
        coordinator (llm-agent/llm-agent {:name "coordinator" :model coordinator-llm :sub-agents [other]})
        rnr (runner/in-memory-runner coordinator "app1")
        evs (runner/run rnr {:user-id "u1" :session-id "s1"
                              :new-message (h/text-content "user" "oi")})]
    (is (= 2 (count evs)))
    (is (= "ninguem" (get-in (last evs) [:actions :transfer-to-agent])))))
