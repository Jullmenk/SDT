package peer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PeerEstado {

    public final String meuId = UUID.randomUUID().toString();

    private volatile List<String> vetorConfirmado = new ArrayList<>();
    private volatile int versaoConfirmada = 0;

    private final Map<Integer, List<String>> vetorPendentePorVersao = new ConcurrentHashMap<>();
    private final Map<Integer, float[]> embeddingsPendentesPorVersao = new ConcurrentHashMap<>();
    private final Map<Integer, String> cidPorVersao = new ConcurrentHashMap<>();
    private final Map<Integer, List<String>> peersPinPorVersao = new ConcurrentHashMap<>();

    private final Set<String> peersConhecidos = ConcurrentHashMap.newKeySet();

    public PeerEstado() {
        peersConhecidos.add(meuId);
    }

    public synchronized void registarVersaoPendente(int versao, String cid, List<String> novoVetor,
                                                     float[] embeddings, List<String> peersPin) {
        vetorPendentePorVersao.put(versao, novoVetor);
        cidPorVersao.put(versao, cid);
        if (embeddings != null) {
            embeddingsPendentesPorVersao.put(versao, embeddings);
        }
        if (peersPin != null) {
            peersPinPorVersao.put(versao, peersPin);
        }
    }

    public synchronized boolean aplicarCommit(int versao) {
        if (versao <= versaoConfirmada) {
            return false;
        }
        List<String> pendente = vetorPendentePorVersao.get(versao);
        if (pendente == null) {
            return false;
        }
        vetorConfirmado = pendente;
        versaoConfirmada = versao;
        return true;
    }

    public void limparVersaoPendente(int versao) {
        vetorPendentePorVersao.remove(versao);
        embeddingsPendentesPorVersao.remove(versao);
        peersPinPorVersao.remove(versao);
        cidPorVersao.remove(versao);
    }

    public float[] getEmbeddingsPendentes(int versao) {
        return embeddingsPendentesPorVersao.get(versao);
    }

    public List<String> getPeersPinPendentes(int versao) {
        return peersPinPorVersao.getOrDefault(versao, List.of());
    }

    public List<String> getVetorPendente(int versao) {
        return vetorPendentePorVersao.get(versao);
    }

    public String getCidPendente(int versao) {
        return cidPorVersao.get(versao);
    }

    public int getVersaoPendenteMaisRecente() {
        int maior = -1;
        for (int v : vetorPendentePorVersao.keySet()) {
            if (v > maior) {
                maior = v;
            }
        }
        return maior;
    }

    public void registarPeerConhecido(String peerId) {
        if (peerId != null && !peerId.isBlank()) {
            peersConhecidos.add(peerId);
        }
    }

    public Set<String> getPeersConhecidos() {
        return peersConhecidos;
    }

    public List<String> getVetorConfirmado() {
        return vetorConfirmado;
    }

    public int getVersaoConfirmada() {
        return versaoConfirmada;
    }
}
