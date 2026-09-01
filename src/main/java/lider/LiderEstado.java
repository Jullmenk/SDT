package lider;

import common.EstadoPersistente;
import common.HashUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Estado do líder, com acesso sincronizado (RNF2 - Thread-Safety).
 *
 * CORREÇÃO IMPORTANTE (ver DOCUMENTACAO_SPRINTS.md, Sprint 2/3/5 e secção RNF2):
 *  1) Todas as estruturas partilhadas eram HashMap simples, acedidas ao mesmo tempo por
 *     threads do Spark (pedidos REST) e pela thread do PubSub, sem qualquer sincronização
 *     — condição de corrida real. Agora usam ConcurrentHashMap e os métodos que fazem
 *     "ler + decidir + escrever" (iniciarNovaVersao, confirmarVersao) são "synchronized".
 *  2) O vetor de CIDs passou a ter uma VERSÃO GLOBAL (versaoConfirmada / ultimaVersaoConhecida)
 *     em vez de uma versão por CID. Antes, duas atualizações concorrentes (ficheiros
 *     diferentes, mais ou menos ao mesmo tempo) partiam da mesma base e o commit mais
 *     tardio SUBSTITUÍA o vetor confirmado em vez de o fundir — perdendo silenciosamente
 *     o CID do primeiro commit. Agora cada nova versão pendente é construída em cima do
 *     último vetor CONHECIDO (confirmado + pendentes anteriores, não só confirmado), por
 *     isso as versões formam uma cadeia e nenhum CID se perde, mesmo com commits fora de
 *     ordem (só se aplica uma versão se for estritamente mais recente que a confirmada).
 */
public class LiderEstado {

    private final Object lock = new Object();

    private volatile List<String> vetorConfirmado;
    private volatile int versaoConfirmada;

    // último vetor conhecido = confirmado + todas as versões pendentes ainda não confirmadas
    private volatile List<String> ultimoVetorConhecido;
    private volatile int ultimaVersaoConhecida;

    private final Map<Integer, List<String>> vetorPendentePorVersao = new ConcurrentHashMap<>();
    private final Map<Integer, String> cidPorVersao = new ConcurrentHashMap<>();
    private final Map<Integer, List<String>> peersPinPorVersao = new ConcurrentHashMap<>();
    private final Map<Integer, Set<String>> confirmacoesPorVersao = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> quorumPorVersao = new ConcurrentHashMap<>();

    // Embeddings de versões pendentes recuperadas do handoff da eleição (ver construtor):
    // normalmente o líder NUNCA guarda embeddings em memória (gera-os e usa-os logo, na
    // hora, em LiderConsenso#publicarPrepare) - só precisamos disto para poder republicar
    // uma versão pendente herdada de um líder anterior sem ter o ficheiro original outra
    // vez à mão. Ver lider.LiderConsenso#republicarPendente.
    private final Map<Integer, float[]> embeddingsPendentesPorVersao = new ConcurrentHashMap<>();

    // Peers de que já se teve notícia (via peer_hello, confirmação ou resultado de query).
    // Usado pelo common.PeerSelector para pinning (RF1) e distribuição de carga (RF2).
    private final Set<String> peersConhecidos = ConcurrentHashMap.newKeySet();

    // Não-nula só logo após o arranque, se o handoff da eleição trouxe uma versão pendente
    // por reconfirmar (ver construtor e lider.Lider#main). Consumida uma única vez.
    private final Integer versaoPendenteRecuperada;

    public LiderEstado() {
        EstadoPersistente.Snapshot snapshot = EstadoPersistente.ler(EstadoPersistente.FICHEIRO_OMISSAO);
        this.vetorConfirmado = new ArrayList<>(snapshot.vetorConfirmado());
        this.versaoConfirmada = snapshot.versaoConfirmada();

        EstadoPersistente.VersaoPendente pendente = snapshot.pendente();
        if (pendente != null && pendente.versao() > this.versaoConfirmada) {
            // RNF3 (Sprint 6) - "recuperar todas as estruturas permanentes E temporárias":
            // esta versão ainda não tinha quórum quando o líder anterior morreu, mas já
            // tinha sido proposta (PREPARE) a pelo menos um peer - não a deitamos fora.
            vetorPendentePorVersao.put(pendente.versao(), pendente.novoVetor());
            cidPorVersao.put(pendente.versao(), pendente.cid());
            peersPinPorVersao.put(pendente.versao(),
                    pendente.peersPin() != null ? pendente.peersPin() : List.of());
            confirmacoesPorVersao.put(pendente.versao(), ConcurrentHashMap.newKeySet());
            if (pendente.embeddings() != null) {
                embeddingsPendentesPorVersao.put(pendente.versao(), pendente.embeddings());
            }
            this.ultimoVetorConhecido = new ArrayList<>(pendente.novoVetor());
            this.ultimaVersaoConhecida = pendente.versao();
            this.versaoPendenteRecuperada = pendente.versao();
            System.out.println("[LiderEstado] Versão pendente recuperada do handoff: versão " +
                    pendente.versao() + " (cid=" + pendente.cid() + ") - será republicada para reconfirmação.");
        } else {
            this.ultimoVetorConhecido = new ArrayList<>(this.vetorConfirmado);
            this.ultimaVersaoConhecida = this.versaoConfirmada;
            this.versaoPendenteRecuperada = null;
        }
    }

    /** Ver lider.Lider#main - se não for null, o líder deve republicar esta versão ao arrancar. */
    public Integer getVersaoPendenteRecuperada() {
        return versaoPendenteRecuperada;
    }

    public List<String> getVetorPendenteDaVersao(int versao) {
        return vetorPendentePorVersao.get(versao);
    }

    public List<String> getPeersPinDaVersao(int versao) {
        return peersPinPorVersao.getOrDefault(versao, List.of());
    }

    public float[] getEmbeddingsPendentesDaVersao(int versao) {
        return embeddingsPendentesPorVersao.get(versao);
    }

    /** Chamado por LiderConsenso#republicarPendente depois de recalcular o quórum com os peers atuais. */
    public synchronized void definirQuorumPendente(int versao, int quorumNecessario) {
        quorumPorVersao.put(versao, quorumNecessario);
        confirmacoesPorVersao.computeIfAbsent(versao, v -> ConcurrentHashMap.newKeySet());
    }

    /** Representa o resultado de "preparar" uma nova versão do vetor (fase PREPARE do RF1). */
    public record VersaoPreparada(int versao, List<String> novoVetor) {
    }

    /** RF1 - Líder, passo 3: cria nova versão do vetor sem substituir a pendente ainda não confirmada. */
    public synchronized VersaoPreparada iniciarNovaVersao(String cid, List<String> peersPin, int quorumNecessario) {
        int novaVersao = ultimaVersaoConhecida + 1;
        List<String> novoVetor = new ArrayList<>(ultimoVetorConhecido);
        novoVetor.add(cid);

        ultimaVersaoConhecida = novaVersao;
        ultimoVetorConhecido = novoVetor;

        vetorPendentePorVersao.put(novaVersao, novoVetor);
        cidPorVersao.put(novaVersao, cid);
        peersPinPorVersao.put(novaVersao, peersPin);
        confirmacoesPorVersao.put(novaVersao, ConcurrentHashMap.newKeySet());
        quorumPorVersao.put(novaVersao, quorumNecessario);

        return new VersaoPreparada(novaVersao, novoVetor);
    }

    /** RF1 - Líder: regista confirmação de um peer e devolve true se o quórum foi atingido agora. */
    public synchronized boolean registarConfirmacao(int versao, String peerId) {
        registarPeerConhecido(peerId);
        Set<String> confs = confirmacoesPorVersao.computeIfAbsent(versao, v -> ConcurrentHashMap.newKeySet());
        confs.add(peerId);
        int quorumNecessario = quorumPorVersao.getOrDefault(versao, 1);
        return confs.size() >= quorumNecessario;
    }

    /** RF1 - Líder: aplica a versão (commit) se for mais recente que a atualmente confirmada. */
    public synchronized boolean confirmarVersao(int versao) {
        if (versao <= versaoConfirmada) {
            // Versão igual/anterior à já confirmada - ignorar (evita retroceder estado).
            return false;
        }
        List<String> vetor = vetorPendentePorVersao.get(versao);
        if (vetor == null) {
            return false;
        }
        vetorConfirmado = vetor;
        versaoConfirmada = versao;
        persistirEstado();
        return true;
    }

    public void persistirEstado() {
        EstadoPersistente.escrever(EstadoPersistente.FICHEIRO_OMISSAO, vetorConfirmado, versaoConfirmada);
    }

    public void registarPeerConhecido(String peerId) {
        if (peerId != null && !peerId.isBlank()) {
            peersConhecidos.add(peerId);
        }
    }

    public Set<String> getPeersConhecidos() {
        return peersConhecidos;
    }

    public String getCidDaVersao(int versao) {
        return cidPorVersao.get(versao);
    }

    public List<String> getVetorConfirmado() {
        return vetorConfirmado;
    }

    public int getVersaoConfirmada() {
        return versaoConfirmada;
    }

    public String getHashVetorConfirmado() {
        return HashUtil.calcularHashVetor(vetorConfirmado);
    }
}
