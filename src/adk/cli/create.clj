(ns adk.cli.create
  "Scaffolding de um novo projeto de agente a partir de um template mínimo —
  o equivalente do adk-clojure a `adk create <app_name>` (adk-python) /
  `adk create` (adk-java).

  Uso via linha de comando, de dentro desta checkout do adk-clojure:

    clj -M -m adk.cli.create meu-agente
    clj -M -m adk.cli.create meu-agente caminho/destino

  Ou programaticamente:

    (require '[adk.cli.create :as create])
    (create/create-agent! \"meu-agente\" {:dir \"caminho/destino\" :model \"gemini-2.0-flash\"})

  Gera:
    <dir>/deps.edn        — com :local/root apontando pra esta checkout do
                            adk-clojure (a biblioteca ainda não está
                            publicada em nenhum repositório Maven/Clojars;
                            quando estiver, troque por uma coordenada normal)
    <dir>/src/<ns>.clj    — um LlmAgent mínimo pronto pra editar, já
                            carregando .env via `adk.env/load-env!`
    <dir>/.env.example    — placeholder de GOOGLE_API_KEY (copie para .env)
    <dir>/.gitignore      — ignora .env/target/.cpcache
    <dir>/README.md       — como rodar

  NOTA v0.1: isto é só scaffolding de arquivos — não há equivalente a
  `adk run`/`adk web`/`adk eval` ainda (ver limitações conhecidas no
  CHANGELOG do adk-clojure)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- slug
  "Normaliza `s` para um slug kebab-case seguro para nome de agente/projeto
  (ex: \"Meu Agente!\" -> \"meu-agente\")."
  [s]
  (-> s
      str/lower-case
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"^-+|-+$" "")))

(defn- detect-adk-clojure-root
  "Localiza esta checkout do adk-clojure via classpath (procurando por
  src/adk/agents.clj), em vez de assumir que o comando é rodado a partir do
  diretório corrente — mais robusto para uso em CI/scripts."
  []
  (if-let [url (io/resource "adk/agents.clj")]
    (-> (.getPath url)
        (str/replace #"/src/adk/agents\.clj$" "")
        io/file
        .getCanonicalPath)
    (System/getProperty "user.dir")))

(defn- ns->path [ns-sym]
  (str (-> (name ns-sym) (str/replace "." "/") (str/replace "-" "_")) ".clj"))

(defn- deps-edn-content [adk-clojure-root]
  (format
    "{:paths [\"src\"]\n :deps {org.clojure/clojure {:mvn/version \"1.12.0\"}\n        io.github.iamgabs/adk-clojure {:local/root \"%s\"}}}\n"
    adk-clojure-root))

(defn- agent-clj-content [agent-ns agent-slug model instruction]
  (format
    (str "(ns %s\n"
         "  \"Agente gerado por `adk.cli.create` — edite :instruction/:model/:tools\n"
         "  conforme precisar. `root-agent` é o que você passa pro Runner.\"\n"
         "  (:require [adk.env :as env]\n"
         "            [adk.agents.llm-agent :as llm-agent]))\n\n"
         ";; Carrega ./.env (GOOGLE_API_KEY etc), se existir — veja .env.example.\n"
         ";; Não precisa de `export` manual no shell; uma variável de ambiente real\n"
         ";; sempre tem prioridade sobre o que estiver no .env.\n"
         "(env/load-env!)\n\n"
         "(def root-agent\n"
         "  (llm-agent/llm-agent\n"
         "    {:name \"%s\"\n"
         "     :model \"%s\"\n"
         "     :instruction \"%s\"\n"
         "     :tools []}))\n")
    agent-ns agent-slug model instruction))

(defn- readme-content [agent-slug agent-ns]
  (format
    (str "# %s\n\n"
         "Agente gerado por `adk.cli.create` (adk-clojure).\n\n"
         "## Rodar\n\n"
         "1. Copie `.env.example` para `.env` e preencha com sua chave do Gemini —\n"
         "   veja https://aistudio.google.com/apikey\n\n"
         "   ```bash\n"
         "   cp .env.example .env\n"
         "   ```\n\n"
         "   (`%s.clj` já carrega esse `.env` sozinho via `adk.env/load-env!` — não\n"
         "   precisa de `export` manual. Se preferir, uma variável de ambiente real\n"
         "   também funciona e tem prioridade sobre o `.env`.)\n\n"
         "2. Abra um REPL ou rode:\n\n"
         "```bash\n"
         "clj -M -e \"(require '[%s :as agent] '[adk.runner :as runner] '[adk.events :as events])\n"
         "            (doseq [ev (runner/run (runner/in-memory-runner agent/root-agent)\n"
         "                                     {:user-id \\\"u1\\\" :session-id \\\"s1\\\"\n"
         "                                      :new-message {:role \\\"user\\\" :parts [{:text \\\"Oi!\\\"}]}})]\n"
         "              (println (:author ev) (events/text ev)))\"\n"
         "```\n")
    agent-slug agent-ns agent-ns))

(def ^:private env-example-content "GOOGLE_API_KEY=\nGOOGLE_GENAI_USE_VERTEXAI=false\n")

(def ^:private gitignore-content "/target\n/.cpcache\n/.nrepl-port\n.env\n")

(defn- write-file! [^java.io.File f content]
  (io/make-parents f)
  (spit f content))

(defn create-agent!
  "Gera um novo projeto de agente. `agent-name` vira o :name do agente (e,
  normalizado para kebab-case, o nome do diretório/namespace default).

  opts:
    :dir              diretório destino (default: ./<agent-name-normalizado>)
    :model            string de modelo (default: \"gemini-2.0-flash\")
    :instruction      instruction inicial do agente
    :adk-clojure-root força o caminho usado no :local/root do deps.edn
                       gerado (default: autodetectado via classpath)

  Devolve o map {:dir :agent-ns} do projeto gerado. Lança se `:dir` já
  existir e não estiver vazio, para não sobrescrever nada sem querer."
  [agent-name & [{:keys [dir model instruction adk-clojure-root]
                  :or {model "gemini-2.0-flash"
                       instruction "Você é um assistente útil. Responda de forma breve e direta."}}]]
  (let [agent-slug (slug agent-name)
        _ (when (str/blank? agent-slug)
            (throw (ex-info "agent-name inválido: normaliza para um slug vazio" {:agent-name agent-name})))
        dir (io/file (or dir agent-slug))
        agent-ns (symbol (str agent-slug ".agent"))
        root (or adk-clojure-root (detect-adk-clojure-root))]
    (when (and (.exists dir) (seq (.listFiles dir)))
      (throw (ex-info "diretório destino já existe e não está vazio" {:dir (str dir)})))
    (write-file! (io/file dir "deps.edn") (deps-edn-content root))
    (write-file! (io/file dir "src" (ns->path agent-ns))
                 (agent-clj-content agent-ns agent-slug model instruction))
    (write-file! (io/file dir "README.md") (readme-content agent-slug agent-ns))
    (write-file! (io/file dir ".env.example") env-example-content)
    (write-file! (io/file dir ".gitignore") gitignore-content)
    {:dir (str dir) :agent-ns agent-ns}))

(defn -main
  [& [agent-name dir]]
  (if (str/blank? agent-name)
    (do (println "Uso: clj -M -m adk.cli.create <nome-do-agente> [diretorio-destino]")
        (System/exit 1))
    (let [{:keys [dir agent-ns]} (create-agent! agent-name (when dir {:dir dir}))]
      (println "Projeto criado em" dir)
      (println "Namespace do agente:" agent-ns)
      (println "Próximo passo: cp" (str dir "/.env.example") (str dir "/.env")
               "(preencha GOOGLE_API_KEY) && veja" (str dir "/README.md")))))
