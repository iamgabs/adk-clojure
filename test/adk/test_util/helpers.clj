(ns adk.test-util.helpers
  "Helpers de asserção para reduzir Events a shapes fáceis de comparar em
  testes, no espírito de `testing_utils.simplify_events` do adk-python."
  (:require [adk.events :as events]))

(defn simplify-events
  "-> vetor de [author texto] por evento."
  [evs]
  (mapv (fn [ev] [(:author ev) (events/text ev)]) evs))

(defn simplify-events+actions
  "-> vetor de [author texto {:escalate :transfer-to-agent}] por evento."
  [evs]
  (mapv (fn [ev] [(:author ev) (events/text ev)
                  (select-keys (:actions ev) [:escalate :transfer-to-agent])])
        evs))

(defn text-content
  "Constrói um Content {:role :parts} com uma única part de texto —
  conveniência para montar LlmResponses de teste."
  [role text]
  {:role role :parts [{:text text}]})

(defn function-call-content
  [name args]
  {:role "model" :parts [{:function-call {:name name :args args}}]})
