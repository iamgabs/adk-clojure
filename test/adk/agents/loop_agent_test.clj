(ns adk.agents.loop-agent-test
  (:require [clojure.test :refer [deftest testing is]]
            [adk.agents :as agents]
            [adk.agents.llm-agent :as llm-agent]
            [adk.agents.loop-agent :as loop-agent]
            [adk.context :as context]
            [adk.events :as events]
            [adk.models :as models]
            [adk.tools :as tools]
            [adk.test-util.mock-llm :as mock]
            [adk.test-util.helpers :as h]))

(defn- call-event-resp [] (models/llm-response {:content (h/function-call-content "exit_loop" {})}))
(defn- text-resp [t] (models/llm-response {:content (h/text-content "model" t)}))

(deftest exits-via-escalate-before-max-iterations
  ;; 3 iterações de texto normal; na 4a o agente chama exit_loop. O loop
  ;; corta assim que a function-response da tool chega (escalate=true),
  ;; sem esperar mais uma chamada ao modelo depois disso — por isso só 4
  ;; LlmResponses são consumidas, embora max-iterations seja bem maior.
  (let [llm (mock/mock-llm [(text-resp "passo 1") (text-resp "passo 2")
                             (text-resp "passo 3") (call-event-resp)])
        worker (llm-agent/llm-agent {:name "worker" :model llm :tools [tools/exit-loop-tool]})
        loop-ag (loop-agent/loop-agent {:name "retry-loop" :sub-agents [worker] :max-iterations 10})
        session {:id "s1" :app-name "app" :user-id "u1" :state {} :events []}
        ctx (context/invocation-context {:session-service nil :session session :agent loop-ag})
        evs (doall (agents/run-agent loop-ag ctx))]
    (testing "3 iterações de texto normal, depois a function-call de exit_loop e sua resposta"
      (is (= [["worker" "passo 1"] ["worker" "passo 2"] ["worker" "passo 3"]]
             (h/simplify-events (take 3 evs)))))
    (is (= 5 (count evs)) "3 textos + a function-call de exit_loop + sua function-response")
    (testing "evento 4: a function-call pendente (ainda sem escalate)"
      (is (events/pending-function-call? (nth evs 3)))
      (is (not (get-in (nth evs 3) [:actions :escalate]))))
    (testing "evento 5: a function-response, com escalate=true"
      (is (true? (get-in (last evs) [:actions :escalate]))))))

(deftest stops-at-max-iterations-without-escalate
  (let [llm (mock/mock-llm [(text-resp "a") (text-resp "b") (text-resp "c")])
        worker (llm-agent/llm-agent {:name "worker" :model llm})
        loop-ag (loop-agent/loop-agent {:name "bounded-loop" :sub-agents [worker] :max-iterations 3})
        session {:id "s1" :app-name "app" :user-id "u1" :state {} :events []}
        ctx (context/invocation-context {:session-service nil :session session :agent loop-ag})
        evs (doall (agents/run-agent loop-ag ctx))]
    (is (= [["worker" "a"] ["worker" "b"] ["worker" "c"]] (h/simplify-events evs)))))
