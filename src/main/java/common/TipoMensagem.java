package common;

public final class TipoMensagem {

    public static final String ATUALIZACAO = "atualizacao";

    public static final String CONFIRMACAO = "confirmacao";

    public static final String COMMIT = "commit";

    public static final String HEARTBEAT = "heartbeat";

    public static final String ELECTION = "election";

    public static final String PEER_HELLO = "peer_hello";

    public static final String QUERY = "query";

    public static final String QUERY_RESULT = "query_result";

    private TipoMensagem() {
    }
}
