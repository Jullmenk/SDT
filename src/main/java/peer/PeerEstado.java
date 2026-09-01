package peer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Estado do peer, com acesso thread-safe (RNF2).
 *
 * Tal como no líder (ver lider.LiderEstado), as estruturas partilhadas passaram de
 * HashMap simples para ConcurrentHashMap, e os métodos "ler + decidir + escrever" que têm
 * de ser atómicos (ex.: aplicar um commit) são "synchronized". Também aqui a versão do
 * vetor passou a ser GLOBAL (não por CID), para ficar alinhada com o líder (ver
 * lider.LiderEstado e DOCUMENTACAO_SPRINTS.md, Sprint 3).
 */
public class PeerEstado {

    public final String meuId = UUID.randomUUID().toString();

    private volatile List<String> vetorConfirmado = new ArrayList<>();
    private volatile int versaoConfirmada = 0;

    private final Map<Integer, List<String>> vetorPendentePorVersao = new ConcurrentHashMap<>();
    private final Map<Integer, float[]> embeddingsPendentesPorVersao = new ConcurrentHashMap<>();
    private final Map<Integer, String> cidPorVersao = new ConcurrentHashMap<>();
    private final Map<Integer, List<String>> peersPinPorVersao = new ConcurrentHashMap<>();

    // Vista (eventualmente consistente) dos peers conhecidos, alimentada por peer_hello,
    // confirmações e resultados de query vistos no tópico. Usada pelo common.PeerSelector.
    private final Set<String> peersConhecidos = ConcurrentHashMap.newKeySet();

    public PeerEstado() {
        peersConhecidos.add(meuId);
    }

    /** RF1 - Peer, passos 3-5: guarda a nova versão pendente (sem substituir versões anteriores ainda não confirmadas). */
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

    /** RF1 - Peer: aplica o commit se a versão for mais recente que a confirmada localmente. */
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

    /** Chamar DEPOIS de qualquer leitura de getEmbeddingsPendentes/getPeersPinPendentes para esta versão. */
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

    /**
     * RNF3 (Sprint 6, handoff de estado) - devolve o número da versão pendente (ainda sem
     * commit) mais recente que este peer conhece, ou -1 se não houver nenhuma. Usado por
     * peer.PeerEleicao#arrancarNovoLider para incluir também as estruturas TEMPORÁRIAS no
     * handoff, não só o vetor confirmado (permanente) - ver o javadoc de
     * common.EstadoPersistente para a explicação completa.
     *
     * Simplificação assumida: só a versão pendente mais recente é recuperada, não uma
     * cadeia de várias versões pendentes em simultâneo (vários uploads concorrentes,
     * nenhum ainda confirmado, ao mesmo tempo) - caso raro, não coberto.
     */
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
