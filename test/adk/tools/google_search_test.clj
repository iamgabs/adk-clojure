(ns adk.tools.google-search-test
  (:require [clojure.test :refer [deftest testing is]]
            [adk.tools :as tools]
            [adk.tools.google-search :as google-search]))

(deftest identifies-as-a-tool-and-a-grounding-tool
  (let [t google-search/google-search-tool]
    (is (= "google_search" (tools/tool-name t)))
    (is (satisfies? tools/GroundingTool t))
    (is (= {:google-search {}} (tools/grounding-spec t)))
    (testing "não tem function declaration — não participa de function-calling"
      (is (nil? (tools/tool-declaration t))))))

(deftest invoke-tool-throws
  ;; nunca deveria ser chamada de verdade (o Gemini resolve a busca do lado
  ;; do servidor); se isso acontecer, é sinal de um bug no roteamento de
  ;; tools comuns vs. grounding tools.
  (is (thrown? clojure.lang.ExceptionInfo
               (tools/invoke-tool google-search/google-search-tool {} nil))))
