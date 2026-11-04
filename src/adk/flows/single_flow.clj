(ns adk.flows.single-flow
  "SingleFlow: o agente usa só suas próprias tools, sem a possibilidade de
  transferir o controle para outro agente. Espelha `SingleFlow.java`/
  `single_flow.py`."
  (:require [adk.flows.processors :as p]))

(def request-processors [p/instructions p/contents p/tools-declaration])
(def response-processors [])

(def flow {:request-processors request-processors
           :response-processors response-processors})
