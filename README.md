# adk-clojure

Um port **nativo, idiomático em Clojure** do núcleo de execução do
[Google Agent Development Kit (ADK)](https://google.github.io/adk-docs/),
cujas implementações oficiais são [`adk-python`](https://github.com/google/adk-python)
(referência) e [`adk-java`](https://github.com/google/adk-java).

> **Aviso:** este é um projeto comunitário independente, **não-oficial e não
> afiliado ao Google**. "ADK" / "Agent Development Kit" são nomes do produto
> original do Google; este repositório apenas reimplementa os mesmos
> conceitos e comportamentos em Clojure, sem usar código-fonte do Google.

## O que é

`adk-clojure` reproduz fielmente a arquitetura clássica do ADK — a mesma
usada pelas versões Python e Java — com dados e idiomas nativos de Clojure
(maps, protocolos, atoms, sequências preguiçosas) no lugar de builders,
AutoValue e RxJava:

- **Agents**: `LlmAgent`, `SequentialAgent`, `ParallelAgent`, `LoopAgent`.
- **Runner**: liga um agente a um serviço de sessão e executa uma invocação,
  produzindo uma sequência preguiçosa de `Event`s.
- **Session / State**: estado em memória com os mesmos prefixos de escopo do
  ADK original — `app:`, `user:`, `temp:`.
- **Events / EventActions**: o mesmo modelo de "estado é um fold sobre os
  eventos" do ADK original.
- **Tools**: `FunctionTool` (qualquer função Clojure vira uma tool via
  metadata), `AgentTool` (um agente como tool de outro agente).
- **Models**: protocolo `Llm` + implementação Gemini sobre o SDK oficial
  `com.google.genai:google-genai`.
- **Flows**: `SingleFlow` e `AutoFlow` (com transferência de controle entre
  agentes), seguindo o mesmo pipeline de request/response processors e
  callbacks `before`/`after` em agent/model/tool.

Escopo da v0.1: o núcleo de execução acima, mais um scaffolding mínimo
(`adk.cli.create`, equivalente a `adk create`). Ver `CHANGELOG.md`/issues
para o que ainda não foi portado — memory/artifact service persistente,
planners, code executors, toolsets MCP/OpenAPI, avaliação, auth, telemetry,
`adk web`/`adk api_server`/`adk eval` (servidor + UI de dev) e outros
provedores de modelo além do Gemini ficam fora desta primeira versão.

## Criar um novo agente

Equivalente a `adk create <app_name>` do adk-python/adk-java: gera um
projeto novo com um `LlmAgent` mínimo, `deps.edn` já apontando para esta
checkout do adk-clojure, `README.md` e `.env.example`.

```bash
# de dentro desta checkout do adk-clojure:
clj -M -m adk.cli.create meu-agente [diretorio-destino]
```

Veja o `README.md` gerado dentro do novo projeto para como rodá-lo. (Ainda
não há `adk run`/`adk web`/`adk eval` — só o scaffolding de arquivos.)

## Uso

```clojure
;; deps.edn
{:deps {io.github.iamgabs/adk-clojure {:git/tag "v0.1.0" :git/sha "..."}}}
```

```clojure
(require '[adk.agents.llm-agent :as llm-agent]
         '[adk.runner :as runner]
         '[adk.events :as events])

(def agent
  (llm-agent/llm-agent {:name "assistant" :model "gemini-2.0-flash"
                         :instruction "Responda de forma breve e direta."}))

(def rnr (runner/in-memory-runner agent))

(doseq [ev (runner/run rnr {:user-id "u1" :session-id "s1"
                             :new-message {:role "user" :parts [{:text "Oi!"}]}})]
  (println (:author ev) "->" (events/text ev)))
```

Requer `GOOGLE_API_KEY` no ambiente (ou configuração Vertex AI — veja
`adk.models.gemini/make-client`). Veja `examples/two_agent_pipeline.clj`
para um pipeline de dois agentes (Sequential + propagação de estado).

## Desenvolvimento

Requer JDK 17+ e [Clojure CLI](https://clojure.org/guides/install_clojure).

```bash
clj -X:test          # roda a suíte de testes (exclui testes ^:integration)
clj -X:test-integration  # inclui testes que chamam a API real do Gemini
                          # (exige GOOGLE_API_KEY no ambiente)
clj -T:build jar      # empacota o jar em target/
```

## Licença

Apache License 2.0 — veja [LICENSE](LICENSE).
