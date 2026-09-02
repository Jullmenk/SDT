package lider;

import common.EstadoPersistente;
import common.HashUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class LiderEstado {

    private final Object lock = new Object();

    private volatile List<String> vetorConfirmado;
    private volatile int versaoConfirmada;

    private volatile List<String> ultimoVetorConhecido;
    private volatile int ultimaVersaoConhecida;

    private final Map<Integer, List<String>> vetorPendentePorVersao = new ConcurrentHashMap<>();
    private final Map<Integer, String> cidPorVersao = new ConcurrentHashMap<>();
    private final Map<Integer, List<String>> peersPinPorVersao = new ConcurrentHashMap<>();
    private final Map<Integer, Set<String>> confirmacoesPorVersao = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> quorumPorVersao = new ConcurrentHashMap<>();

    private final Map<Integer, float[]> embeddingsPendentesPorVersao = new ConcurrentHashMap<>();

    private final Set<String> peersConhecidos = ConcurrentHashMap.newKeySet();

    private final Integer versaoPendenteRecuperada;

    public LiderEstado() {
        EstadoPersistente.Snapshot snapshot = EstadoPersistente.ler(EstadoPersistente.FICHEIRO_OMISSAO);
        this.vetorConfirmado = new ArrayList<>(snapshot.vetorConfirmado());
        this.versaoConfirmada = snapshot.versaoConfirmada();

        EstadoPersistente.VersaoPendente pendente = snapshot.pendente();
        if (pendente != null && pendente.versao() > this.versaoConfirmada) {
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

    public synchronized void definirQuorumPendente(int versao, int quorumNecessario) {
        quorumPorVersao.put(versao, quorumNecessario);
        confirmacoesPorVersao.computeIfAbsent(versao, v -> ConcurrentHashMap.newKeySet());
    }

    public record VersaoPreparada(int versao, List<String> novoVetor) {
    }

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

    public synchronized boolean registarConfirmacao(int versao, String peerId) {
        registarPeerConhecido(peerId);
        Set<String> confs = confirmacoesPorVersao.computeIfAbsent(versao, v -> ConcurrentHashMap.newKeySet());
        confs.add(peerId);
        int quorumNecessario = quorumPorVersao.getOrDefault(versao, 1);
        return confs.size() >= quorumNecessario;
    }

    public synchronized boolean confirmarVersao(int versao) {
        if (versao <= versaoConfirmada) {
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
