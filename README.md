# adk-clojure

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Made with Clojure](https://img.shields.io/badge/made%20with-Clojure-5881D8.svg)](https://clojure.org)
[![Last commit](https://img.shields.io/github/last-commit/iamgabs/adk-clojure.svg)](https://github.com/iamgabs/adk-clojure/commits/main)

<h2 align="center">
  <img src="https://raw.githubusercontent.com/google/adk-python/main/assets/agent-development-kit.png" width="256"/>
</h2>
<p align="center">
  <sub>ADK logo &copy; Google, from
  <a href="https://github.com/google/adk-python">google/adk-python</a> —
  shown here only to identify the upstream project this is a port of.</sub>
</p>

<h3 align="center">
  A native, idiomatic Clojure port of the execution core of the
  <a href="https://google.github.io/adk-docs/">Google Agent Development Kit (ADK)</a>.
</h3>
<h3 align="center">
  ADK implementations:
  <a href="https://github.com/google/adk-python">Python</a> (reference),
  <a href="https://github.com/google/adk-java">Java</a>,
  <a href="https://github.com/google/adk-kotlin">Kotlin</a>,
  <a href="https://github.com/google/adk-go">Go</a>,
  <a href="https://github.com/google/adk-js">TypeScript</a> &
  <b>Clojure</b> (this repo, unofficial).
</h3>

> **Disclaimer:** this is an independent community project — **unofficial
> and not affiliated with Google**. "ADK" / "Agent Development Kit" are
> names of Google's original product; this repository only reimplements
> the same concepts and behavior in Clojure, without using any of Google's
> source code or branding.

`adk-clojure` faithfully reproduces ADK's classic agent architecture — the
same one used by the Python and Java implementations — using idiomatic
Clojure data and idioms (maps, protocols, atoms, lazy sequences) instead of
builders, AutoValue, and RxJava. It is optimized for Gemini, following
upstream ADK.

## ✨ Key Features

- **Agent hierarchy** — `LlmAgent`, `SequentialAgent`, `ParallelAgent`,
  `LoopAgent`: compose multiple specialized agents into flexible
  hierarchies, the same way upstream ADK does (this port doesn't implement
  adk-python's newer graph-based `Workflow` engine — see "Known
  limitations" below).
- **Runner** — wires an agent to a session service and drives one
  invocation, producing a lazy sequence of `Event`s you consume as they're
  produced.
- **Session / State** — in-memory state with the same scoping prefixes as
  upstream ADK: `app:`, `user:`, `temp:`.
- **Events / EventActions** — the same "state is a fold over events" model
  as upstream ADK; agent-to-agent control transfer via `transfer_to_agent`.
- **Code-first tools** — any Clojure function becomes a tool via metadata
  (`FunctionTool`), or wrap a whole agent as a tool for another agent
  (`AgentTool`).
- **Built-in grounding tools** — `adk.tools/GroundingTool` for tools the
  model resolves server-side (no local invocation): `google-search`,
  `url-context`, `code-execution`, `vertex-ai-search`, and
  `vertex-ai-rag-retrieval`.
- **MCP toolset** — `adk.tools.mcp` connects to an MCP server over stdio
  (via the official `io.modelcontextprotocol.sdk` Java SDK) and wraps its
  tools as `Tool`s. An optional dependency — see "MCP toolset" below.
- **OpenAPI toolset** — `adk.tools.openapi` turns an OpenAPI 3.x spec into
  one real-HTTP-calling tool per operation. Also optional — see "OpenAPI
  toolset" below.
- **Web console** — `adk.web`, a minimal equivalent of `adk web`: a
  one-page browser chat console (over Server-Sent Events) to try an agent
  interactively. Optional — see "Web console" below.
- **Gemini out of the box** — an `Llm` protocol plus a Gemini
  implementation on top of the official `com.google.genai:google-genai`
  SDK, registered automatically.
- **`.env` support** — `adk.env/load-env!` loads a `.env` file (the same
  `KEY=VALUE` convention `python-dotenv` uses in adk-python), so
  `GOOGLE_API_KEY` doesn't require a manual shell `export`.
- **Scaffolding** — `adk.cli.create`, a minimal equivalent of `adk create`,
  generates a new agent project from a template.

## 🚀 Installation

The library isn't published on Clojars yet (no `pip install` equivalent
across machines yet). Two ways to depend on it from a `deps.edn` project:

**Git dependency** (works from any machine, no local checkout needed):

```clojure
{:deps {io.github.iamgabs/adk-clojure
        {:git/tag "main" :git/sha "5db03224b8c324cf769a4b862b38c4d69367d58c"}}}
```

Pin `:git/sha` to whatever commit (or future tag) you want to track — see
[releases/commits](https://github.com/iamgabs/adk-clojure/commits/main) for
the latest.

**Local Maven install** (if you have this repo checked out):

```bash
git clone https://github.com/iamgabs/adk-clojure.git
cd adk-clojure
clj -T:build install   # installs the jar into ~/.m2 on this machine
```

Then, from *any* project on that machine:

```clojure
{:deps {io.github.iamgabs/adk-clojure {:mvn/version "0.1.0-SNAPSHOT"}}}
```

**Requirements:** JDK 17+ and the
[Clojure CLI](https://clojure.org/guides/install_clojure).

## Quick Start

### Agent

```clojure
(require '[adk.agents.llm-agent :as llm-agent])

(def root-agent
  (llm-agent/llm-agent
    {:name "greeting-agent"
     :model "gemini-2.0-flash"
     :instruction "You are a helpful assistant. Greet the user warmly."}))
```

### Composing agents

adk-clojure ports upstream ADK's classic agent-hierarchy composition
primitives — not adk-python 2.0's newer declarative graph `Workflow` DSL.
Multi-agent orchestration is done by nesting `SequentialAgent` /
`ParallelAgent` / `LoopAgent`:

```clojure
(require '[adk.agents.llm-agent :as llm-agent]
         '[adk.agents.sequential-agent :as sequential-agent])

(def extractor
  (llm-agent/llm-agent {:name "extractor" :model "gemini-2.0-flash"
                         :instruction "Extract the user's first name. Reply with just the name."
                         :output-key "user-name"}))

(def greeter
  (llm-agent/llm-agent {:name "greeter" :model "gemini-2.0-flash"
                         :instruction "Greet the person named in session state[\"user-name\"] warmly, in one sentence."}))

(def root-agent
  (sequential-agent/sequential-agent {:name "pipeline" :sub-agents [extractor greeter]}))
```

### Run it

```clojure
(require '[adk.env :as env]
         '[adk.runner :as runner]
         '[adk.events :as events])

(env/load-env!)  ;; loads GOOGLE_API_KEY from ./.env, if present

(def rnr (runner/in-memory-runner root-agent))

(doseq [ev (runner/run rnr {:user-id "u1" :session-id "s1"
                             :new-message {:role "user" :parts [{:text "Hi!"}]}})]
  (println (:author ev) "->" (events/text ev)))
```

Requires `GOOGLE_API_KEY` — either in a `.env` file (copy `.env.example`)
or as a real environment variable, which always takes precedence (or
Vertex AI configuration — see `adk.models.gemini/make-client`). See
`examples/two_agent_pipeline.clj` for the full runnable version of the
pipeline above.

### Scaffold a new agent project

A minimal equivalent of `adk create <app_name>`:

```bash
# from inside this adk-clojure checkout:
clj -M -m adk.cli.create my-agent [target-dir]
```

Generates a new project with a minimal `LlmAgent`, a `deps.edn`, a
`README.md`, `.gitignore`, and `.env.example`. The generated `agent.clj`
already calls `(adk.env/load-env!)`, so copying `.env.example` to `.env`
and filling in `GOOGLE_API_KEY` is enough — no `export` needed. (This is
just file scaffolding — no `adk run`/`adk eval` equivalent; see "Web
console" below for the minimal `adk web` equivalent.) See the generated
project's own `README.md` for how to run it.

### MCP toolset

`adk.tools.mcp` is an **optional** integration — the MCP SDK isn't in
adk-clojure's own `:deps` (to keep the core small), so add it to *your*
`deps.edn`:

```clojure
{:deps {io.modelcontextprotocol.sdk/mcp-core {:mvn/version "2.1.0"}
        io.modelcontextprotocol.sdk/mcp-json-jackson2 {:mvn/version "2.1.0"}}}
```

Then connect to an MCP server over stdio and use its tools like any other:

```clojure
(require '[adk.tools.mcp :as mcp])

(def client (mcp/connect! {:command "npx"
                            :args ["-y" "@modelcontextprotocol/server-everything"]}))
(def mcp-tools (mcp/mcp-tools client))  ;; -> vector of adk.tools/Tool

(llm-agent/llm-agent {:name "agent" :model "gemini-2.0-flash" :tools mcp-tools})

;; when done:
(mcp/disconnect! client)
```

Only stdio transport (spawns a local server process) for now, and
`mcp-tools` connects + lists the server's tools **once**, as a static
vector — it isn't a dynamic toolset that reacts to the server's tool list
changing later. See "Known limitations" below.

### OpenAPI toolset

`adk.tools.openapi` is also an **optional** integration — add to *your*
`deps.edn`:

```clojure
{:deps {org.clojure/data.json {:mvn/version "2.5.1"}}}
```

(the HTTP client itself is `java.net.http.HttpClient`, already built into
the JDK — only the JSON parser/encoder is an extra dependency). Point it at
an already-parsed OpenAPI 3.x spec (a Clojure map) and it generates one
tool per operation, each making a real HTTP call when invoked:

```clojure
(require '[adk.tools.openapi :as openapi])

(def pet-tools (openapi/openapi-tools spec {:base-url "https://api.example.com"
                                             :auth {:type :bearer :token "..."}}))

(llm-agent/llm-agent {:name "agent" :model "gemini-2.0-flash" :tools pet-tools})
```

Supports path/query/header parameters, JSON request/response bodies, local
`$ref` resolution (within the same spec), and either bearer-token or
static API-key auth. No YAML parsing (bring your own parser and pass a
map), no multipart/form-data bodies, no OAuth2 flows — see "Known
limitations" below.

### Web console

`adk.web` is also an **optional** integration — add to *your* `deps.edn`:

```clojure
{:deps {ring/ring-core {:mvn/version "1.15.6"}
        ring/ring-jetty-adapter {:mvn/version "1.15.6"}
        org.clojure/data.json {:mvn/version "2.5.1"}}}
```

Then serve one or more agents (as `Runner`s) as a one-page browser chat
console:

```clojure
(require '[adk.web :as web])

(def server (web/start-server! {"my-agent" my-runner} {:port 8080}))
;; open http://localhost:8080
(web/stop-server! server)
```

See `examples/web_console.clj` for a full runnable version. Agent
responses stream to the page live via Server-Sent Events, and the session
id is preserved across messages so a conversation keeps its history. This
is a minimal testing console, **not** a port of the official dev UI — no
trace viewer, no eval authoring, no multi-agent directory scanning. See
"Known limitations" below.

## 📚 Documentation

There's no hosted docs site yet (unlike `adk.dev` for adk-python) — the
docs live in this repository:

- **This README** — features, installation, quick start.
- [`examples/`](examples/) — runnable example agents.
- [`CHANGELOG.md`](CHANGELOG.md) — what's implemented, and known
  limitations in detail.
- Source docstrings — every namespace under [`src/adk/`](src/adk/) has a
  docstring explaining what it ports from adk-java/adk-python and why.

## 🧪 Development

```bash
clj -X:test              # run the test suite (excludes ^:integration tests)
clj -X:test-integration  # also run tests that call the real Gemini API
                          # (requires GOOGLE_API_KEY in the environment)
clj -T:build jar          # build the jar into target/
clj -T:build install      # install the jar into the local Maven repo (~/.m2)
```

The test suite runs entirely offline against a scripted `MockLlm` — no
network calls, no API key needed, for everything except the explicitly
`^:integration`-tagged Gemini tests.

## 🤝 Contributing

This is a young, unofficial port — issues and pull requests are welcome,
especially around the known limitations listed below.
There's no formal contribution guide yet; opening an issue to discuss a
change before a larger PR is appreciated.

## 📌 Known limitations (v0.1)

- No graph-based `Workflow` engine (adk-python 2.0's newer DSL) — only the
  classic `Sequential`/`Parallel`/`LoopAgent` composition primitives.
- `instructions` doesn't walk the ancestor agent chain — `global-instruction`
  only applies to the current agent, it isn't inherited by sub-agents.
- `long-running?` tools still execute synchronously (no "return now, resolve
  later" protocol yet).
- Streaming + fragmented function-calling doesn't merge partial arguments
  across chunks (only text gets aggregated).
- `transfer_to_agent` is only resolved by the Runner walking the whole
  agent tree; a transfer in the middle of a running
  Sequential/Parallel/LoopAgent doesn't interrupt that composition.
- No `adk run` (interactive CLI)/`adk eval`/`adk deploy` equivalent, and
  `adk.web` is a minimal testing console, not a port of the official dev
  UI — no trace viewer, no eval authoring, no scanning a directory of
  agent modules (you register `{app-name Runner}` in code).
- `adk.tools.mcp` only supports stdio transport (no SSE/Streamable HTTP
  yet) and is a static, one-time tool list — not a dynamic toolset that
  reacts to the server's tools changing after connection.
- `adk.tools.openapi` only supports JSON bodies (no multipart/form-data),
  a single server/base URL, local `$ref` resolution (no external refs),
  and static bearer/API-key auth (no OAuth2 flows). No YAML spec parsing.
- Out of scope for now: persistent memory/artifact services, planners,
  local code executors (sandboxed, non-Gemini-native), local/custom
  retrieval (files, LlamaIndex), the evaluation framework, the auth
  framework, telemetry, and model providers other than Gemini.

## 📄 License

Apache License 2.0 — see [LICENSE](LICENSE).

______________________________________________________________________

*Happy agent building, in Clojure!*
