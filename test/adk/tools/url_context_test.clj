(ns adk.tools.url-context-test
  (:require [clojure.test :refer [deftest testing is]]
            [adk.tools :as tools]
            [adk.tools.url-context :as url-context]))

(deftest identifies-as-a-grounding-tool
  (let [t url-context/url-context-tool]
    (is (= "url_context" (tools/tool-name t)))
    (is (satisfies? tools/GroundingTool t))
    (is (= {:url-context {}} (tools/grounding-spec t)))
    (is (nil? (tools/tool-declaration t)))))

(deftest invoke-tool-throws
  (is (thrown? clojure.lang.ExceptionInfo
               (tools/invoke-tool url-context/url-context-tool {} nil))))
