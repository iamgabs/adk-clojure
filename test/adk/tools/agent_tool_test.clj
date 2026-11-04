(ns adk.tools.agent-tool-test
  (:require [clojure.test :refer [deftest testing is]]
            [adk.tools :as tools]
            [adk.tools.agent-tool :as agent-tool]
            [adk.agents.llm-agent :as llm-agent]
            [adk.context :as context]
            [adk.models :as models]
            [adk.test-util.mock-llm :as mock]
            [adk.test-util.helpers :as h]))

(deftest agent-tool-runs-wrapped-agent-and-returns-final-text
  (let [sub-llm (mock/mock-llm [(models/llm-response {:content (h/text-content "model" "42")})])
        sub-agent (llm-agent/llm-agent {:name "calculator" :description "Resolve contas." :model sub-llm})
        tool (agent-tool/agent-tool sub-agent)
        ctx (context/invocation-context {:session-service nil :session {:events []}})
        tc (context/tool-context ctx {:function-call-id "fc1"})]
    (is (= "calculator" (tools/tool-name tool)))
    (is (= {:result "42"} (tools/invoke-tool tool {"request" "quanto é 6x7?"} tc)))
    (testing "o sub-agente recebeu a mensagem como texto do usuário"
      (let [req (first (mock/requests-of sub-llm))]
        (is (= [{:role "user" :parts [{:text "quanto é 6x7?"}]}] (:contents req)))))))
