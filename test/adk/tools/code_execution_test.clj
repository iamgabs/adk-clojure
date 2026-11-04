(ns adk.tools.code-execution-test
  (:require [clojure.test :refer [deftest testing is]]
            [adk.tools :as tools]
            [adk.tools.code-execution :as code-execution]))

(deftest identifies-as-a-grounding-tool
  (let [t code-execution/code-execution-tool]
    (is (= "code_execution" (tools/tool-name t)))
    (is (satisfies? tools/GroundingTool t))
    (is (= {:code-execution {}} (tools/grounding-spec t)))
    (is (nil? (tools/tool-declaration t)))))

(deftest invoke-tool-throws
  (is (thrown? clojure.lang.ExceptionInfo
               (tools/invoke-tool code-execution/code-execution-tool {} nil))))
