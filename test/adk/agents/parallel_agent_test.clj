(ns adk.agents.parallel-agent-test
  (:require [clojure.test :refer [deftest testing is]]
            [adk.agents :as agents]
            [adk.agents.llm-agent :as llm-agent]
            [adk.agents.parallel-agent :as parallel-agent]
            [adk.context :as context]
            [adk.models :as models]
            [adk.test-util.mock-llm :as mock]
            [adk.test-util.helpers :as h]))

(deftest branches-write-distinct-state-keys
  (let [llm-a (mock/mock-llm [(models/llm-response {:content (h/text-content "model" "A pronto")})])
        llm-b (mock/mock-llm [(models/llm-response {:content (h/text-content "model" "B pronto")})])
        agent-a (llm-agent/llm-agent {:name "agentA" :model llm-a :output-key "result-a"})
        agent-b (llm-agent/llm-agent {:name "agentB" :model llm-b :output-key "result-b"})
        par (parallel-agent/parallel-agent {:name "fan-out" :sub-agents [agent-a agent-b]})
        session {:id "s1" :app-name "app" :user-id "u1" :state {} :events []}
        ctx (context/invocation-context {:session-service nil :session session :agent par})
        evs (doall (agents/run-agent par ctx))
        final-state (:state (context/current-session ctx))]
    (is (= 2 (count evs)))
    (is (= #{"A pronto" "B pronto"} (set (map second (h/simplify-events evs)))))
    (is (= "A pronto" (get final-state "result-a")))
    (is (= "B pronto" (get final-state "result-b")))))

(deftest branches-last-write-wins-on-same-key
  (let [llm-a (mock/mock-llm [(models/llm-response {:content (h/text-content "model" "de A")})])
        llm-b (mock/mock-llm [(models/llm-response {:content (h/text-content "model" "de B")})])
        agent-a (llm-agent/llm-agent {:name "agentA" :model llm-a :output-key "winner"})
        agent-b (llm-agent/llm-agent {:name "agentB" :model llm-b :output-key "winner"})
        par (parallel-agent/parallel-agent {:name "fan-out" :sub-agents [agent-a agent-b]})
        session {:id "s1" :app-name "app" :user-id "u1" :state {} :events []}
        ctx (context/invocation-context {:session-service nil :session session :agent par})
        _ (doall (agents/run-agent par ctx))
        winner (get (:state (context/current-session ctx)) "winner")]
    (is (contains? #{"de A" "de B"} winner))))

(deftest branches-do-not-see-each-others-history
  (let [llm-a (mock/mock-llm [(models/llm-response {:content (h/text-content "model" "oi de A")})])
        llm-b (mock/mock-llm [(models/llm-response {:content (h/text-content "model" "oi de B")})])
        agent-a (llm-agent/llm-agent {:name "agentA" :model llm-a})
        agent-b (llm-agent/llm-agent {:name "agentB" :model llm-b})
        par (parallel-agent/parallel-agent {:name "fan-out" :sub-agents [agent-a agent-b]})
        session {:id "s1" :app-name "app" :user-id "u1" :state {} :events []}
        ctx (context/invocation-context {:session-service nil :session session :agent par})
        evs (doall (agents/run-agent par ctx))
        ev-a (first (filter #(= "agentA" (:author %)) evs))
        ev-b (first (filter #(= "agentB" (:author %)) evs))]
    (is (= "agentA" (:branch ev-a)))
    (is (= "agentB" (:branch ev-b)))
    (testing "do ponto de vista do branch de A, o evento de B é invisível"
      (let [ctx-a (assoc ctx :branch "agentA")]
        (is (= [ev-a] (vec (context/events-on-branch ctx-a))))))
    (testing "o pai (sem branch) vê os dois"
      (is (= 2 (count (context/events-on-branch ctx)))))))
