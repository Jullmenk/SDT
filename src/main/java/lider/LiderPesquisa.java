package lider;

import com.google.gson.JsonObject;
import common.Config;
import common.JsonUtil;
import common.PeerSelector;
import common.TipoMensagem;
import io.ipfs.api.IPFS;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
 *
 * CORREÇÃO ADICIONAL (RF2, testes ao vivo): se o peer escolhido estiver em baixo (ou o
 * seu faiss_service.py tiver morrido a meio, ou a mensagem tiver-se perdido), a query
 * ficava "pendente" para sempre - nada reatribuía o trabalho a outro peer, e o cliente
 * ficava a fazer polling a `GET /prompt/:id` indefinidamente sem nunca obter resposta.
 * Agora guardamos, por query pendente, quando foi enviada e a que peer(es) já foi
 * atribuída; uma verificação periódica (ver lider.Lider#iniciarVerificacaoTimeoutsQuery,
 * chama {@link #verificarTimeouts()}) deteta queries paradas há mais de
 * "query.timeout.ms" e reatribui a um peer diferente (excluindo os já tentados), até
 * esgotar os peers conhecidos ou o número máximo de tentativas ("query.max.tentativas") -
 * nesse caso a query passa a ter um resultado de erro em vez de ficar pendente para
 * sempre, para o cliente deixar de fazer polling às cegas.
 */
public class LiderPesquisa {

    private final IPFS ipfs;
    private final String topico;
    private final LiderEstado estado;

    private final long timeoutMs = Config.getLong("query.timeout.ms", 5000);
    private final int maxTentativas = Config.getInt("query.max.tentativas", 5);

    private final Map<String, JsonObject> resultadosQuery = new ConcurrentHashMap<>();

    /** Estado de acompanhamento de uma query ainda sem resposta - usado só para deteção de timeout/reatribuição. */
    private static final class QueryPendente {
        final String prompt;
        volatile String peerResponsavel;
        volatile long enviadaEm;
        final Set<String> peersJaTentados = ConcurrentHashMap.newKeySet();

        QueryPendente(String prompt, String peerResponsavel) {
            this.prompt = prompt;
            this.peerResponsavel = peerResponsavel;
            this.enviadaEm = System.currentTimeMillis();
            if (peerResponsavel != null) {
                this.peersJaTentados.add(peerResponsavel);
            }
        }
    }

    private final Map<String, QueryPendente> pendentes = new ConcurrentHashMap<>();

    public LiderPesquisa(IPFS ipfs, String topico, LiderEstado estado) {
        this.ipfs = ipfs;
        this.topico = topico;
        this.estado = estado;
    }

    /** RF2 - Líder, passos 1-3: gera id/token, escolhe o peer responsável e propaga a query. */
    public String publicarQuery(String prompt) {
        String queryId = UUID.randomUUID().toString();
        String peerResponsavel = PeerSelector.escolherPeerUnico(queryId, estado.getPeersConhecidos());

        pendentes.put(queryId, new QueryPendente(prompt, peerResponsavel));
        enviar(queryId, prompt, peerResponsavel);
        return queryId;
    }

    private void enviar(String queryId, String prompt, String peerResponsavel) {
        String token = UUID.randomUUID().toString();

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
    }

    /** RF2 - Líder: guarda o resultado devolvido por um peer. */
    public void registarResultado(JsonObject json) {
        String id = json.get("id").getAsString();
        JsonObject resultado = json.getAsJsonObject("resultado");
        resultadosQuery.put(id, resultado);
        pendentes.remove(id);
        System.out.println("Resultado de query recebido para id=" + id);
    }

    /** RF2 - Líder, 2º pedido do cliente: obter a resposta a partir do id. */
    public JsonObject obterResultado(String id) {
        return resultadosQuery.get(id);
    }

    /**
     * CORREÇÃO (RF2, timeout/reatribuição) - chamada periodicamente (ver
     * lider.Lider#iniciarVerificacaoTimeoutsQuery). Para cada query ainda sem resultado,
     * se já passou "query.timeout.ms" desde o último envio, tenta um peer diferente
     * (excluindo os já tentados nesta query). Se já não houver mais peers conhecidos por
     * tentar, ou o número de tentativas exceder "query.max.tentativas", desiste e regista
     * um resultado de erro, para o cliente parar de receber "pendente" para sempre.
     */
    public void verificarTimeouts() {
        long agora = System.currentTimeMillis();
        for (Map.Entry<String, QueryPendente> entrada : pendentes.entrySet()) {
            String id = entrada.getKey();
            QueryPendente qp = entrada.getValue();

            if (resultadosQuery.containsKey(id)) {
                // Resultado chegou entretanto (corrida entre a resposta do peer e esta
                // verificação) - nada a fazer, será removido de "pendentes" pelo
                // registarResultado que já correu (ou vai correr de seguida).
                continue;
            }
            if (agora - qp.enviadaEm < timeoutMs) {
                continue; // ainda dentro do prazo normal de resposta
            }

            if (qp.peersJaTentados.size() >= maxTentativas) {
                desistir(id, qp, "número máximo de tentativas (" + maxTentativas + ") excedido");
                continue;
            }

            Set<String> candidatos = new TreeSet<>(estado.getPeersConhecidos());
            candidatos.removeAll(qp.peersJaTentados);

            if (candidatos.isEmpty()) {
                desistir(id, qp, "nenhum peer conhecido por tentar (todos os peers conhecidos já falharam nesta query)");
                continue;
            }

            String novoPeer = PeerSelector.escolherPeerUnico(id, candidatos);
            qp.peersJaTentados.add(novoPeer);
            qp.peerResponsavel = novoPeer;
            qp.enviadaEm = agora;

            System.out.println("Timeout na query id=" + id + " (sem resposta do peer anterior) - reatribuída ao peer " +
                    novoPeer + " (tentativa " + qp.peersJaTentados.size() + "/" + maxTentativas + ")");
            enviar(id, qp.prompt, novoPeer);
        }
    }

    private void desistir(String id, QueryPendente qp, String motivo) {
        JsonObject erro = new JsonObject();
        erro.addProperty("id", id);
        erro.addProperty("prompt", qp.prompt);
        erro.addProperty("erro", "Não foi possível obter resposta a esta query: " + motivo + ".");
        erro.add("peersTentados", JsonUtil.GSON.toJsonTree(new LinkedHashSet<>(qp.peersJaTentados)));
        resultadosQuery.put(id, erro);
        pendentes.remove(id);
        System.err.println("Query id=" + id + " desistida: " + motivo);
    }
}
