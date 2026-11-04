(ns adk.events
  "Event / EventActions — o modelo de dados central do ADK: cada passo de
  execução produz um Event, e o estado da sessão é sempre um fold sobre a
  lista de Events (nunca mutado diretamente por agents/tools).

  Espelha `events/Event.java` + `events/EventActions.java` (adk-java) e
  `events/event.py` + `events/event_actions.py` (adk-python).")

;; ---------------------------------------------------------------------------
;; EventActions — o \"diff\" que um Event aplica à sessão / ao runtime.
;; ---------------------------------------------------------------------------

(defn event-actions
  "Constrói um map de EventActions. Todas as chaves são opcionais;
  `state-delta`/`artifact-delta` default para {}, `escalate`/
  `skip-summarization` default para false."
  ([] (event-actions {}))
  ([{:keys [state-delta artifact-delta transfer-to-agent escalate
            skip-summarization]
     :or {state-delta {} artifact-delta {} escalate false
          skip-summarization false}}]
   {:state-delta state-delta
    :artifact-delta artifact-delta
    :transfer-to-agent transfer-to-agent
    :escalate escalate
    :skip-summarization skip-summarization}))

(def empty-actions (event-actions))

(defn merge-actions
  "Combina duas EventActions (ex: resultados de múltiplas tool calls no
  mesmo passo) numa só — espelha EventActions.merge do adk-java. Em caso de
  conflito em transfer-to-agent, a última leitura (`b`) vence."
  [a b]
  {:state-delta (merge (:state-delta a) (:state-delta b))
   :artifact-delta (merge (:artifact-delta a) (:artifact-delta b))
   :transfer-to-agent (or (:transfer-to-agent b) (:transfer-to-agent a))
   :escalate (boolean (or (:escalate a) (:escalate b)))
   :skip-summarization (boolean (or (:skip-summarization a) (:skip-summarization b)))})

;; ---------------------------------------------------------------------------
;; Event
;; ---------------------------------------------------------------------------

(defn event
  "Constrói um Event. `author` é \"user\" ou o nome de um agente.
  `content` segue o shape de adk.genai.content (map {:role :parts})."
  [{:keys [invocation-id author content actions branch partial turn-complete
           long-running-tool-ids timestamp id]
    :or {actions empty-actions
         partial false
         turn-complete true
         long-running-tool-ids #{}
         timestamp (System/currentTimeMillis)
         id (str (random-uuid))}}]
  {:id id
   :invocation-id invocation-id
   :author author
   :content content
   :actions actions
   :branch branch
   :partial partial
   :turn-complete turn-complete
   :long-running-tool-ids long-running-tool-ids
   :timestamp timestamp})

(defn parts [ev]
  (get-in ev [:content :parts]))

(defn function-calls
  "Extrai as {:name :args} de todas as parts `:function-call` do evento."
  [ev]
  (keep :function-call (parts ev)))

(defn function-responses
  "Extrai as {:name :response} de todas as parts `:function-response`."
  [ev]
  (keep :function-response (parts ev)))

(defn pending-function-call?
  "Verdadeiro se o evento contém ao menos uma function-call (ainda não
  respondida) — i.e. é a resposta do modelo pedindo execução de tools."
  [ev]
  (boolean (seq (function-calls ev))))

(defn final-response?
  "Espelha Event.isFinalResponse()/is_final_response(): verdadeiro quando o
  evento não é um chunk parcial de streaming e não carrega uma function-call
  pendente (i.e. é a resposta definitiva de um passo de agente)."
  [ev]
  (and (not (:partial ev))
       (not (pending-function-call? ev))))

(defn text
  "Concatena todas as parts de texto do evento (conveniência para
  logging/testes — não substitui inspecionar `:content` diretamente)."
  [ev]
  (apply str (keep :text (parts ev))))
