(ns adk.tools.vertex-ai-rag-retrieval-test
  (:require [clojure.test :refer [deftest testing is]]
            [adk.tools :as tools]
            [adk.tools.vertex-ai-rag-retrieval :as rag]))

(deftest requires-rag-corpora
  (is (thrown? clojure.lang.ExceptionInfo (rag/vertex-ai-rag-retrieval-tool {})))
  (is (thrown? clojure.lang.ExceptionInfo (rag/vertex-ai-rag-retrieval-tool {:rag-corpora []}))))

(deftest builds-with-rag-corpora
  (let [t (rag/vertex-ai-rag-retrieval-tool {:rag-corpora ["projects/p/ragCorpora/1"]})]
    (is (= "vertex_ai_rag_retrieval" (tools/tool-name t)))
    (is (satisfies? tools/GroundingTool t))
    (is (= {:vertex-ai-rag-retrieval {:rag-corpora ["projects/p/ragCorpora/1"]}}
           (tools/grounding-spec t)))))

(deftest builds-with-optional-tuning-params
  (let [t (rag/vertex-ai-rag-retrieval-tool
            {:rag-corpora ["projects/p/ragCorpora/1"]
             :similarity-top-k 3
             :vector-distance-threshold 0.5})]
    (is (= {:vertex-ai-rag-retrieval {:rag-corpora ["projects/p/ragCorpora/1"]
                                       :similarity-top-k 3
                                       :vector-distance-threshold 0.5}}
           (tools/grounding-spec t)))))

(deftest invoke-tool-throws
  (is (thrown? clojure.lang.ExceptionInfo
               (tools/invoke-tool (rag/vertex-ai-rag-retrieval-tool {:rag-corpora ["x"]}) {} nil))))
