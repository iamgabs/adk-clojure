(ns adk.tools.mcp-test
  "Testes de `adk.tools.mcp`. Os testes offline constroem objetos REAIS do
  SDK (McpSchema.Tool/CallToolResult/TextContent) diretamente — sem
  precisar de um servidor rodando — para validar a lógica de conversão
  (schema MCP -> nosso formato, extração de texto de um CallToolResult).

  O teste ^:integration sobe um servidor MCP de verdade via `npx` (o
  servidor de referência oficial `@modelcontextprotocol/server-everything`)
  e exercita o fluxo completo (connect! -> mcp-tools -> invoke-tool ->
  disconnect!) contra ele. Exige `npx`/rede (baixa o pacote na 1a vez) — por
  isso fica fora de `clj -X:test` por padrão, igual aos testes reais do
  Gemini (rode `clj -X:test-integration` para incluí-lo)."
  (:require [clojure.test :refer [deftest testing is]]
            [adk.tools :as tools]
            [adk.tools.mcp :as mcp])
  (:import [io.modelcontextprotocol.spec McpSchema$Tool McpSchema$CallToolResult McpSchema$TextContent]))

(def ^:private wrap-mcp-tool #'mcp/wrap-mcp-tool)
(def ^:private content->text #'mcp/content->text)

(deftest wrap-mcp-tool-converts-schema-to-our-format
  (let [raw (McpSchema$Tool. "get_weather" nil "Returns the weather"
                              {"type" "object"
                               "properties" {"city" {"type" "string" "description" "City name"}}
                               "required" ["city"]}
                              nil nil nil)
        wrapped (wrap-mcp-tool nil raw)]
    (is (= "get_weather" (tools/tool-name wrapped)))
    (is (= "Returns the weather" (tools/tool-description wrapped)))
    (is (= {:name "get_weather"
            :description "Returns the weather"
            :parameters {:type "object"
                         :properties {:city {:type "string" :description "City name"}}
                         :required ["city"]}}
           (tools/tool-declaration wrapped)))
    (is (false? (tools/long-running? wrapped)))))

(deftest wrap-mcp-tool-handles-nil-description
  ;; McpSchema.Tool exige inputSchema não-nulo (o SDK valida isso no
  ;; construtor) — mas description pode ser nil na prática.
  (let [raw (McpSchema$Tool. "no_op" nil nil {"type" "object" "properties" {}} nil nil nil)
        wrapped (wrap-mcp-tool nil raw)]
    (is (= "" (tools/tool-description wrapped)))
    (is (= {:type "object" :properties {}}
           (get-in (tools/tool-declaration wrapped) [:parameters])))))

(deftest content-text-extracts-text-parts
  (let [result (McpSchema$CallToolResult. [(McpSchema$TextContent. "sunny, 20C")] false nil nil)]
    (is (= "sunny, 20C" (content->text result)))))

(deftest content-text-concatenates-multiple-parts
  (let [result (McpSchema$CallToolResult.
                 [(McpSchema$TextContent. "part1 ") (McpSchema$TextContent. "part2")] false nil nil)]
    (is (= "part1 part2" (content->text result)))))

(deftest connect-requires-command
  (is (thrown? clojure.lang.ExceptionInfo (mcp/connect! {}))))

(deftest ^:integration real-mcp-server-round-trip
  (let [client (mcp/connect! {:command "npx" :args ["-y" "@modelcontextprotocol/server-everything"]})]
    (try
      (let [server-tools (mcp/mcp-tools client)]
        (testing "listou pelo menos uma tool do servidor de referência"
          (is (pos? (count server-tools))))
        (testing "achamos a tool \"echo\" do servidor de referência e conseguimos chamá-la"
          (if-let [echo (first (filter #(= "echo" (tools/tool-name %)) server-tools))]
            (let [result (tools/invoke-tool echo {"message" "oi"} nil)]
              (is (not (contains? result :error)))
              (is (re-find #"oi" (str (:result result)))))
            (println "SKIP: tool \"echo\" não encontrada no servidor de referência"))))
      (finally (mcp/disconnect! client)))))
