package lider;

import com.google.gson.JsonObject;
import common.PeerSelector;
import common.TipoMensagem;
import io.ipfs.api.IPFS;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RF2 - Pesquisa de Informação, lado do líder.
 *
 * CORREÇÃO IMPORTANTE (Sprint 7): o enunciado pede "utilizar uma abordagem distribuída
 * que permita a distribuição da carga pelos peers" para decidir qual peer aceita o
 * "token" de uma query. Na versão original NÃO havia nenhuma seleção — a mensagem
 * "query" ia para o tópico e TODOS os peers processavam a mesma pesquisa (trabalho
 * duplicado, e o líder ficava com o último resultado a chegar, por ordem de chegada,
 * sem qualquer critério).
 *
 * Agora o líder calcula, com o mesmo algoritmo determinístico usado no pinning
 * (common.PeerSelector), qual é o peer responsável por cada query, e inclui esse peer
 * no campo "peerResponsavel" da mensagem. Só esse peer processa o pedido (ver
 * peer.PeerPesquisa#tratarQuery) — os restantes ignoram-no.
 */
public class LiderPesquisa {

    private final IPFS ipfs;
    private final String topico;
    private final LiderEstado estado;

    private final Map<String, JsonObject> resultadosQuery = new ConcurrentHashMap<>();

    public LiderPesquisa(IPFS ipfs, String topico, LiderEstado estado) {
        this.ipfs = ipfs;
        this.topico = topico;
        this.estado = estado;
    }

    /** RF2 - Líder, passos 1-3: gera id/token, escolhe o peer responsável e propaga a query. */
    public String publicarQuery(String prompt) {
        String queryId = UUID.randomUUID().toString();
        String token = UUID.randomUUID().toString();

        String peerResponsavel = PeerSelector.escolherPeerUnico(queryId, estado.getPeersConhecidos());

        JsonObject msg = new JsonObject();
        msg.addProperty("tipo", TipoMensagem.QUERY);
        msg.addProperty("id", queryId);
        msg.addProperty("token", token);
        msg.addProperty("prompt", prompt);
        if (peerResponsavel != null) {
            msg.addProperty("peerResponsavel", peerResponsavel);
        }

        try {
            ipfs.pubsub.pub(topico, msg.toString());
        } catch (Exception e) {
            System.err.println("Erro ao publicar query: " + e.getMessage());
        }

        System.out.println("Query id=" + queryId + " enviada" +
                (peerResponsavel != null ? " (peer responsável: " + peerResponsavel + ")" : " (sem peers conhecidos ainda - qualquer peer pode aceitar)"));
        return queryId;
    }

    /** RF2 - Líder: guarda o resultado devolvido por um peer. */
    public void registarResultado(JsonObject json) {
        String id = json.get("id").getAsString();
        JsonObject resultado = json.getAsJsonObject("resultado");
        resultadosQuery.put(id, resultado);
        System.out.println("Resultado de query recebido para id=" + id);
    }

    /** RF2 - Líder, 2º pedido do cliente: obter a resposta a partir do id. */
    public JsonObject obterResultado(String id) {
        return resultadosQuery.get(id);
    }
}
