package common;

/**
 * Constantes para o campo "tipo" das mensagens trocadas via PubSub do IPFS.
 *
 * Antes existiam strings soltas ("atualizacao", "commit", ...) repetidas em Lider.java
 * e Peer.java — fácil de escrever mal um dos lados e o outro nunca reconhecer a mensagem.
 * Centralizar aqui elimina esse risco (o compilador avisa se houver erro de referência).
 */
public final class TipoMensagem {

    /** Líder -> Peers: PREPARE (nova versão do vetor + CID + embeddings). RF1. */
    public static final String ATUALIZACAO = "atualizacao";

    /** Peer -> Líder: confirmação do PREPARE, com o hash local do vetor pendente. RF1. */
    public static final String CONFIRMACAO = "confirmacao";

    /** Líder -> Peers: COMMIT, a nova versão passa a ser a versão confirmada. RF1. */
    public static final String COMMIT = "commit";

    /** Líder -> Peers: heartbeat periódico, usado para deteção de falha do líder. RNF3/RNF4. */
    public static final String HEARTBEAT = "heartbeat";

    /** Peer -> Todos: pedido de eleição (estilo bully, maior id vence). RNF4. */
    public static final String ELECTION = "election";

    /** Peer -> Todos: anúncio de presença, usado para descoberta dinâmica de peers. RNF5. */
    public static final String PEER_HELLO = "peer_hello";

    /** Líder -> Peers: pedido de pesquisa (prompt) a processar por UM peer. RF2. */
    public static final String QUERY = "query";

    /** Peer -> Líder: resultado de uma pesquisa processada. RF2. */
    public static final String QUERY_RESULT = "query_result";

    private TipoMensagem() {
    }
}
