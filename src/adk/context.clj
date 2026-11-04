(ns adk.context
  "InvocationContext, ToolContext e RunConfig — espelham
  `agents/InvocationContext.java`/`ToolContext.java`/`RunConfig.java`
  (adk-java) e os equivalentes em `agents/invocation_context.py`,
  `tools/tool_context.py`, `agents/run_config.py` (adk-python).

  `InvocationContext` é um plain map; não há necessidade de um tipo
  dedicado já que nada despacha polimorficamente sobre ele.")

(def default-run-config
  "Espelha RunConfig: max-llm-calls é o limite padrão de 500 chamadas ao LLM
  por invocação (mesmo valor-default do adk-java/adk-python)."
  {:max-llm-calls 500
   :streaming-mode :none            ;; :none | :sse  (:bidi fora de escopo v0.1)
   :response-modalities ["TEXT"]
   :save-input-blobs-as-artifacts false})

(defn invocation-context
  "Constrói um InvocationContext. `session-service` e `session` são
  obrigatórios; `agent` é o agente raiz desta invocação (ou um sub-agente,
  quando o contexto é derivado via `child-context`).

  `:session` é guardado como um ATOM: diferente de Java/Python (onde
  Session.events é uma lista mutável compartilhada por toda a invocação),
  aqui precisamos de uma referência mutável explícita para que eventos
  produzidos no meio do loop de uma mesma invocação (ex: a function-call e
  sua function-response, antes da próxima chamada ao LLM) fiquem visíveis
  para os passos seguintes — mesmo agente, outro sub-agente de um
  SequentialAgent, ou outro branch de um ParallelAgent, todos compartilham o
  MESMO atom. Isso é ortogonal à persistência durável via SessionService,
  que é responsabilidade exclusiva do Runner (`adk.runner`)."
  [{:keys [session-service session agent invocation-id user-content
           run-config branch llm-call-count]
    :or {invocation-id (str (random-uuid))
         run-config default-run-config
         llm-call-count (atom 0)}}]
  {:session-service session-service
   :session (if (instance? clojure.lang.Atom session) session (atom (or session {})))
   :agent agent
   :invocation-id invocation-id
   :user-content user-content
   :run-config run-config
   :branch branch
   ;; Contador compartilhado entre todos os agentes/sub-agentes da mesma
   ;; invocação, para impor run-config.max-llm-calls de ponta a ponta —
   ;; igual ao InvocationCostManager do adk-java/adk-python.
   :llm-call-count llm-call-count})

(defn append-event!
  "Anexa `ev` ao atom de sessão vivo desta invocação — chamado pelos pontos
  que de fato produzem eventos (`adk.flows`, `adk.agents`), nunca por quem
  só os repassa (Sequential/Parallel/LoopAgent).

  Também aplica `event.actions.state-delta` ao `:state` do atom (merge raso,
  sem distinguir app:/user:/temp: — essa distinção só importa para o store
  durável entre invocações, roteada por `adk.sessions`; aqui é só a vista de
  trabalho corrente, e todo mundo que compartilha este atom — outro
  sub-agente de um SequentialAgent, outro branch de um ParallelAgent, o
  próximo passo do mesmo flow loop — precisa enxergar a escrita
  imediatamente, inclusive chaves temp:)."
  [ctx ev]
  (swap! (:session ctx)
         (fn [sess]
           (-> sess
               (update :events (fnil conj []) ev)
               (update :state merge (get-in ev [:actions :state-delta])))))
  ev)

(defn current-session
  "Vista corrente (snapshot) da sessão viva desta invocação."
  [ctx]
  @(:session ctx))

(defn child-context
  "Deriva um InvocationContext filho para um sub-agente, opcionalmente
  trocando o agente corrente e/ou aninhando o branch (usado por
  ParallelAgent para isolar o histórico de cada branch)."
  [ctx {:keys [agent branch-segment]}]
  (cond-> ctx
    agent (assoc :agent agent)
    branch-segment (update :branch
                            (fn [b] (if b (str b "." branch-segment) branch-segment)))))

(defn inc-llm-call-count!
  "Incrementa o contador de chamadas ao LLM desta invocação e lança se
  `run-config.max-llm-calls` for excedido."
  [ctx]
  (let [n (swap! (:llm-call-count ctx) inc)
        limit (get-in ctx [:run-config :max-llm-calls])]
    (when (and limit (> n limit))
      (throw (ex-info "max-llm-calls excedido para esta invocação"
                       {:type ::max-llm-calls-exceeded :limit limit})))
    n))

(defn events-on-branch
  "Filtra os eventos da sessão visíveis para o branch corrente: um branch
  \"a.b\" vê os eventos sem branch (do agente raiz/antes de qualquer
  ramificação) e os eventos cujo branch é exatamente \"a.b\" ou um
  ancestral dele (\"a\"), mas não os de branches irmãos (\"a.c\").
  Espelha InvocationContext.eventsOnCurrentBranch do adk-java."
  [ctx]
  (let [branch (:branch ctx)
        events (:events @(:session ctx) [])]
    (if (nil? branch)
      events
      (filter (fn [ev]
                (let [eb (:branch ev)]
                  (or (nil? eb)
                      (= eb branch)
                      (clojure.string/starts-with? branch (str eb ".")))))
              events))))

;; ---------------------------------------------------------------------------
;; ToolContext — passado para cada invocação de Tool. Acumula as actions
;; (state-delta/escalate/transfer-to-agent/...) que serão mescladas no Event
;; de function-response correspondente.
;; ---------------------------------------------------------------------------

(defn tool-context
  [invocation-ctx {:keys [function-call-id]}]
  {:invocation-context invocation-ctx
   :function-call-id function-call-id
   :actions (atom {})})

(defn set-state!
  "Agenda uma escrita de estado (chave já com prefixo app:/user:/temp: se
  aplicável) a ser aplicada quando o Event desta tool call for persistido."
  [tc k v]
  (swap! (:actions tc) assoc-in [:state-delta k] v)
  v)

(defn escalate!
  "Sinaliza EventActions.escalate=true — usado por ExitLoopTool para
  encerrar um LoopAgent."
  [tc]
  (swap! (:actions tc) assoc :escalate true)
  nil)

(defn request-transfer!
  [tc agent-name]
  (swap! (:actions tc) assoc :transfer-to-agent agent-name)
  nil)

(defn tool-actions
  "Lê as actions acumuladas neste ToolContext (map parcial de EventActions,
  sem defaults — combine com `adk.events/event-actions` antes de anexar a
  um Event)."
  [tc]
  @(:actions tc))
