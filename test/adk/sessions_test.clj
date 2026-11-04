(ns adk.sessions-test
  (:require [clojure.test :refer [deftest testing is]]
            [adk.sessions :as sessions]
            [adk.events :as events]))

(deftest create-and-get-session
  (let [svc (sessions/in-memory-session-service)
        sess (sessions/create-session svc {:app-name "app1" :user-id "u1"})]
    (is (some? (:id sess)))
    (is (= {} (:state sess)))
    (is (= [] (:events sess)))
    (is (= sess (sessions/get-session svc {:app-name "app1" :user-id "u1"
                                            :session-id (:id sess)})))))

(deftest get-missing-session-returns-nil
  (let [svc (sessions/in-memory-session-service)]
    (is (nil? (sessions/get-session svc {:app-name "app1" :user-id "u1" :session-id "nope"})))))

(deftest append-event-routes-prefixes
  (testing "chaves sem prefixo ficam locais à sessão"
    (let [svc (sessions/in-memory-session-service)
          sess (sessions/create-session svc {:app-name "app1" :user-id "u1"})
          ev (events/event {:invocation-id "inv1" :author "agent1"
                             :actions (events/event-actions {:state-delta {"counter" 1}})})
          sess' (sessions/append-event svc sess ev)]
      (is (= 1 (get (:state sess') "counter")))
      (is (= 1 (count (:events sess'))))))

  (testing "app: é compartilhado entre sessões/usuários do mesmo app"
    (let [svc (sessions/in-memory-session-service)
          sess-a (sessions/create-session svc {:app-name "app1" :user-id "u1"})
          sess-b (sessions/create-session svc {:app-name "app1" :user-id "u2"})
          ev (events/event {:invocation-id "inv1" :author "agent1"
                             :actions (events/event-actions {:state-delta {"app:theme" "dark"}})})
          _ (sessions/append-event svc sess-a ev)
          sess-b' (sessions/get-session svc {:app-name "app1" :user-id "u2" :session-id (:id sess-b)})]
      (is (= "dark" (get (:state sess-b') "app:theme")))))

  (testing "user: é compartilhado entre sessões do mesmo usuário, não entre usuários"
    (let [svc (sessions/in-memory-session-service)
          sess-1 (sessions/create-session svc {:app-name "app1" :user-id "u1"})
          sess-2 (sessions/create-session svc {:app-name "app1" :user-id "u1"})
          sess-other-user (sessions/create-session svc {:app-name "app1" :user-id "u2"})
          ev (events/event {:invocation-id "inv1" :author "agent1"
                             :actions (events/event-actions {:state-delta {"user:lang" "pt-BR"}})})
          _ (sessions/append-event svc sess-1 ev)
          sess-2' (sessions/get-session svc {:app-name "app1" :user-id "u1" :session-id (:id sess-2)})
          sess-other' (sessions/get-session svc {:app-name "app1" :user-id "u2" :session-id (:id sess-other-user)})]
      (is (= "pt-BR" (get (:state sess-2') "user:lang")))
      (is (nil? (get (:state sess-other') "user:lang")))))

  (testing "temp: fica visível no state local mas é removido do evento persistido"
    (let [svc (sessions/in-memory-session-service)
          sess (sessions/create-session svc {:app-name "app1" :user-id "u1"})
          ev (events/event {:invocation-id "inv1" :author "agent1"
                             :actions (events/event-actions {:state-delta {"temp:scratch" 42 "kept" 1}})})
          sess' (sessions/append-event svc sess ev)
          persisted-event (first (:events sess'))]
      (is (= 42 (get (:state sess') "temp:scratch")))
      (is (= 1 (get (:state sess') "kept")))
      (is (not (contains? (get-in persisted-event [:actions :state-delta]) "temp:scratch")))
      (is (= 1 (get-in persisted-event [:actions :state-delta "kept"]))))))

(deftest delete-session-test
  (let [svc (sessions/in-memory-session-service)
        sess (sessions/create-session svc {:app-name "app1" :user-id "u1"})]
    (sessions/delete-session svc "app1" "u1" (:id sess))
    (is (nil? (sessions/get-session svc {:app-name "app1" :user-id "u1" :session-id (:id sess)})))))
