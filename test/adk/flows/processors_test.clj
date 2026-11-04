(ns adk.flows.processors-test
  (:require [clojure.test :refer [deftest testing is]]
            [adk.flows.processors :as p]
            [adk.models :as models]
            [adk.context :as context]
            [adk.tools.function-tool :as ft]
            [adk.tools.google-search :as google-search]))

(defn say-hi
  "Diz oi."
  {:adk/tool {}}
  []
  "oi")

(deftest instructions-processor-combines-global-and-local
  (let [agent {:global-instruction "Seja educado." :instruction "Responda em português."}
        ctx {:agent agent}
        request (p/instructions (models/llm-request {}) ctx)]
    (is (= "Seja educado.\n\nResponda em português." (:system-instruction request)))))

(deftest instructions-processor-supports-instruction-fn
  (let [agent {:instruction (fn [_ctx] "instrução dinâmica")}
        ctx {:agent agent}
        request (p/instructions (models/llm-request {}) ctx)]
    (is (= "instrução dinâmica" (:system-instruction request)))))

(deftest contents-processor-respects-include-contents
  (let [session (atom {:events [{:content {:role "user" :parts [{:text "oi"}]}}]})
        ctx-all {:agent {:include-contents :all} :session session}
        ctx-none {:agent {:include-contents :none} :session session}]
    (is (= 1 (count (:contents (p/contents (models/llm-request {}) ctx-all)))))
    (is (= [] (:contents (p/contents (models/llm-request {}) ctx-none))))))

(deftest tools-declaration-separates-function-tools-from-grounding-tools
  (let [plain-tool (ft/function-tool #'say-hi)
        agent {:tools [plain-tool google-search/google-search-tool]}
        ctx {:agent agent}
        request (p/tools-declaration (models/llm-request {}) ctx)]
    (testing "tool comum vai pra :tools (function declarations)"
      (is (contains? (:tools request) "say_hi")))
    (testing "google_search vai pra :grounding-tools, não pra :tools"
      (is (not (contains? (:tools request) "google_search")))
      (is (= [{:google-search {}}] (:grounding-tools request))))))
