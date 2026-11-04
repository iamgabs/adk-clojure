(ns adk.runner
  "Runner — liga um agente raiz a um SessionService e executa uma invocação,
  espelhando `runner/Runner.java`/`runners.py` + `InMemoryRunner`.

  Responsabilidades do Runner (e só dele):
  1. Criar/recuperar a Session via SessionService.
  2. Injetar `new-message` como o primeiro Event (autor \"user\").
  3. Resolver `transfer_to_agent` (AutoFlow) buscando o agente-alvo pelo
     nome em toda a árvore a partir da raiz, e continuar a execução a
     partir dele.
  4. Persistir cada Event produzido via SessionService (durável, entre
     invocações) — a visibilidade DENTRO de uma mesma invocação já é
     garantida por `adk.context/append-event!`, chamado por `adk.flows`/
     `adk.agents` assim que cada Event é produzido."
  (:require [adk.agents :as agents]
            [adk.context :as context]
            [adk.events :as events]
            [adk.sessions :as sessions]))

(defn- find-agent
  "Busca em profundidade, a partir de `root`, o agente cujo nome é
  `target-name` — usado para resolver o alvo de um transfer_to_agent, que
  pode ser qualquer agente da árvore (sub-agente, irmão, ou o próprio pai)."
  [root target-name]
  (if (= (agents/agent-name* root) target-name)
    root
    (some #(find-agent % target-name) (agents/sub-agents* root))))

(defn- run-with-transfers
  "Dreno o stream de `agent`; sempre que um Event tiver
  actions.transfer-to-agent setado, para aí e continua a partir do agente
  encontrado por nome em toda a árvore (a partir de `root`).

  Usa `rest`, não desestruturação `[ev & more]` — ver nota em
  `adk.agents.loop-agent/events-until-escalate` sobre por que isso importa
  para não forçar uma chamada a mais do que o necessário."
  [root agent ctx]
  (lazy-seq
    (let [evs (agents/run-agent agent ctx)]
      ((fn drain [s]
         (lazy-seq
           (when (seq s)
             (let [ev (first s)]
               (if-let [target-name (get-in ev [:actions :transfer-to-agent])]
                 (cons ev
                       (when-let [target (find-agent root target-name)]
                         (run-with-transfers root target ctx)))
                 (cons ev (drain (rest s))))))))
       evs))))

(defn runner
  "opts: {:agent :app-name? :session-service}."
  [{:keys [agent app-name session-service]}]
  {:agent agent
   :app-name (or app-name "default-app")
   :session-service session-service})

(defn in-memory-runner
  "Conveniência: Runner com InMemorySessionService já configurado."
  ([agent] (in-memory-runner agent "InMemoryRunner"))
  ([agent app-name]
   (runner {:agent agent :app-name app-name
            :session-service (sessions/in-memory-session-service)})))

(defn run-async
  "Executa uma invocação e devolve uma seq preguiçosa de Event. `opts`:
  {:user-id :session-id? :new-message? :run-config? :state}. `session-id`
  omitido cria uma sessão nova; `new-message` é o Content do turno do
  usuário (pode ser omitido para simplesmente continuar/retomar uma sessão
  existente sem nova entrada)."
  [{:keys [agent app-name session-service]}
   {:keys [user-id session-id new-message run-config state]}]
  (let [svc session-service
        session (or (and session-id
                          (sessions/get-session svc {:app-name app-name :user-id user-id
                                                      :session-id session-id}))
                     (sessions/create-session svc {:app-name app-name :user-id user-id
                                                    :session-id session-id :state state}))
        session-ref {:app-name app-name :user-id user-id :id (:id session)}
        ctx (context/invocation-context {:session-service svc :session session :agent agent
                                          :user-content new-message :run-config run-config})]
    (when new-message
      (let [user-event (events/event {:invocation-id (:invocation-id ctx) :author "user"
                                       :content new-message})]
        (context/append-event! ctx user-event)
        (sessions/append-event svc session-ref user-event)))
    (map (fn [ev] (sessions/append-event svc session-ref ev) ev)
         (run-with-transfers agent agent ctx))))

(defn run
  "Versão \"tudo de uma vez\": roda `run-async` e realiza a sequência
  inteira num vetor (conveniência para scripts/REPL/testes)."
  [rnr opts]
  (vec (run-async rnr opts)))
