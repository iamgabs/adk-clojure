(ns adk.models.gemini-test
  "Testes de `adk.models.gemini`. O smoke test de construção roda sempre
  (não toca rede); os testes marcados ^:integration chamam a API real do
  Gemini e exigem GOOGLE_API_KEY no ambiente — por isso ficam fora de
  `clj -X:test` por padrão (ver deps.edn, alias :test exclui :integration;
  rode `clj -X:test-integration` para incluí-los)."
  (:require [clojure.test :refer [deftest testing is]]
            [adk.models :as models]
            [adk.models.gemini :as gemini]
            [adk.tools :as tools]
            [adk.tools.function-tool :as ft]
            [adk.tools.google-search :as google-search]
            [adk.tools.vertex-ai-search :as vertex-ai-search]
            [adk.tools.vertex-ai-rag-retrieval :as rag]))

(deftest builds-llm-without-network-call
  (let [llm (gemini/gemini-llm {:model "gemini-2.0-flash" :client-opts {:api-key "fake-key-for-construction-only"}})]
    (is (= "gemini-2.0-flash" (models/model-name llm)))))

(defn say-hi
  "Diz oi."
  {:adk/tool {}}
  []
  "oi")

(deftest request->config-builds-separate-tool-entries-for-grounding-and-functions
  (let [request->config #'gemini/request->config
        plain-tool (ft/function-tool #'say-hi)
        request (models/llm-request {:tools {"say_hi" plain-tool}
                                      :grounding-tools [{:google-search {}}]})
        cfg (request->config request)
        gemini-tools (vec (.get (.tools cfg)))]
    (is (= 2 (count gemini-tools)))
    (testing "uma entrada Tool com functionDeclarations, outra com googleSearch"
      (is (= 1 (count (filter #(.isPresent (.functionDeclarations %)) gemini-tools))))
      (is (= 1 (count (filter #(.isPresent (.googleSearch %)) gemini-tools)))))))

(deftest request->config-with-only-grounding-tools
  (let [request->config #'gemini/request->config
        request (models/llm-request {:grounding-tools [{:google-search {}}]})
        cfg (request->config request)
        gemini-tools (vec (.get (.tools cfg)))]
    (is (= 1 (count gemini-tools)))
    (is (.isPresent (.googleSearch (first gemini-tools))))))

(deftest request->config-url-context
  (let [request->config #'gemini/request->config
        request (models/llm-request {:grounding-tools [{:url-context {}}]})
        gemini-tools (vec (.get (.tools (request->config request))))]
    (is (= 1 (count gemini-tools)))
    (is (.isPresent (.urlContext (first gemini-tools))))))

(deftest request->config-code-execution
  (let [request->config #'gemini/request->config
        request (models/llm-request {:grounding-tools [{:code-execution {}}]})
        gemini-tools (vec (.get (.tools (request->config request))))]
    (is (= 1 (count gemini-tools)))
    (is (.isPresent (.codeExecution (first gemini-tools))))))

(deftest request->config-vertex-ai-search
  (let [request->config #'gemini/request->config
        spec (tools/grounding-spec (vertex-ai-search/vertex-ai-search-tool
                                      {:datastore "projects/p/dataStores/d" :max-results 7}))
        request (models/llm-request {:grounding-tools [spec]})
        gemini-tool (first (.get (.tools (request->config request))))
        retrieval (.get (.retrieval gemini-tool))
        vais (.get (.vertexAiSearch retrieval))]
    (is (= "projects/p/dataStores/d" (.get (.datastore vais))))
    (is (= 7 (.get (.maxResults vais))))))

(deftest request->config-vertex-ai-rag-retrieval
  (let [request->config #'gemini/request->config
        spec (tools/grounding-spec (rag/vertex-ai-rag-retrieval-tool
                                      {:rag-corpora ["projects/p/ragCorpora/1"] :similarity-top-k 3}))
        request (models/llm-request {:grounding-tools [spec]})
        gemini-tool (first (.get (.tools (request->config request))))
        retrieval (.get (.retrieval gemini-tool))
        rag-store (.get (.vertexRagStore retrieval))]
    (is (= ["projects/p/ragCorpora/1"] (vec (.get (.ragCorpora rag-store)))))
    (is (= 3 (.get (.similarityTopK rag-store))))))

(deftest ^:integration real-gemini-call
  (if-not (System/getenv "GOOGLE_API_KEY")
    (println "SKIP real-gemini-call: defina GOOGLE_API_KEY para rodar este teste")
    (let [llm (gemini/gemini-llm {:model "gemini-2.0-flash"
                                   :client-opts {:api-key (System/getenv "GOOGLE_API_KEY")}})
          req (models/llm-request {:model "gemini-2.0-flash"
                                    :contents [{:role "user" :parts [{:text "Diga apenas: ok"}]}]})
          [resp] (models/generate-content llm req false)]
      (testing "a API real responde com texto"
        (is (string? (-> resp :content :parts first :text)))))))
