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

/**
 * Lógica do protocolo de consenso (RF1) do lado do líder: PREPARE -> CONFIRMAÇÃO -> COMMIT.
 *
 * Corrige dois bugs identificados na análise ao código original:
 *
 *  1) Cálculo do quórum (Sprint 5). O código antigo fazia:
 *         List<Object> peers = Collections.singletonList(ipfs.pubsub.peers(TOPICO_PUBSUB));
 *         numPeers = Math.max(1, peers.size());
 *     `Collections.singletonList(...)` cria uma lista com UM elemento (que é a lista
 *     inteira lá dentro) — `peers.size()` dava sempre 1, e o quórum era sempre 1.
 *     Corrigido em {@link #calcularNumPeers()}: usa diretamente o resultado de
 *     ipfs.pubsub.peers(...), sem o embrulhar outra vez.
 *
 *  2) Falta da regra de pinning do RF1 ("cada ficheiro deve ser pinned por pelo menos 2
 *     peers"). Corrigido: ao fazer PREPARE, o líder já escolhe (de forma determinística,
 *     ver common.PeerSelector) os peers responsáveis pelo pinning e envia essa lista na
 *     própria mensagem PREPARE (campo "peersPin"); cada peer decide, ao receber o COMMIT,
 *     se está nessa lista e, se estiver, faz ipfs.pin.add(cid) (ver peer.PeerConsenso).
 */
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

    /** RF1 - Líder, passos 2-5: guarda no IPFS (feito antes de chamar isto), prepara nova versão e propaga. */
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

    /**
     * RNF3 (Sprint 6) - se este líder arrancou com uma versão PENDENTE recuperada do
     * handoff da eleição (ver LiderEstado, construtor, e peer.PeerEleicao#arrancarNovoLider),
     * essa versão nunca chegou a COMMIT do lado do líder anterior, e ninguém sabe quantas
     * confirmações já tinha reunido (essa contagem era só dele, e morreu com ele). Em vez
     * de tentar adivinhar isso, tratamo-la como uma ronda nova: recalcula-se o quórum com
     * o número de peers ATUAL (pode ter mudado desde então) e repete-se o PREPARE com o
     * MESMO conteúdo (mesmo cid/vetor/embeddings/peersPin - importante manter tudo igual,
     * para o hash do vetor bater certo). Os peers que já a tinham como pendente
     * simplesmente voltam a confirmar; nenhum dado se perde.
     */
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

    /** RF1 - Líder, passo final: recebe confirmação de um peer; se atingir quórum, faz commit. */
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
            // Versão já tinha sido ultrapassada por outra mais recente (commit fora de ordem) - nada a fazer.
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

    /**
     * Corrige o bug de "Collections.singletonList" descrito na documentação da classe.
     *
     * NOTA: nesta versão da biblioteca java-ipfs-http-client, ipfs.pubsub.peers(...) está
     * declarado a devolver "Object" (não "List&lt;String&gt;") - foi provavelmente por
     * isso que o código original o embrulhava num singletonList, para conseguir atribuir
     * o resultado a uma variável List sem erro de compilação. Em runtime o valor devolvido
     * é uma Collection (tipicamente uma List de peer ids); fazemos aqui um "instanceof"
     * seguro em vez de assumir isso às cegas, e caímos no fallback se não for o caso.
     */
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
