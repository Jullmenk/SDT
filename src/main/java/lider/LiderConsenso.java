package lider;

import com.google.gson.JsonObject;
import common.Config;
import common.EmbeddingUtil;
import common.HashUtil;
import common.JsonUtil;
import common.PeerSelector;
import common.TipoMensagem;
import io.ipfs.api.IPFS;

import java.util.List;

public class LiderConsenso {

    private final IPFS ipfs;
    private final String topico;
    private final LiderEstado estado;
    private final int pinningReplicas;

    public LiderConsenso(IPFS ipfs, String topico, LiderEstado estado) {
        this.ipfs = ipfs;
        this.topico = topico;
        this.estado = estado;
        this.pinningReplicas = Config.getInt("pinning.replicas", 2);
    }


    public LiderEstado.VersaoPreparada publicarPrepare(String nomeFicheiro, String cid, byte[] conteudoFicheiro) {
        int numPeers = calcularNumPeers();
        int quorumNecessario = numPeers / 2 + 1;

        List<String> peersPin = PeerSelector.escolherPeersResponsaveis(cid, estado.getPeersConhecidos(), pinningReplicas);

        LiderEstado.VersaoPreparada preparada = estado.iniciarNovaVersao(cid, peersPin, quorumNecessario);

        float[] embeddings = EmbeddingUtil.gerarEmbedding(conteudoFicheiro);
        String hashVetor = HashUtil.calcularHashVetor(preparada.novoVetor());

        System.out.println("    Peers subscritos ao tópico (IPFS pubsub): " + numPeers);
        System.out.println("    Quórum necessário para a versão " + preparada.versao() + ": " + quorumNecessario);
        System.out.println("    Peers responsáveis pelo pinning deste CID: " + peersPin);

        JsonObject msg = new JsonObject();
        msg.addProperty("tipo", TipoMensagem.ATUALIZACAO);
        msg.addProperty("nome", nomeFicheiro);
        msg.addProperty("cid", cid);
        msg.addProperty("versaoVetor", preparada.versao());
        msg.add("novoVetor", JsonUtil.GSON.toJsonTree(preparada.novoVetor()));
        msg.add("embeddings", JsonUtil.GSON.toJsonTree(embeddings));
        msg.addProperty("hashVetor", hashVetor);
        msg.add("peersPin", JsonUtil.GSON.toJsonTree(peersPin));

        publicar(msg);
        return preparada;
    }


    public void republicarPendente(int versao) {
        List<String> novoVetor = estado.getVetorPendenteDaVersao(versao);
        String cid = estado.getCidDaVersao(versao);
        if (novoVetor == null || cid == null) {
            System.err.println("Aviso: versão pendente " + versao + " recuperada do handoff, mas sem " +
                    "conteúdo utilizável - ignorada.");
            return;
        }
        List<String> peersPin = estado.getPeersPinDaVersao(versao);
        float[] embeddings = estado.getEmbeddingsPendentesDaVersao(versao);

        int numPeers = calcularNumPeers();
        int quorumNecessario = numPeers / 2 + 1;
        estado.definirQuorumPendente(versao, quorumNecessario);

        String hashVetor = HashUtil.calcularHashVetor(novoVetor);

        System.out.println("\n>>> A republicar versão pendente recuperada do handoff da eleição: versão " +
                versao + " cid=" + cid);
        System.out.println("    Quórum recalculado com os peers atuais: " + quorumNecessario + " de " + numPeers);

        JsonObject msg = new JsonObject();
        msg.addProperty("tipo", TipoMensagem.ATUALIZACAO);
        msg.addProperty("nome", "(versão recuperada após failover)");
        msg.addProperty("cid", cid);
        msg.addProperty("versaoVetor", versao);
        msg.add("novoVetor", JsonUtil.GSON.toJsonTree(novoVetor));
        if (embeddings != null) {
            msg.add("embeddings", JsonUtil.GSON.toJsonTree(embeddings));
        }
        msg.addProperty("hashVetor", hashVetor);
        msg.add("peersPin", JsonUtil.GSON.toJsonTree(peersPin));

        publicar(msg);
    }

    public void tratarConfirmacao(JsonObject json) {
        int versao = json.get("versaoVetor").getAsInt();
        String hash = json.get("hashVetor").getAsString();
        String peerId = json.has("peerId") ? json.get("peerId").getAsString() : "desconhecido";

        System.out.println("\n>>> Confirmação recebida do peer " + peerId + " para a versão " + versao + " (hash=" + hash + ")");

        boolean quorumAtingido = estado.registarConfirmacao(versao, peerId);
        if (!quorumAtingido) {
            return;
        }

        boolean aplicou = estado.confirmarVersao(versao);
        if (!aplicou) {
            return;
        }

        System.out.println("    QUORUM atingido para a versão " + versao + ". A publicar COMMIT...");

        JsonObject commitMsg = new JsonObject();
        commitMsg.addProperty("tipo", TipoMensagem.COMMIT);
        commitMsg.addProperty("cid", estado.getCidDaVersao(versao));
        commitMsg.addProperty("versaoVetor", versao);
        commitMsg.addProperty("hashVetor", estado.getHashVetorConfirmado());

        publicar(commitMsg);
        System.out.println("    COMMIT publicado para a versão " + versao + " (vetor confirmado agora com " +
                estado.getVetorConfirmado().size() + " documentos)\n");
    }

    private int calcularNumPeers() {
        try {
            Object resultado = ipfs.pubsub.peers(topico);
            if (resultado instanceof java.util.Collection<?> peers) {
                return Math.max(1, peers.size());
            }
            throw new IllegalStateException("ipfs.pubsub.peers(...) devolveu " +
                    (resultado == null ? "null" : resultado.getClass().getName()) + ", não uma Collection");
        } catch (Exception e) {
            int fallback = Math.max(1, estado.getPeersConhecidos().size());
            System.err.println("    Aviso: não foi possível obter peers via IPFS pubsub (" + e.getMessage() +
                    "), a usar peers conhecidos por peer_hello: " + fallback);
            return fallback;
        }
    }

    private void publicar(JsonObject msg) {
        try {
            ipfs.pubsub.pub(topico, msg.toString());
        } catch (Exception e) {
            System.err.println("Erro ao publicar mensagem via PubSub: " + e.getMessage());
        }
    }
}
