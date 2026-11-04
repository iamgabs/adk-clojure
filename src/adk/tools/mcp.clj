(ns adk.tools.mcp
  "Integração com MCP (Model Context Protocol) via o SDK oficial Java
  (`io.modelcontextprotocol.sdk:mcp-core` + `mcp-json-jackson2` — o mesmo
  SDK que o adk-java usa para `tools/mcp/McpToolset.java`). Espelha
  `tools/mcp/mcp_toolset.py` (adk-python) na parte que importa: listar as
  tools de um servidor MCP e envolvê-las como `adk.tools/Tool`.

  DEPENDÊNCIA OPCIONAL: ao contrário de `com.google.genai` (sempre presente
  em `:deps`, é o provider default), o SDK do MCP NÃO está em `:deps` do
  adk-clojure — para usar este namespace, adicione ao SEU deps.edn:

    io.modelcontextprotocol.sdk/mcp-core {:mvn/version \"2.1.0\"}
    io.modelcontextprotocol.sdk/mcp-json-jackson2 {:mvn/version \"2.1.0\"}

  (usamos `mcp-core` + `mcp-json-jackson2`, não o aggregator `mcp`, pra
  reaproveitar o Jackson 2.x que o `google-genai` já traz, em vez de puxar
  uma segunda pilha Jackson 3 — ver `mcp-json-jackson3` se preferir o
  default dos exemplos oficiais).

  Uso:
    (require '[adk.tools.mcp :as mcp])
    (def client (mcp/connect! {:command \"npx\"
                                :args [\"-y\" \"@modelcontextprotocol/server-everything\"]}))
    (def tools (mcp/mcp-tools client))
    (llm-agent/llm-agent {:name \"a\" :model \"gemini-2.0-flash\" :tools tools})
    ;; ... quando terminar:
    (mcp/disconnect! client)

  MODELO DE USO (v0.1, LIMITAÇÃO CONHECIDA): `mcp-tools` conecta e lista as
  tools do servidor UMA VEZ, devolvendo um vetor ESTÁTICO de `Tool` — não é
  um `BaseToolset` dinâmico como no ADK original (que resolve a lista de
  tools por invocação e reage a `tools/list_changed`). Se o servidor mudar
  sua lista de tools depois de `mcp-tools` ter sido chamado, isso não é
  percebido automaticamente; reconexão/refresh fica para uma versão futura.

  TRANSPORTE: só stdio (spawna um processo MCP local) na v0.1 — SSE/
  Streamable HTTP (`HttpClientStreamableHttpTransport` no SDK) ficam de
  fora por ora, mas a única mudança necessária seria trocar a construção
  do transport em `connect!`; o resto (listar/envolver/chamar tools)
  independe do transporte."
  (:require [clojure.walk :as walk]
            [adk.tools :as tools])
  (:import [io.modelcontextprotocol.client McpClient McpSyncClient]
           [io.modelcontextprotocol.client.transport ServerParameters StdioClientTransport]
           [io.modelcontextprotocol.json McpJsonDefaults]
           [io.modelcontextprotocol.spec McpSchema$CallToolRequest McpSchema$CallToolResult
            McpSchema$ClientCapabilities McpSchema$TextContent McpSchema$Tool]))

(defn- java->clj
  "Converte recursivamente java.util.Map/List (o inputSchema de uma tool
  MCP vem assim, do parse de JSON) para maps/vetores Clojure imutáveis."
  [x]
  (cond
    (instance? java.util.Map x) (into {} (map (fn [[k v]] [(java->clj k) (java->clj v)])) x)
    (instance? java.util.List x) (mapv java->clj x)
    :else x))

(defn- mcp-tool->json-schema
  "O inputSchema de uma McpSchema.Tool já vem JSON-Schema-shaped (chaves
  string: \"type\"/\"properties\"/\"required\"/...). Convertido pra Clojure e
  com as chaves tornadas keyword, fica exatamente no formato esperado por
  `adk.tools/tool-declaration` / `adk.genai.schema/map->schema`."
  [input-schema]
  (walk/keywordize-keys (java->clj (or input-schema {}))))

(defn- content->text
  "Concatena as partes de texto de um CallToolResult — outros tipos de
  content (imagem, recurso embutido, áudio) são ignorados por ora."
  [^McpSchema$CallToolResult result]
  (->> (.content result)
       (keep #(when (instance? McpSchema$TextContent %) (.text ^McpSchema$TextContent %)))
       (apply str)))

(defrecord McpTool [^McpSyncClient client mcp-name description json-schema]
  tools/Tool
  (tool-name [_] mcp-name)
  (tool-description [_] description)
  (tool-declaration [_] {:name mcp-name :description description :parameters json-schema})
  (long-running? [_] false)
  (invoke-tool [_ args _tool-context]
    ;; `args` já é um map string->valor (vindo do parse da function-call do
    ;; LLM) — Map<String,Object> é exatamente o que CallToolRequest espera,
    ;; sem conversão.
    (let [result (.callTool client (McpSchema$CallToolRequest. mcp-name args nil))
          text (content->text result)]
      (if (true? (.isError result))
        {:error text}
        {:result text}))))

(defn- wrap-mcp-tool [client ^McpSchema$Tool t]
  (->McpTool client (.name t) (or (.description t) "") (mcp-tool->json-schema (.inputSchema t))))

(defn connect!
  "Conecta a um servidor MCP via stdio, spawnando `:command` (+ `:args`)
  como subprocesso, e completa o handshake MCP (`initialize`). opts:
    :command (obrigatório) — o executável a rodar (ex: \"npx\").
    :args (vetor de strings, default []).
    :env (map string->string; MESCLADO com o ambiente default e seguro do
          SDK — PATH/HOME/etc — não herda o ambiente inteiro do processo
          pai; se o servidor precisa de uma API key, passe-a aqui
          explicitamente).

  Devolve o `McpSyncClient` já inicializado — passe pra `mcp-tools` e,
  quando terminar, pra `disconnect!`."
  ^McpSyncClient [{:keys [command args env] :or {args []}}]
  (when-not command
    (throw (ex-info "adk.tools.mcp/connect! exige :command" {:args args :env env})))
  (let [b (ServerParameters/builder command)
        b (if (seq args) (.args b ^java.util.List (vec args)) b)
        b (if (seq env) (.env b ^java.util.Map env) b)
        params (.build b)
        transport (StdioClientTransport. params (McpJsonDefaults/getMapper))
        client (-> (McpClient/sync transport)
                   (.capabilities (.build (McpSchema$ClientCapabilities/builder)))
                   .build)]
    (.initialize client)
    client))

(defn mcp-tools
  "Lista (paginando até esgotar `nextCursor`) e envolve todas as tools do
  servidor MCP conectado como implementações de `adk.tools/Tool`. Ver
  LIMITAÇÃO CONHECIDA na docstring do namespace: isto conecta e lista uma
  única vez, não é um toolset dinâmico."
  [^McpSyncClient client]
  (loop [cursor nil acc []]
    (let [result (if cursor (.listTools client cursor) (.listTools client))
          acc (into acc (map #(wrap-mcp-tool client %)) (.tools result))
          next-cursor (.nextCursor result)]
      (if next-cursor
        (recur next-cursor acc)
        acc))))

(defn disconnect!
  "Fecha a conexão MCP — no caso do transporte stdio, isso encerra o
  subprocesso do servidor."
  [^McpSyncClient client]
  (.close client)
  nil)
