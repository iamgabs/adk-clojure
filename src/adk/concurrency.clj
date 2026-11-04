(ns adk.concurrency
  "Único namespace do sistema com threads/futures cruas. Todo o resto do
  core é preguiçoso por padrão (lazy-seq/mapcat); concorrência real só
  existe aqui — usada pelo `ParallelAgent` para fan-out real entre
  branches."
  (:import [java.util.concurrent LinkedBlockingQueue]))

(def ^:private sentinel-done (Object.))

(defn- drain
  "Lazy-seq que consome `q` até o sentinel de término, relançando qualquer
  erro reportado por um produtor na posição em que ocorreu."
  [^LinkedBlockingQueue q]
  (lazy-seq
    (let [v (.take q)]
      (cond
        (identical? v sentinel-done) nil
        (and (map? v) (contains? v ::error)) (throw (::error v))
        :else (cons v (drain q))))))

(defn thread-seq
  "Roda `(producer-fn put!)` numa thread própria; `put!` enfileira valores
  um de cada vez. Devolve uma lazy-seq que drena a fila até o sinal de
  término."
  [producer-fn]
  (let [q (LinkedBlockingQueue.)]
    (future
      (try
        (producer-fn (fn [v] (.put q v)))
        (catch Throwable t (.put q {::error t}))
        (finally (.put q sentinel-done))))
    (drain q)))

(defn merge-by-arrival
  "Roda cada seq de `seqs` numa thread própria e funde os itens numa única
  lazy-seq, na ORDEM REAL DE CHEGADA (não na ordem de `seqs`) — é o que dá
  ao ParallelAgent seu fan-out real. Cancelamento é só cooperativo de
  granularidade grossa: quando o consumidor para de drenar a seq resultante,
  as threads produtoras que já estão bloqueadas numa chamada de rede não são
  interrompidas (ver riscos no plano de implementação)."
  [seqs]
  (let [q (LinkedBlockingQueue.)
        remaining (atom (count seqs))]
    (if (zero? @remaining)
      '()
      (do
        (doseq [s seqs]
          (future
            (try
              (doseq [item s] (.put q item))
              (catch Throwable t (.put q {::error t}))
              (finally (when (zero? (swap! remaining dec)) (.put q sentinel-done))))))
        (drain q)))))
