# adk-clojure

[![License: Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

A **native, idiomatic Clojure** port of the execution core of the
[Google Agent Development Kit (ADK)](https://google.github.io/adk-docs/),
whose official implementations are [`adk-python`](https://github.com/google/adk-python)
(the reference implementation) and [`adk-java`](https://github.com/google/adk-java).

> **Disclaimer:** this is an independent community project — **unofficial
> and not affiliated with Google**. "ADK" / "Agent Development Kit" are
> names of Google's original product; this repository only reimplements
> the same concepts and behavior in Clojure, without using any of Google's
> source code.

## What this is

`adk-clojure` faithfully reproduces ADK's classic agent architecture — the
same one used by the Python and Java implementations — using idiomatic
Clojure data and idioms (maps, protocols, atoms, lazy sequences) instead of
builders, AutoValue, and RxJava:

- **Agents** — `LlmAgent`, `SequentialAgent`, `ParallelAgent`, `LoopAgent`.
- **Runner** — wires an agent to a session service and drives one
  invocation, producing a lazy sequence of `Event`s.
- **Session / State** — in-memory state with the same scoping prefixes as
  upstream ADK: `app:`, `user:`, `temp:`.
- **Events / EventActions** — the same "state is a fold over events" model
  as upstream ADK.
- **Tools** — `FunctionTool` (any Clojure function becomes a tool via
  metadata), `AgentTool` (an agent used as a tool by another agent).
- **Models** — an `Llm` protocol plus a Gemini implementation on top of the
  official `com.google.genai:google-genai` SDK.
- **`.env` support** — `adk.env/load-env!` loads a `.env` file (the same
  `KEY=VALUE` convention `python-dotenv` uses in adk-python), so
  `GOOGLE_API_KEY` doesn't require a manual shell `export`.
- **Flows** — `SingleFlow` and `AutoFlow` (with agent-to-agent control
  transfer), following the same request/response processor pipeline and
  `before`/`after` callbacks on agent/model/tool.

**v0.1 scope**: the execution core above, plus a minimal scaffolding
command (`adk.cli.create`, equivalent to `adk create`). See
`CHANGELOG.md`/issues for what hasn't been ported yet — persistent
memory/artifact services, planners, code executors, MCP/OpenAPI toolsets,
evaluation, auth, telemetry, `adk web`/`adk api_server`/`adk eval` (the dev
server + UI), and model providers other than Gemini are all out of scope
for this first release.

## Installation

The library isn't published on Clojars yet. In the meantime, there are two
ways to depend on it from a `deps.edn` project:

**Git dependency** (works from any machine, no local checkout needed):

```clojure
{:deps {io.github.iamgabs/adk-clojure
        {:git/tag "main" :git/sha "dcf8e33031cd35d387d61a92d26ed8090851d325"}}}
```

Pin `:git/sha` to whatever commit (or future tag) you want to track.

**Local Maven install** (if you have this repo checked out and want to
iterate on it locally):

```bash
git clone https://github.com/iamgabs/adk-clojure.git
cd adk-clojure
clj -T:build install   # installs the jar into ~/.m2 on this machine
```

Then, from *any* project on that machine:

```clojure
{:deps {io.github.iamgabs/adk-clojure {:mvn/version "0.1.0-SNAPSHOT"}}}
```

## Creating a new agent

Equivalent to `adk create <app_name>` in adk-python/adk-java: scaffolds a
new project with a minimal `LlmAgent`, a `deps.edn` pointing at this
adk-clojure checkout, a `README.md`, and a `.env.example`.

```bash
# from inside this adk-clojure checkout:
clj -M -m adk.cli.create my-agent [target-dir]
```

The generated `agent.clj` already calls `(adk.env/load-env!)`, so copying
`.env.example` to `.env` and filling in `GOOGLE_API_KEY` is enough — no
`export` needed. See the generated project's `README.md` for details.
(There's no `adk run`/`adk web`/`adk eval` yet — just file scaffolding.)

## Quick start

```clojure
(require '[adk.env :as env]
         '[adk.agents.llm-agent :as llm-agent]
         '[adk.runner :as runner]
         '[adk.events :as events])

(env/load-env!)  ;; loads GOOGLE_API_KEY from ./.env, if present

(def agent
  (llm-agent/llm-agent {:name "assistant" :model "gemini-2.0-flash"
                         :instruction "Answer briefly and directly."}))

(def rnr (runner/in-memory-runner agent))

(doseq [ev (runner/run rnr {:user-id "u1" :session-id "s1"
                             :new-message {:role "user" :parts [{:text "Hi!"}]}})]
  (println (:author ev) "->" (events/text ev)))
```

Requires `GOOGLE_API_KEY` — either in a `.env` file (copy `.env.example`)
or as a real environment variable, which always takes precedence (or Vertex
AI configuration — see `adk.models.gemini/make-client`). See
`examples/two_agent_pipeline.clj` for a two-agent pipeline (Sequential
composition + state propagation).

## Development

Requires JDK 17+ and the [Clojure CLI](https://clojure.org/guides/install_clojure).

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

## Known limitations (v0.1)

- `instructions` doesn't walk the ancestor agent chain — `global-instruction`
  only applies to the current agent, it isn't inherited by sub-agents.
- `long-running?` tools still execute synchronously (no "return now, resolve
  later" protocol yet).
- Streaming + fragmented function-calling doesn't merge partial arguments
  across chunks (only text gets aggregated).
- `transfer_to_agent` is only resolved by the Runner walking the whole
  agent tree; a transfer in the middle of a running
  Sequential/Parallel/LoopAgent doesn't interrupt that composition.
- Out of scope for now (see above): persistent memory/artifact services,
  planners, code executors, MCP/OpenAPI toolsets, the evaluation framework,
  the auth framework, telemetry, the dev server/UI (`adk web`/
  `adk api_server`/`adk eval`), and model providers other than Gemini.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
