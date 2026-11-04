(ns adk.state-test
  (:require [clojure.test :refer [deftest testing is]]
            [adk.state :as state]))

(deftest key-helpers
  (is (= "app:theme" (state/app-key :theme)))
  (is (= "user:lang" (state/user-key "lang")))
  (is (= "temp:scratch" (state/temp-key :scratch)))
  (is (state/app-key? "app:theme"))
  (is (not (state/app-key? "theme")))
  (is (state/user-key? "user:lang"))
  (is (state/temp-key? "temp:scratch")))

(deftest strip-temp-test
  (is (= {"foo" 1 "app:bar" 2}
         (state/strip-temp {"foo" 1 "app:bar" 2 "temp:x" 3}))))

(deftest split-by-scope-test
  (is (= {:app {"x" 1} :user {"y" 2} :session {"z" 3 "temp:w" 4}}
         (state/split-by-scope {"app:x" 1 "user:y" 2 "z" 3 "temp:w" 4}))))

(deftest merge-state-delta-test
  (is (= {"a" 1 "b" 2} (state/merge-state-delta {"a" 1} {"b" 2}))))
