package peer;

import com.google.gson.JsonObject;
import common.Config;
import common.EstadoPersistente;
import common.JsonUtil;
import common.TipoMensagem;
import io.ipfs.api.IPFS;

import java.util.List;

/**
 * RNF3 (deteção de falha) + RNF4 (eleição), lado do peer.
 *
 * CORREÇÕES:
 *
 *  1) Isolamento do lock (RNF2). No código original, TODO o processamento de mensagens
 *     (incluindo pesquisas RF2, que fazem chamadas de rede bloqueantes ao FAISS e ao
 *     IPFS) corria dentro de um único método "synchronized" que também protegia as
 *     variáveis de eleição. Se uma pesquisa demorasse muito (ou bloqueasse, por não haver
 *     timeout - ver FaissClient), a deteção de falha do líder deste peer ficava
 *     bloqueada à espera do mesmo lock. Esta classe tem o SEU PRÓPRIO lock, só para as
 *     variáveis de eleição/heartbeat, independente do processamento de queries
 *     (ver peer.Peer, que despacha queries para um executor à parte).
 *
 *  2) Handoff de estado no failover (Sprint 6). Antes de arrancar um novo processo
 *     Líder, este peer agora escreve o SEU PRÓPRIO vetor confirmado (que já tem em
 *     memória, recebido via COMMIT) em "estado_lider.json" — para o novo Líder arrancar
 *     já com os dados, em vez de arrancar vazio. Ver {@link #arrancarNovoLider()}.
 *
 *  3) Handoff das estruturas TEMPORÁRIAS, não só das permanentes (Sprint 6, correção
 *     posterior). O enunciado pede explicitamente que a recuperação envolva "todas as
 *     estruturas de dados permanentes e temporárias armazenadas pelo líder". O ponto (2)
 *     só recuperava o vetor confirmado (permanente); se o líder morresse a meio de uma
 *     ronda de consenso (já tinha feito PREPARE, ainda sem quórum), essa versão pendente
 *     perdia-se. Agora {@link #construirHandoffPendente()} inclui também, se existir, a
 *     versão pendente mais recente que este peer já recebeu via PREPARE — o novo líder
 *     recupera-a e volta a publicá-la (ver lider.LiderConsenso#republicarPendente), como
 *     se fosse um PREPARE novo, para reunir confirmações outra vez.
 */
public class PeerEleicao {

    private final PeerEstado estado;
    private final IPFS ipfs;
    private final String topico;

    private final Object lock = new Object();

    private volatile boolean emEleicao = false;
    private volatile long inicioEleicao = 0L;
    private volatile String melhorPeerId = null;
    private volatile boolean liderProcessStarted = false;
    private volatile long lastHeartbeatTime = System.currentTimeMillis();

    private final long electionDurationMs = Config.getLong("eleicao.duracao.ms", 3000);
    private final long heartbeatTimeoutMs = Config.getLong("heartbeat.timeout.ms", 10_000);

    private static final String START_LEADER_SH = "./start-leader.sh";
    private static final String START_LEADER_BAT = "start-leader.bat";

    public PeerEleicao(PeerEstado estado, IPFS ipfs, String topico) {
        this.estado = estado;
        this.ipfs = ipfs;
        this.topico = topico;
    }

    public void iniciarDetetor() {
        new Thread(() -> {
            while (true) {
                try {
                    long agora = System.currentTimeMillis();

                    if (emEleicao && (agora - inicioEleicao > electionDurationMs)) {
                        finalizarEleicaoSeForGanhador();
                    }

                    if (agora - lastHeartbeatTime > heartbeatTimeoutMs && !emEleicao && !liderProcessStarted) {
                        System.out.println("ALERTA: possível falha do líder (sem heartbeat há mais de " +
                                (heartbeatTimeoutMs / 1000) + "s).");
                        iniciarEleicao();
                    }

                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "leader-failure-detector").start();
    }

    public void tratarHeartbeat() {
        lastHeartbeatTime = System.currentTimeMillis();
        synchronized (lock) {
            emEleicao = false;
        }
    }

    public void tratarElection(JsonObject json) {
        if (!json.has("peerId")) return;
        String peerId = json.get("peerId").getAsString();
        synchronized (lock) {
            if (melhorPeerId == null || peerId.compareTo(melhorPeerId) > 0) {
                melhorPeerId = peerId;
            }
        }
    }

    private void iniciarEleicao() {
        synchronized (lock) {
            if (emEleicao || liderProcessStarted) return;
            emEleicao = true;
            inicioEleicao = System.currentTimeMillis();
            melhorPeerId = estado.meuId;
        }
        System.out.println("Iniciar eleição. Meu ID: " + estado.meuId);

        JsonObject msg = new JsonObject();
        msg.addProperty("tipo", TipoMensagem.ELECTION);
        msg.addProperty("peerId", estado.meuId);
        try {
            ipfs.pubsub.pub(topico, JsonUtil.GSON.toJson(msg));
        } catch (Exception e) {
            System.err.println("Erro ao enviar mensagem de eleição: " + e.getMessage());
        }
    }

    private void finalizarEleicaoSeForGanhador() {
        boolean souVencedor;
        synchronized (lock) {
            if (!emEleicao || liderProcessStarted) return;
            emEleicao = false;
            souVencedor = estado.meuId.equals(melhorPeerId);
            System.out.println("Eleição terminou. Melhor ID visto: " + melhorPeerId);
        }
        if (souVencedor) {
            System.out.println("Sou o vencedor da eleição. Vou arrancar o Líder.");
            arrancarNovoLider();
        } else {
            System.out.println("Não sou o vencedor da eleição. Não arranco o Líder.");
        }
    }

    /**
     * NOTA IMPORTANTE PARA A DEFESA (RNF3/Sprint 6): isto continua a ser uma eleição
     * simples (maior id vence), sem números de termo/época ao estilo Raft. Isso significa
     * que, numa partição de rede em que o líder antigo continua vivo mas os peers deixam
     * de o ouvir, é POSSÍVEL haver dois líderes em simultâneo por breves instantes
     * (split-brain) até a rede recuperar. Não implementámos deteção/fencing desse cenário
     * — é uma limitação conhecida e documentada, não um esquecimento (ver README.md,
     * secção "Limitações conhecidas").
     */
    private void arrancarNovoLider() {
        synchronized (lock) {
            if (liderProcessStarted) return;
            liderProcessStarted = true;
        }
        try {
            // Handoff de estado: o novo processo Líder vai ler este ficheiro ao arrancar
            // (ver lider.LiderEstado). Sem esta escrita, o líder novo arrancava vazio.
            // Inclui também a versão PENDENTE (temporária) mais recente, se este peer
            // conhecer alguma - ver construirHandoffPendente() e o javadoc de
            // common.EstadoPersistente.
            EstadoPersistente.VersaoPendente pendente = construirHandoffPendente();
            EstadoPersistente.escrever(EstadoPersistente.FICHEIRO_OMISSAO,
                    estado.getVetorConfirmado(), estado.getVersaoConfirmada(), pendente);
            System.out.println("Estado atual (versão " + estado.getVersaoConfirmada() + ", " +
                    estado.getVetorConfirmado().size() + " CIDs) escrito para " +
                    EstadoPersistente.FICHEIRO_OMISSAO + " antes de arrancar o novo líder.");
            if (pendente != null) {
                System.out.println("    + versão pendente incluída no handoff: versão " + pendente.versao() +
                        " (cid=" + pendente.cid() + ") - será republicada pelo novo líder para reconfirmação.");
            }

            String osName = System.getProperty("os.name").toLowerCase();
            ProcessBuilder pb;
            if (osName.contains("win")) {
                System.out.println("A executar script Windows para arrancar o Líder: " + START_LEADER_BAT);
                pb = new ProcessBuilder("cmd.exe", "/c", START_LEADER_BAT);
            } else {
                System.out.println("A executar script Unix para arrancar o Líder: " + START_LEADER_SH);
                pb = new ProcessBuilder("bash", START_LEADER_SH);
            }
            pb.inheritIO();
            pb.start();
        } catch (Exception e) {
            System.err.println("Falha ao correr script do Líder: " + e.getMessage());
            liderProcessStarted = false;
        }
    }

    /**
     * Constrói a versão pendente a incluir no handoff, se este peer tiver alguma (ver
     * PeerEstado#getVersaoPendenteMaisRecente). Devolve null se não houver nenhuma versão
     * pendente por aplicar - caso normal, se o líder morreu sem nenhum upload "a meio".
     */
    private EstadoPersistente.VersaoPendente construirHandoffPendente() {
        int versaoPendente = estado.getVersaoPendenteMaisRecente();
        if (versaoPendente < 0 || versaoPendente <= estado.getVersaoConfirmada()) {
            return null;
        }
        List<String> novoVetor = estado.getVetorPendente(versaoPendente);
        String cid = estado.getCidPendente(versaoPendente);
        if (novoVetor == null || cid == null) {
            return null;
        }
        List<String> peersPin = estado.getPeersPinPendentes(versaoPendente);
        float[] embeddings = estado.getEmbeddingsPendentes(versaoPendente);
        return new EstadoPersistente.VersaoPendente(versaoPendente, cid, novoVetor, peersPin, embeddings);
    }
}
