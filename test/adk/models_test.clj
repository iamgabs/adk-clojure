(ns adk.models-test
  (:require [clojure.test :refer [deftest testing is]]
            [adk.models :as models]
            [adk.test-util.mock-llm :as mock]
            [adk.test-util.helpers :as h]))

(deftest resolve-model-instance-passthrough
  (let [m (mock/mock-llm [])]
    (is (identical? m (models/resolve-model m)))))

(deftest resolve-model-unknown-string-throws
  (models/reset-registry!)
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Nenhum Llm"
                         (models/resolve-model "totally-unknown-model"))))

(deftest resolve-model-via-registry
  (models/reset-registry!)
  (let [built (mock/mock-llm [])]
    (models/register-llm! #"^foo-.*" (fn [_] built))
    (is (identical? built (models/resolve-model "foo-bar")))))

(deftest mock-llm-returns-queued-responses-and-logs-requests
  (let [resp1 (models/llm-response {:content (h/text-content "model" "oi")})
        m (mock/mock-llm [resp1])
        req (models/llm-request {:model "mock" :contents [(h/text-content "user" "ola")]})
        out (models/generate-content m req false)]
    (is (= [resp1] out))
    (is (= [req] (mock/requests-of m)))))

(deftest mock-llm-exhausted-throws
  (let [m (mock/mock-llm [])]
    (is (thrown? clojure.lang.ExceptionInfo
                 (models/generate-content m (models/llm-request {:model "mock"}) false)))))

(deftest mock-llm-streaming-chunks
  (let [chunks [(models/llm-response {:content (h/text-content "model" "a") :partial true})
                (models/llm-response {:content (h/text-content "model" "ab")})]
        m (mock/mock-llm [chunks])
        out (models/generate-content m (models/llm-request {:model "mock"}) true)]
    (is (= chunks out))))
