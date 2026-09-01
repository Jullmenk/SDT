package peer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import common.HashUtil;
import common.JsonUtil;
import common.TipoMensagem;
import io.ipfs.api.IPFS;
import io.ipfs.multihash.Multihash;

import java.util.ArrayList;
import java.util.List;

/**
 * RF1, lado do peer: recebe PREPARE ("atualizacao") e COMMIT, confirma, e faz pinning.
 *
 * CORREÇÃO (regra de pinning do RF1, ver DOCUMENTACAO_SPRINTS.md Sprint 1): o líder já
 * escolhe (de forma determinística) que peers devem fazer pinning de cada CID e envia
 * essa lista na mensagem PREPARE (campo "peersPin", ver lider.LiderConsenso). Este peer
 * guarda essa lista junto com a versão pendente e, ao aplicar o COMMIT, verifica se o seu
 * próprio id está nela; se estiver, chama ipfs.pin.add(cid) para garantir a redundância
 * pedida ("pelo menos 2 peers").
 */
public class PeerConsenso {

    private final IPFS ipfs;
    private final String topico;
    private final PeerEstado estado;
    private final FaissClient faiss;

    public PeerConsenso(IPFS ipfs, String topico, PeerEstado estado, FaissClient faiss) {
        this.ipfs = ipfs;
        this.topico = topico;
        this.estado = estado;
        this.faiss = faiss;
    }

    /** RF1 - Peer, passos 1-6: recebe PREPARE, valida versão, guarda pendente e confirma. */
    public void tratarAtualizacao(JsonObject json) {
        String cid = json.has("cid") ? json.get("cid").getAsString() : null;
        if (cid == null || !json.has("versaoVetor")) {
            System.out.println("ATUALIZAÇÃO sem CID/versão, ignorada.");
            return;
        }

        int versao = json.get("versaoVetor").getAsInt();

        // RF1 - Peer, passo 2: verificação de conflito de versões (versão já ultrapassada localmente).
        if (versao <= estado.getVersaoConfirmada()) {
            System.out.println("AVISO: PREPARE com versão não superior à confirmada localmente " +
                    "(recebida=" + versao + ", local=" + estado.getVersaoConfirmada() + "). Ignorado.");
            // NOTA: isto cobre o caso "versão desatualizada". Um verdadeiro processo de
            // resolução de conflitos concorrentes (duas propostas para a MESMA versão, com
            // conteúdo diferente) continua por implementar - ver DOCUMENTACAO_SPRINTS.md,
            // Sprint 3, tal como o próprio enunciado admite ("a implementar no futuro").
            return;
        }

        List<String> novoVetor = new ArrayList<>();
        if (json.has("novoVetor")) {
            for (JsonElement el : json.getAsJsonArray("novoVetor")) {
                novoVetor.add(el.getAsString());
            }
        }

        float[] embeddings = null;
        if (json.has("embeddings")) {
            JsonArray arr = json.getAsJsonArray("embeddings");
            embeddings = new float[arr.size()];
            for (int i = 0; i < arr.size(); i++) embeddings[i] = arr.get(i).getAsFloat();
        }

        List<String> peersPin = new ArrayList<>();
        if (json.has("peersPin")) {
            for (JsonElement el : json.getAsJsonArray("peersPin")) peersPin.add(el.getAsString());
        }

        // RF1 - Peer, passos 4-5: cria a nova versão pendente e guarda os embeddings temporariamente.
        estado.registarVersaoPendente(versao, cid, novoVetor, embeddings, peersPin);

        String hashLocal = HashUtil.calcularHashVetor(novoVetor);
        System.out.println("PREPARE recebido: cid=" + cid + " versaoVetor=" + versao +
                " documentos=" + novoVetor.size() + " hashLocal=" + hashLocal);

        // RF1 - Peer, passo 6: devolve a hash do vetor de CIDs ao líder.
        enviarConfirmacao(versao, hashLocal);
    }

    /** RF1 - Peer, passos finais: aplica o commit, atualiza o índice FAISS e faz pinning se responsável. */
    public void tratarCommit(JsonObject json) {
        if (!json.has("versaoVetor")) {
            System.out.println("COMMIT sem versão, ignorado.");
            return;
        }
        int versao = json.get("versaoVetor").getAsInt();

        boolean aplicado = estado.aplicarCommit(versao);
        if (!aplicado) {
            System.out.println("COMMIT para versão " + versao + " ignorado (já ultrapassado ou sem estado pendente).");
            return;
        }

        System.out.println("COMMIT aplicado: versão " + versao + " (vetor confirmado com " +
                estado.getVetorConfirmado().size() + " documentos)");

        String cid = json.has("cid") ? json.get("cid").getAsString() : null;
        float[] embeddings = estado.getEmbeddingsPendentes(versao);
        if (embeddings != null && cid != null) {
            indexarNoFaiss(cid, embeddings);
        }

        if (cid != null) {
            fazerPinningSeResponsavel(versao, cid);
        }

        estado.limparVersaoPendente(versao);
    }

    private void indexarNoFaiss(String cid, float[] embeddings) {
        JsonObject body = new JsonObject();
        body.addProperty("cid", cid);
        JsonArray arr = new JsonArray();
        for (float v : embeddings) arr.add(v);
        body.add("embedding", arr);
        String resp = faiss.post("/index", body.toString());
        System.out.println("FAISS /index -> " + resp);
    }

    /** Regra do RF1: "cada ficheiro/embedding deve ser pinned por pelo menos 2 peers". */
    private void fazerPinningSeResponsavel(int versao, String cid) {
        List<String> peersPin = estado.getPeersPinPendentes(versao);
        if (!peersPin.contains(estado.meuId)) {
            return;
        }
        try {
            ipfs.pin.add(Multihash.fromBase58(cid));
            System.out.println("Pinning feito para cid=" + cid + " (este peer é responsável, peersPin=" + peersPin + ")");
        } catch (Exception e) {
            System.err.println("Erro ao fazer pinning de " + cid + ": " + e.getMessage());
        }
    }

    private void enviarConfirmacao(int versao, String hashLocal) {
        JsonObject confirm = new JsonObject();
        confirm.addProperty("tipo", TipoMensagem.CONFIRMACAO);
        confirm.addProperty("peerId", estado.meuId);
        confirm.addProperty("versaoVetor", versao);
        confirm.addProperty("hashVetor", hashLocal);
        try {
            ipfs.pubsub.pub(topico, JsonUtil.GSON.toJson(confirm));
        } catch (Exception e) {
            System.err.println("Erro ao enviar confirmação: " + e.getMessage());
        }
    }
}
