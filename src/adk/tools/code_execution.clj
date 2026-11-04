(ns adk.tools.code-execution
  "O código-execução NATIVO do Gemini — espelha `tools/
  BuiltInCodeExecutionTool.java` (adk-java) / o tool-level code execution
  habilitado por `code_executors/built_in_code_executor.py` (adk-python).
  O modelo escreve e roda o código do lado do servidor; não há sandbox
  local nem `adk.tools/invoke-tool` de verdade aqui.

  NOTA: isto NÃO é um executor de código local/sandboxado (equivalente a
  `UnsafeLocalCodeExecutor`/`ContainerCodeExecutor` do ADK original) — esse
  tipo de executor pluggável (`BaseCodeExecutor`) continua fora do escopo
  da v0.1 (ver CHANGELOG). Isto é só o Tool que liga a capacidade nativa de
  execução de código do próprio Gemini."
  (:require [adk.tools :as tools]))

(defrecord CodeExecutionTool []
  tools/Tool
  (tool-name [_] "code_execution")
  (tool-description [_] "Escreve e executa código Python para resolver tarefas (execução nativa do Gemini).")
  (tool-declaration [_] nil)
  (long-running? [_] false)
  (invoke-tool [_ _args _tool-context]
    (throw (ex-info "code_execution é executada pelo próprio Gemini (nativa); nunca deveria ser invocada via function-calling."
                     {:type ::not-invokable})))

  tools/GroundingTool
  (grounding-spec [_] {:code-execution {}}))

(def code-execution-tool (->CodeExecutionTool))
