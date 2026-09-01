package peer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import common.EmbeddingUtil;
import common.JsonUtil;
import common.TipoMensagem;
import io.ipfs.api.IPFS;
import io.ipfs.multihash.Multihash;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RF2, lado do peer: processa a query, se for o peer responsável, e devolve resultado ao líder.
 *
 * DUAS CORREÇÕES (ver DOCUMENTACAO_SPRINTS.md, Sprint 7):
 *
 *  1) Distribuição de carga: só processa a query se a mensagem indicar que este peer é o
 *     "peerResponsavel" (escolhido pelo líder via common.PeerSelector — ver
 *     lider.LiderPesquisa). Antes, TODOS os peers processavam TODAS as queries.
 *
 *  2) Leitura do conteúdo do IPFS: usa ipfs.cat(...) em vez de ipfs.block.get(...). O
 *     bloco "raw" (block.get) inclui o framing protobuf do UnixFS - foi isso que apareceu
 *     como texto corrompido no vosso teste do Sprint 7 (readme.md), algo como
 *     "\nw\bq\"Sprint 1 e 2 funcionando!\"...". ipfs.cat(...) devolve o
 *     conteúdo do ficheiro já descodificado.
 */
public class PeerPesquisa {

    private final IPFS ipfs;
    private final String topico;
    private final PeerEstado estado;
    private final FaissClient faiss;

    private final Map<String, JsonObject> respostasPorId = new ConcurrentHashMap<>();

    public PeerPesquisa(IPFS ipfs, String topico, PeerEstado estado, FaissClient faiss) {
        this.ipfs = ipfs;
        this.topico = topico;
        this.estado = estado;
        this.faiss = faiss;
    }

    public void tratarQuery(JsonObject json) {
        String id = json.get("id").getAsString();
        String prompt = json.get("prompt").getAsString();

        String peerResponsavel = json.has("peerResponsavel") ? json.get("peerResponsavel").getAsString() : null;
        if (peerResponsavel != null && !peerResponsavel.equals(estado.meuId)) {
            // Não sou o peer escolhido para esta query - ignorar (distribuição de carga).
            return;
        }

        System.out.println("Aceitei query id=" + id + " com prompt='" + prompt + "'" +
                (peerResponsavel == null ? " (sem líder a indicar responsável - melhor esforço)" : ""));

        // Mesma função de embedding usada pelo líder para os documentos (common.EmbeddingUtil),
        // para que prompt e documentos vivam no mesmo espaço vetorial e sejam comparáveis.
        float[] embPrompt = EmbeddingUtil.gerarEmbedding(prompt.getBytes(StandardCharsets.UTF_8));

        JsonObject body = new JsonObject();
        JsonArray arr = new JsonArray();
        for (float v : embPrompt) arr.add(v);
        body.add("embedding", arr);
        body.addProperty("k", 5);

        String respostaFaiss = faiss.post("/search", body.toString());
        if (respostaFaiss == null) {
            System.err.println("Falha em FAISS para query id=" + id);
            return;
        }

        JsonObject faissJson = JsonUtil.GSON.fromJson(respostaFaiss, JsonObject.class);
        JsonArray resultsArr = faissJson.has("results") ? faissJson.getAsJsonArray("results") : new JsonArray();

        JsonArray docs = new JsonArray();
        for (var el : resultsArr) {
            JsonObject r = el.getAsJsonObject();
            String cid = r.get("cid").getAsString();
            float score = r.get("score").getAsFloat();
            try {
                byte[] data = ipfs.cat(Multihash.fromBase58(cid));
                String conteudo = new String(data, StandardCharsets.UTF_8);
                JsonObject doc = new JsonObject();
                doc.addProperty("cid", cid);
                doc.addProperty("score", score);
                doc.addProperty("conteudo", conteudo);
                docs.add(doc);
            } catch (Exception e) {
                System.err.println("Erro a ler cid " + cid + " do IPFS: " + e.getMessage());
            }
        }

        JsonObject resposta = new JsonObject();
        resposta.addProperty("id", id);
        resposta.addProperty("prompt", prompt);
        resposta.add("docs", docs);
        respostasPorId.put(id, resposta);

        enviarResultadoAoLider(id, resposta);
    }

    /** Exposto para a rota de debug /pesquisas/:id (ver Peer.java) - útil para confirmar, durante os testes, qual peer processou cada query. */
    public JsonObject getRespostaLocal(String id) {
        return respostasPorId.get(id);
    }

    private void enviarResultadoAoLider(String id, JsonObject resposta) {
        JsonObject msg = new JsonObject();
        msg.addProperty("tipo", TipoMensagem.QUERY_RESULT);
        msg.addProperty("id", id);
        msg.addProperty("peerId", estado.meuId);
        msg.add("resultado", resposta);
        try {
            ipfs.pubsub.pub(topico, JsonUtil.GSON.toJson(msg));
            System.out.println("Resultado de query id=" + id + " enviado ao líder.");
        } catch (Exception e) {
            System.err.println("Erro ao enviar query_result: " + e.getMessage());
        }
    }
}
