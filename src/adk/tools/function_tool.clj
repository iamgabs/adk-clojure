(ns adk.tools.function-tool
  "Envolve uma função/var Clojure comum como `adk.tools/Tool`, espelhando
  `tools/FunctionTool.java` (adk-java) e `tools/function_tool.py`
  (adk-python) — mas sem reflection: Clojure já preserva nomes de
  parâmetros via `(:arglists (meta var))`, então só falta descrição/tipo
  por parâmetro, resolvidos por convenção de metadata:

    (defn get-weather
      \"Retorna o clima atual de uma cidade.\"
      {:adk/tool {:name \"get_weather\"                       ;; opcional
                  :description \"...\"                        ;; opcional, default = docstring
                  :params {'city [:string {:description \"Nome da cidade\"}]}}}
      [city ^:adk/tool-context tool-context]
      ...)

    (def weather-tool (function-tool #'get-weather))

  - `:adk/tool` na metadata da var dá nome/descrição/schema Malli por
    parâmetro (chave = símbolo do parâmetro, igual ao usado no arglist).
  - `^:adk/tool-context` no símbolo de um parâmetro marca injeção
    automática do ToolContext — esse parâmetro nunca vem dos args do LLM
    nem entra no schema exposto ao modelo.
  - Parâmetros sem entrada em `:params` caem no fallback `[:string ...]`."
  (:require [adk.tools :as tools]
            [malli.json-schema :as mjs]))

(defn- param-symbols
  [v]
  (let [arglists (:arglists (meta v))]
    (when (not= 1 (count arglists))
      (throw (ex-info "function-tool exige uma função com exatamente uma aridade"
                       {:type ::unsupported-arglists :var v :arglists arglists})))
    (first arglists)))

(defn- tool-context-index
  [params]
  (first (keep-indexed (fn [i p] (when (:adk/tool-context (meta p)) i)) params)))

(defn- arg-val
  "Busca o valor de `p` em `args`, aceitando tanto chave string quanto
  keyword (os args vindos de um LlmRequest real são string-keyed; testes
  costumam usar maps keyword-keyed por conveniência)."
  [args p]
  (let [k (clojure.core/name p)]
    (if-let [e (find args k)]
      (val e)
      (get args (keyword k)))))

(defrecord FunctionTool [name description json-schema f params tc-index]
  tools/Tool
  (tool-name [_] name)
  (tool-description [_] description)
  (tool-declaration [_] {:name name :description description :parameters json-schema})
  (long-running? [_] false)
  (invoke-tool [_ args tool-context]
    (apply f
           (map-indexed (fn [i p] (if (= i tc-index) tool-context (arg-val args p)))
                        params))))

(defn function-tool
  "Constrói uma `adk.tools/Tool` a partir de uma var de função Clojure."
  [v]
  (let [{tool-meta :adk/tool doc :doc fn-name :name} (meta v)
        params (param-symbols v)
        tc-index (tool-context-index params)
        llm-params (remove #(:adk/tool-context (meta %)) params)
        malli-schema (into [:map]
                           (for [p llm-params]
                             [(keyword (clojure.core/name p))
                              (get-in tool-meta [:params p] [:string {:description (clojure.core/name p)}])]))
        json-schema (mjs/transform malli-schema)
        ;; Nomes de função Clojure são kebab-case por convenção; a maioria
        ;; dos provedores de LLM espera/prefere snake_case para nomes de
        ;; tool (é o que o próprio ecossistema Python do ADK produz), então
        ;; convertemos no default — só não quando :name é dado explicitamente.
        default-name (clojure.string/replace (clojure.core/name fn-name) "-" "_")]
    (map->FunctionTool
      {:name (or (:name tool-meta) default-name)
       :description (or (:description tool-meta) doc "")
       :json-schema json-schema
       :f @v
       :params params
       :tc-index tc-index})))
