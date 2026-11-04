(ns adk.sessions
  "Protocolo SessionService + InMemorySessionService, espelhando
  `sessions/BaseSessionService.java`/`InMemorySessionService.java` (adk-java)
  e `sessions/base_session_service.py`/`in_memory_session_service.py`
  (adk-python).

  Uma Session é um map:
    {:id :app-name :user-id :state {...} :events [...] :last-update-time ms}

  `:state` tal como devolvido por `get-session`/`append-event` já é a *vista
  mesclada* (session + user: + app:), igual ao comportamento documentado do
  adk-java: as chaves app:/user: são guardadas num store compartilhado por
  app/por usuário e remescladas de volta (com o prefixo) a cada leitura."
  (:require [adk.state :as state]))

(defprotocol SessionService
  (create-session [svc opts]
    "opts: {:app-name :user-id :state? :session-id?} -> Session.")
  (get-session [svc opts]
    "opts: {:app-name :user-id :session-id} -> Session ou nil.")
  (list-sessions [svc app-name user-id]
    "-> vetor de Session (com :state mesclado, mas sem necessariamente todos
    os :events — implementações podem omitir o histórico na listagem).")
  (delete-session [svc app-name user-id session-id])
  (append-event [svc session event]
    "Aplica o fold de `event` sobre `session` (via actions.state-delta,
    roteando app:/user:/temp: pelas regras de adk.state) e retorna a Session
    atualizada (vista mesclada)."))

(defn- merged-state
  [store app-name user-id local-state]
  (let [app-state (get-in store [:app-state app-name] {})
        user-state (get-in store [:user-state app-name user-id] {})]
    (merge
      (into {} (map (fn [[k v]] [(state/app-key k) v])) app-state)
      (into {} (map (fn [[k v]] [(state/user-key k) v])) user-state)
      local-state)))

(defrecord InMemorySessionService [store]
  SessionService
  (create-session [_ {:keys [app-name user-id state session-id]}]
    (let [id (or session-id (str (random-uuid)))
          now (System/currentTimeMillis)
          sess {:id id :app-name app-name :user-id user-id
                :state (or state {}) :events [] :last-update-time now}]
      (swap! store assoc-in [:sessions app-name user-id id] sess)
      (get-session (->InMemorySessionService store)
                   {:app-name app-name :user-id user-id :session-id id})))

  (get-session [_ {:keys [app-name user-id session-id]}]
    (let [snap @store]
      (when-let [sess (get-in snap [:sessions app-name user-id session-id])]
        (update sess :state #(merged-state snap app-name user-id %)))))

  (list-sessions [_ app-name user-id]
    (let [snap @store]
      (mapv (fn [sess] (update sess :state #(merged-state snap app-name user-id %)))
            (vals (get-in snap [:sessions app-name user-id] {})))))

  (delete-session [_ app-name user-id session-id]
    (swap! store update-in [:sessions app-name user-id] dissoc session-id)
    nil)

  (append-event [this {:keys [app-name user-id id] :as session} event]
    (let [delta (get-in event [:actions :state-delta] {})
          {:keys [app user session]} (state/split-by-scope delta)
          persisted-event (update-in event [:actions :state-delta] state/strip-temp)]
      (swap! store
             (fn [s]
               (-> s
                   (update-in [:app-state app-name] merge app)
                   (update-in [:user-state app-name user-id] merge user)
                   (update-in [:sessions app-name user-id id]
                              (fn [sess]
                                (-> sess
                                    (update :state state/merge-state-delta session)
                                    (update :events (fnil conj []) persisted-event)
                                    (assoc :last-update-time (System/currentTimeMillis))))))))
      (get-session this {:app-name app-name :user-id user-id :session-id id}))))

(defn in-memory-session-service
  "Construtor de uma InMemorySessionService nova e isolada (um atom próprio)."
  []
  (->InMemorySessionService (atom {:sessions {} :app-state {} :user-state {}})))
