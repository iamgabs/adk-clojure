(ns adk.tools.function-tool-test
  (:require [clojure.test :refer [deftest testing is]]
            [adk.tools :as tools]
            [adk.tools.function-tool :as ft]
            [adk.context :as context]))

(defn get-weather
  "Retorna o clima atual de uma cidade."
  {:adk/tool {:params {'city [:string {:description "Nome da cidade"}]}}}
  [city ^:adk/tool-context tool-context]
  (context/set-state! tool-context "temp:last-city" city)
  {:city city :forecast "ensolarado"})

(defn no-context-fn
  "Soma dois números."
  {:adk/tool {:params {'a :int 'b :int}}}
  [a b]
  (+ a b))

(defn no-meta-fn
  "Função sem :adk/tool metadata."
  [x]
  (str "echo:" x))

(deftest derives-name-and-description
  (let [t (ft/function-tool #'get-weather)]
    (is (= "get_weather" (tools/tool-name t)))
    (is (= "Retorna o clima atual de uma cidade." (tools/tool-description t)))))

(deftest derives-schema-from-metadata
  (let [t (ft/function-tool #'get-weather)
        decl (tools/tool-declaration t)]
    (is (= "get_weather" (:name decl)))
    (is (= "object" (get-in decl [:parameters :type])))
    (is (contains? (get-in decl [:parameters :properties]) :city))
    (is (not (contains? (get-in decl [:parameters :properties]) :tool-context)))))

(deftest injects-tool-context-and-binds-args
  (let [t (ft/function-tool #'get-weather)
        ctx (context/invocation-context {:session-service nil :session {:events []}})
        tc (context/tool-context ctx {:function-call-id "fc1"})
        result (tools/invoke-tool t {"city" "Paris"} tc)]
    (is (= {:city "Paris" :forecast "ensolarado"} result))
    (is (= "Paris" (get-in (context/tool-actions tc) [:state-delta "temp:last-city"])))))

(deftest accepts-keyword-keyed-args-too
  (let [t (ft/function-tool #'no-context-fn)
        ctx (context/invocation-context {:session-service nil :session {:events []}})
        tc (context/tool-context ctx {:function-call-id "fc1"})]
    (is (= 3 (tools/invoke-tool t {:a 1 :b 2} tc)))
    (is (= 3 (tools/invoke-tool t {"a" 1 "b" 2} tc)))))

(deftest falls-back-without-tool-metadata
  (let [t (ft/function-tool #'no-meta-fn)
        decl (tools/tool-declaration t)]
    (is (= "no_meta_fn" (:name t)))
    (is (= "Função sem :adk/tool metadata." (:description t)))
    (is (contains? (get-in decl [:parameters :properties]) :x))))
