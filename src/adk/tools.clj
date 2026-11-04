(ns adk.tools
  "Protocolo Tool + execução de tool calls a partir de um Event de resposta
  do modelo. Espelha `tools/BaseTool.java` (adk-java) e
  `tools/base_tool.py` + `flows/llm_flows/functions.py` (adk-python).

  O shape de `tool-declaration` é um JSON Schema simplificado, independente
  de qualquer SDK:
    {:name \"get_weather\" :description \"...\"
     :parameters {:type \"object\"
                  :properties {\"city\" {:type \"string\" :description \"...\"}}
                  :required [\"city\"]}}
  A conversão para o tipo `FunctionDeclaration` do SDK Gemini acontece só em
  `adk.genai.schema` (fronteira de interop), não aqui."
  (:require [adk.context :as context]
            [adk.events :as events]))

(defprotocol Tool
  (tool-name [t])
  (tool-description [t])
  (tool-declaration [t] "-> JSON Schema simplificado, ver docstring do ns.")
  (long-running? [t])
  (invoke-tool [t args tool-context]
    "args: map string->valor vindo do LLM. Pode retornar qualquer valor; se
    não for um map, é empacotado como {:result valor} antes de virar
    function-response."))

(defprotocol GroundingTool
  "Protocolo opcional para tools \"built-in\" que não são function-calling
  de verdade — ao invés de serem invocadas por nós, elas pedem pro próprio
  modelo fazer a ação do lado do servidor (ex: Google Search nativo do
  Gemini). Espelha o caso especial de `BaseTool.processLlmRequest` no
  adk-java/adk-python, quando uma tool sobrescreve esse método para mutar o
  request diretamente em vez de expor uma FunctionDeclaration.

  Uma tool que satisfaz `GroundingTool` ainda deve satisfazer `Tool`
  (para ter nome/descrição), mas sua `tool-declaration`/`invoke-tool` nunca
  são usadas — `grounding-spec` é o que de fato importa, e é consumido
  pelos request processors (`adk.flows.processors/tools-declaration`) e
  pela implementação de `Llm` (ex: `adk.models.gemini`), não pelo loop de
  execução de function-calls (`run-tool-calls`)."
  (grounding-spec [t]
    "-> map genérico descrevendo a configuração nativa a injetar no
    request, ex: {:google-search {}}. Cada chave é interpretada pela
    implementação de Llm correspondente; uma que não reconheça uma chave
    deve ignorá-la."))

(defn- first-truthy
  "Roda `fns` em ordem, passando `args`, e retorna o primeiro resultado
  truthy (ou nil se nenhuma produzir um). Espelha a semântica de lista de
  callbacks do adk-java/adk-python: a primeira que \"responde\" vence."
  [fns & args]
  (some #(apply % args) fns))

(defn- run-one-call
  [tools-by-name before-cbs after-cbs on-error-cbs invocation-ctx call-id {:keys [name args]}]
  (if-let [tool (get tools-by-name name)]
    (let [tc (context/tool-context invocation-ctx {:function-call-id call-id})
          override (first-truthy before-cbs tool args tc)
          result (try
                   (if (some? override)
                     override
                     (invoke-tool tool args tc))
                   (catch Throwable t
                     (let [handled (first-truthy on-error-cbs tool args tc t)]
                       (if (some? handled)
                         handled
                         {:error (.getMessage t)}))))
          final-result (if-let [overridden (first-truthy after-cbs tool args tc result)]
                         overridden
                         result)]
      {:name name
       :response (if (map? final-result) final-result {:result final-result})
       :actions (context/tool-actions tc)
       :long-running? (long-running? tool)})
    {:name name
     :response {:error (str "Tool desconhecida: " name)}
     :actions {}
     :long-running? false}))

(defn run-tool-calls
  "Executa todas as function-calls de `model-event` e produz uma seq com um
  único Event de function-response agregado — igual ao comportamento padrão
  (sequencial) do adk-java/adk-python quando várias tools são chamadas no
  mesmo passo.

  `tool-exec-config` é um map {:tools-by-name {nome -> impl de Tool, ...}
                                :before-tool-callbacks [...]
                                :after-tool-callbacks [...]
                                :on-tool-error-callbacks [...]}.

  IMPORTANTE: `tools-by-name` deve vir das tools efetivamente oferecidas ao
  modelo NESTE passo (o `:tools` do LlmRequest já resolvido pelos request
  processors), não apenas de `agent.tools` — processors como o
  `transfer-to-agent` do AutoFlow injetam tools sintéticas direto no
  request, que não existem em `agent.tools`.

  NOTA v0.1: tools marcadas `long-running?` ainda são executadas
  sincronamente aqui (o protocolo completo de \"retornar já, resolver
  depois\" do ADK original fica para uma versão futura); apenas o
  `:long-running-tool-ids` do Event é populado corretamente para já deixar
  o ponto de extensão pronto."
  [{:keys [tools-by-name before-tool-callbacks after-tool-callbacks on-tool-error-callbacks]
    :or {tools-by-name {} before-tool-callbacks [] after-tool-callbacks [] on-tool-error-callbacks []}}
   model-event ctx]
  (let [calls (events/function-calls model-event)
        call-id (fn [i] (str (:id model-event) "-fc" i))
        results (vec (map-indexed
                       (fn [i call]
                         (assoc (run-one-call tools-by-name before-tool-callbacks after-tool-callbacks
                                               on-tool-error-callbacks ctx (call-id i) call)
                                :call-id (call-id i)))
                       calls))
        combined-actions (reduce events/merge-actions events/empty-actions (map :actions results))
        long-running-ids (into #{} (keep #(when (:long-running? %) (:call-id %))) results)
        parts (mapv (fn [r] {:function-response {:name (:name r) :response (:response r)}}) results)]
    [(events/event {:invocation-id (:invocation-id ctx)
                     :author (:author model-event)
                     :content {:role "user" :parts parts}
                     :actions combined-actions
                     :branch (:branch ctx)
                     :long-running-tool-ids long-running-ids})]))

;; ---------------------------------------------------------------------------
;; ExitLoopTool — built-in necessária para LoopAgent: seta escalate=true no
;; ToolContext, o que corta a iteração corrente do LoopAgent.
;; ---------------------------------------------------------------------------

(defrecord ExitLoopTool []
  Tool
  (tool-name [_] "exit_loop")
  (tool-description [_] "Encerra o LoopAgent corrente nesta iteração.")
  (tool-declaration [_]
    {:name "exit_loop"
     :description "Encerra o LoopAgent corrente nesta iteração."
     :parameters {:type "object" :properties {} :required []}})
  (long-running? [_] false)
  (invoke-tool [_ _args tool-context]
    (context/escalate! tool-context)
    {:result "loop encerrado"}))

(def exit-loop-tool (->ExitLoopTool))
