(ns adk.genai.schema-test
  (:require [clojure.test :refer [deftest testing is]]
            [adk.tools :as tools]
            [adk.genai.schema :as schema]
            [adk.tools.function-tool :as ft]))

(defn get-weather
  "Retorna o clima atual de uma cidade."
  {:adk/tool {:params {'city [:string {:description "Nome da cidade"}]}}}
  [city]
  city)

(deftest converts-tool-declaration-without-throwing
  (let [decl (tools/tool-declaration (ft/function-tool #'get-weather))
        fd (schema/declaration->function-declaration decl)]
    (is (= "get_weather" (.. fd name get)))
    (is (= "Retorna o clima atual de uma cidade." (.. fd description get)))
    (is (true? (.. fd parameters get properties get (containsKey "city"))))))
