(ns adk.env
  "Carregador mínimo de arquivo `.env` (formato `CHAVE=valor`, um por linha,
  `#` para comentário, linhas em branco ignoradas) — o equivalente ao
  `python-dotenv` que o adk-python usa para `adk run`/`adk web` carregarem
  `GOOGLE_API_KEY` de um `.env` no diretório do projeto, sem precisar de
  `export` manual no shell.

  Uso típico, no início de um namespace de agente ou de um `-main`:

    (require '[adk.env :as env])
    (env/load-env!)   ;; carrega ./.env, se existir

  Depois disso, `adk.models.gemini` passa a enxergar GOOGLE_API_KEY/
  GEMINI_API_KEY/GOOGLE_GENAI_USE_VERTEXAI vindos do `.env` como se fossem
  variáveis de ambiente reais (via `adk.env/getenv`), sem precisar tocar
  `System/getenv` — a JVM não permite mutar o ambiente real do processo de
  forma portátil, então mantemos esse cache à parte em vez de tentar isso."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defonce ^:private loaded (atom {}))

(defn- parse-line [line]
  (let [line (str/trim line)]
    (when (and (seq line) (not (str/starts-with? line "#")))
      (when-let [i (str/index-of line "=")]
        (let [k (str/trim (subs line 0 i))
              v (-> (subs line (inc i)) str/trim
                    (str/replace #"^[\"']|[\"']$" ""))]
          (when (seq k) [k v]))))))

(defn- parse-env-file [^java.io.File f]
  (when (.exists f)
    (into {} (keep parse-line) (line-seq (io/reader f)))))

(defn load-env!
  "Carrega `path` (default: `.env`, relativo ao diretório de trabalho
  corrente) para o cache desta sessão, mesclando com o que já tiver sido
  carregado antes. Não-destrutivo: se o arquivo não existir, não faz nada
  (silenciosamente — ter ou não um `.env` é opcional, GOOGLE_API_KEY pode
  continuar vindo de uma variável de ambiente real)."
  ([] (load-env! ".env"))
  ([path]
   (when-let [parsed (parse-env-file (io/file path))]
     (swap! loaded merge parsed))
   nil))

(defn getenv
  "Como `System/getenv`, mas consulta primeiro o que foi carregado via
  `load-env!` antes de cair para a variável de ambiente real do processo."
  [k]
  (or (get @loaded k) (System/getenv k)))

(defn reset-env!
  "Limpa o cache de `.env` carregado — principalmente para testes."
  []
  (reset! loaded {}))
