(ns adk.tools.vertex-ai-search-test
  (:require [clojure.test :refer [deftest testing is]]
            [adk.tools :as tools]
            [adk.tools.vertex-ai-search :as vertex-ai-search]))

(deftest requires-datastore-or-engine
  (is (thrown? clojure.lang.ExceptionInfo (vertex-ai-search/vertex-ai-search-tool {}))))

(deftest rejects-both-datastore-and-engine
  (is (thrown? clojure.lang.ExceptionInfo
               (vertex-ai-search/vertex-ai-search-tool {:datastore "ds" :engine "eng"}))))

(deftest builds-with-datastore
  (let [t (vertex-ai-search/vertex-ai-search-tool {:datastore "projects/p/dataStores/d"})]
    (is (= "vertex_ai_search" (tools/tool-name t)))
    (is (satisfies? tools/GroundingTool t))
    (is (= {:vertex-ai-search {:datastore "projects/p/dataStores/d"}}
           (tools/grounding-spec t)))))

(deftest builds-with-engine-filter-and-max-results
  (let [t (vertex-ai-search/vertex-ai-search-tool
            {:engine "projects/p/engines/e" :filter "category:news" :max-results 5})]
    (is (= {:vertex-ai-search {:engine "projects/p/engines/e"
                                :filter "category:news"
                                :max-results 5}}
           (tools/grounding-spec t)))))

(deftest invoke-tool-throws
  (is (thrown? clojure.lang.ExceptionInfo
               (tools/invoke-tool (vertex-ai-search/vertex-ai-search-tool {:datastore "ds"}) {} nil))))
