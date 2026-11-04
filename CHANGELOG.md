# Changelog

## [Unreleased] — v0.1.0 (núcleo de execução)

Port nativo em Clojure do núcleo de execução do Google ADK (equivalente ao
módulo `core` do adk-java / à superfície estável do adk-python), com paridade
conceitual de comportamento, idiomático em Clojure (maps/records/protocolos/
atoms, lazy-seq + threads — sem RxJava, sem core.async).

### Added

- **Agents**: `LlmAgent`, `SequentialAgent`, `ParallelAgent`, `LoopAgent`,
  protocolo comum `adk.agents/Agent` com before/after-agent-callbacks.
- **Runner**: `adk.runner` + `in-memory-runner`, com resolução de
  `transfer_to_agent` (busca o agente-alvo em toda a árvore e continua a
  execução a partir dele).
- **Sessions/State**: `InMemorySessionService` com roteamento de prefixos
  `app:`/`user:`/`temp:`, e um atom "vivo" por invocação
  (`adk.context/append-event!`) para visibilidade de histórico/estado
  dentro do mesmo loop antes da persistência durável.
- **Events/EventActions**: modelo de dados completo (state-delta,
  artifact-delta, transfer-to-agent, escalate, skip-summarization),
  `final-response?`/`pending-function-call?`.
- **Tools**: protocolo `adk.tools/Tool`, `FunctionTool` (função Clojure vira
  tool via metadata `:adk/tool` + Malli, com injeção automática de
  ToolContext via `^:adk/tool-context`), `AgentTool` (agente como tool),
  `exit-loop-tool` built-in.
- **Grounding tools**: protocolo `adk.tools/GroundingTool` para tools
  "built-in" que não são function-calling de verdade (o modelo resolve do
  lado do servidor). `adk.flows.processors/tools-declaration` separa essas
  de tools comuns no `LlmRequest` (`:grounding-tools` vs `:tools`);
  `adk.models.gemini` converte cada spec reconhecida numa entrada `Tool`
  própria do SDK. Cinco implementadas:
  - `adk.tools.google-search/google-search-tool` — Google Search nativo.
  - `adk.tools.url-context/url-context-tool` — leitura de URLs mencionadas.
  - `adk.tools.code-execution/code-execution-tool` — execução de código
    nativa do Gemini (NÃO um executor local/sandboxado — isso continua
    fora de escopo).
  - `adk.tools.vertex-ai-search/vertex-ai-search-tool` — busca num
    datastore/engine do Vertex AI Search (exige `:datastore` ou `:engine`).
  - `adk.tools.vertex-ai-rag-retrieval/vertex-ai-rag-retrieval-tool` —
    retrieval contra corpora do Vertex AI RAG Engine (exige
    `:rag-corpora`).
- **MCP toolset**: `adk.tools.mcp` conecta a um servidor MCP via stdio
  usando o SDK Java oficial (`io.modelcontextprotocol.sdk`, o mesmo que o
  adk-java usa) e envolve cada tool do servidor como `adk.tools/Tool`.
  Dependência OPCIONAL (não entra em `:deps` do adk-clojure — adicione
  `mcp-core` + `mcp-json-jackson2` ao seu próprio projeto pra usar). Modelo
  de uso: `connect!` conecta e faz o handshake, `mcp-tools` lista e envolve
  as tools UMA VEZ (vetor estático, não um toolset dinâmico), `disconnect!`
  fecha a conexão/encerra o subprocesso. Só transporte stdio por ora.
- **OpenAPI toolset**: `adk.tools.openapi` gera uma `adk.tools/Tool` por
  operação de uma spec OpenAPI 3.x (map Clojure já parseado), cada uma
  fazendo uma chamada HTTP real via `java.net.http.HttpClient` (JDK, sem
  lib extra) quando invocada. Dependência OPCIONAL (`org.clojure/data.json`
  pro parse/encode JSON — não entra em `:deps`). Suporta parâmetros
  path/query/header, corpo JSON, resolução de `$ref` local (profundidade
  limitada), e auth estática (bearer token ou API-key em header/query).
  Testado contra um servidor HTTP real local (`com.sun.net.httpserver`,
  puro JDK) — round-trip completo de path param, query param, corpo com
  `$ref` resolvido, e auth aceita/rejeitada pelo servidor de verdade.
- **Models**: protocolo `adk.models/Llm`, registry regex→factory,
  `GeminiLlm` sobre o SDK oficial `com.google.genai` (não-streaming e
  streaming).
- **Flows**: `SingleFlow`/`AutoFlow`, pipeline de request processors
  (instructions/contents/tools-declaration[+transfer]), loop completo de
  function-calling com callbacks before/after/on-error em model e tool.
- **Interop**: `adk.genai.content` (Content/Part) e `adk.genai.schema`
  (JSON Schema → Schema/FunctionDeclaration) — únicos pontos que tocam
  tipos Java do SDK.
- **CLI de scaffolding**: `adk.cli.create` — equivalente mínimo a
  `adk create <app_name>`, gera um novo projeto de agente (LlmAgent +
  deps.edn com `:local/root` + README + .env.example + .gitignore) a
  partir de um template. Sem equivalente a `adk run`/`adk web`/`adk eval`
  ainda.
- **Suporte a `.env`**: `adk.env` carrega um arquivo `.env` (`CHAVE=valor`,
  o mesmo formato que `python-dotenv` usa no adk-python) para dentro do
  processo — `adk.models.gemini/make-client` consulta `adk.env/getenv`
  como fallback quando `:api-key`/`:vertex-ai?` não são dados
  explicitamente, então `GOOGLE_API_KEY` não precisa mais de `export`
  manual no shell. O template de `adk.cli.create` já chama
  `(adk.env/load-env!)` automaticamente.
- **Instalação local**: `clj -T:build install` instala o jar no repositório
  Maven local (`~/.m2`) desta máquina — qualquer projeto local passa a
  poder depender de adk-clojure via `{:mvn/version "0.1.0-SNAPSHOT"}`, sem
  `:local/root`. (Corrigido um bug onde `install` reusava um jar antigo em
  `target/` em vez de sempre reconstruir.)
- **Testes**: `MockLlm` + helpers (`simplify-events`), cobrindo os 9
  cenários críticos do plano de implementação (LlmAgent simples e com tool
  call, Sequential propagando estado, Loop saindo via escalate, Parallel
  com branches isolados e state last-write-wins, roteamento de
  app:/user:/temp:, AutoFlow/transfer_to_agent, round-trip de Content).

### Known limitations (v0.1)

- `instructions` não percorre a cadeia de agentes ancestrais
  (`global-instruction` só se aplica ao agente corrente, não é herdado por
  sub-agentes).
- Tools `long-running?` ainda executam sincronamente (sem o protocolo
  completo de "retornar já, resolver depois").
- Streaming + function-calling fragmentado entre chunks não faz merge fino
  de argumentos parciais (só agrega texto).
- `transfer_to_agent` só é resolvido a partir do Runner operando na árvore
  inteira; uma transferência no meio de um SequentialAgent/ParallelAgent/
  LoopAgent não interrompe a composição em andamento.
- `adk.tools.mcp` só suporta transporte stdio (sem SSE/Streamable HTTP
  ainda) e é uma lista estática de tools (conectada/listada uma única vez)
  — não reage a mudanças na lista de tools do servidor depois da conexão.
- `adk.tools.openapi` só suporta corpo JSON (sem multipart/form-data), um
  único servidor/base URL, resolução de `$ref` local (sem $ref externo), e
  auth estática bearer/API-key (sem fluxo OAuth2). Sem parser de YAML
  embutido.
- Fora de escopo (ver README): memory/artifact service persistente,
  planners, code executors locais, retrieval local/customizado (arquivos,
  LlamaIndex), evaluation framework, auth framework, telemetry, servidor/UI
  de dev (`adk web`/`adk api_server`/`adk eval`), outros provedores de
  modelo além do Gemini.
