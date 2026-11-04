(ns adk.state
  "Convenções de prefixo para chaves de estado de sessão, espelhando
  `sessions/State.java` (adk-java) e `sessions/state.py` (adk-python):

  - sem prefixo -> estado local da sessão, persistido com ela.
  - \"app:\"  -> compartilhado entre todas as sessões do mesmo app.
  - \"user:\" -> compartilhado entre todas as sessões do mesmo usuário.
  - \"temp:\" -> escopo da invocação corrente; nunca persistido."
  (:require [clojure.string :as str]))

(def app-prefix "app:")
(def user-prefix "user:")
(def temp-prefix "temp:")

(defn app-key [k] (str app-prefix (name k)))
(defn user-key [k] (str user-prefix (name k)))
(defn temp-key [k] (str temp-prefix (name k)))

(defn app-key? [k] (str/starts-with? k app-prefix))
(defn user-key? [k] (str/starts-with? k user-prefix))
(defn temp-key? [k] (str/starts-with? k temp-prefix))

(defn strip-temp
  "Remove do map de delta as chaves `temp:*` — usado antes de persistir um
  Event, já que estado temp é escopo-de-invocação apenas."
  [state-delta]
  (into {} (remove (fn [[k _]] (temp-key? k))) state-delta))

(defn split-by-scope
  "Particiona um state-delta em três maps: {:app {...} :user {...} :session {...}},
  com os prefixos já removidos das chaves app:/user: (session mantém a chave
  original, incluindo temp:, já que quem decide descartar temp: é quem
  persiste o evento, não quem faz o merge local)."
  [state-delta]
  (reduce
    (fn [acc [k v]]
      (cond
        (app-key? k) (assoc-in acc [:app (subs k (count app-prefix))] v)
        (user-key? k) (assoc-in acc [:user (subs k (count user-prefix))] v)
        :else (assoc-in acc [:session k] v)))
    {:app {} :user {} :session {}}
    state-delta))

(defn merge-state-delta
  "Aplica um state-delta a uma state map local (merge raso, igual ao
  comportamento de dict.update do Python / State.putAll do Java)."
  [state delta]
  (merge state delta))
