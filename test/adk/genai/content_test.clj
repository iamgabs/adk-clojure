(ns adk.genai.content-test
  (:require [clojure.test :refer [deftest testing is]]
            [adk.genai.content :as content]))

(deftest text-part-round-trip
  (let [m {:role "user" :parts [{:text "olá, mundo"}]}]
    (is (= m (content/content->map (content/map->content m))))))

(deftest function-call-part-round-trip
  (let [m {:role "model" :parts [{:function-call {:name "get_weather" :args {"city" "Paris"}}}]}]
    (is (= m (content/content->map (content/map->content m))))))

(deftest function-response-part-round-trip
  (let [m {:role "user" :parts [{:function-response {:name "get_weather" :response {"forecast" "ensolarado"}}}]}]
    (is (= m (content/content->map (content/map->content m))))))

(deftest multi-part-content-round-trip
  (let [m {:role "model" :parts [{:text "deixa eu checar"}
                                  {:function-call {:name "get_weather" :args {"city" "Paris"}}}]}]
    (is (= m (content/content->map (content/map->content m))))))
