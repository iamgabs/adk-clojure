(ns adk.agents.sequential-agent-test
  (:require [clojure.test :refer [deftest testing is]]
            [adk.agents :as agents]
            [adk.agents.llm-agent :as llm-agent]
            [adk.agents.sequential-agent :as sequential-agent]
            [adk.context :as context]
            [adk.models :as models]
            [adk.test-util.mock-llm :as mock]
            [adk.test-util.helpers :as h]))

(deftest propagates-output-key-and-order
  (let [llm1 (mock/mock-llm [(models/llm-response {:content (h/text-content "model" "Ana")})])
        llm2 (mock/mock-llm [(models/llm-response {:content (h/text-content "model" "Oi, Ana!")})])
        agent1 (llm-agent/llm-agent {:name "extractor" :model llm1 :output-key "user-name"})
        agent2 (llm-agent/llm-agent {:name "greeter" :model llm2
                                      :instruction (fn [ctx]
                                                     (str "Cumprimente "
                                                          (get (:state (context/current-session ctx)) "user-name")))})
        seq-agent (sequential-agent/sequential-agent {:name "pipeline" :sub-agents [agent1 agent2]})
        session {:id "s1" :app-name "app" :user-id "u1" :state {} :events []}
        ctx (context/invocation-context {:session-service nil :session session :agent seq-agent})
        evs (doall (agents/run-agent seq-agent ctx))]
    (is (= [["extractor" "Ana"] ["greeter" "Oi, Ana!"]] (h/simplify-events evs)))
    (is (= "Ana" (get-in (first evs) [:actions :state-delta "user-name"])))
    (testing "agent2 viu o output-key de agent1 via o state já atualizado no atom de sessão"
      (is (= "Ana" (second (re-find #"Cumprimente (.*)" (:system-instruction (first (mock/requests-of llm2))))))))
    (testing "agent2 também viu o Event de agent1 no histórico de contents"
      (is (= 1 (count (:contents (first (mock/requests-of llm2)))))))))
