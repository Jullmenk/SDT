package peer;

import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import common.Config;
import common.JsonUtil;
import common.TipoMensagem;
import io.ipfs.api.IPFS;
import io.ipfs.multiaddr.MultiAddress;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;

import static spark.Spark.get;
import static spark.Spark.port;

public class Peer {

    private static IPFS ipfs;
    private static PeerEstado estado;
    private static PeerConsenso consenso;
    private static PeerEleicao eleicao;
    private static PeerPesquisa pesquisa;
    private static FaissClient faiss;

    private static final String TOPICO_PUBSUB = Config.get("pubsub.topic", "atualizacoes");
    private static final ExecutorService queryExecutor = Executors.newFixedThreadPool(4);

    public static void main(String[] args) {
        try {
            System.out.println("===========================================");
            System.out.println("      PEER - Sistema IPFS P2P");
            System.out.println("===========================================\n");

            estado = new PeerEstado();
            System.out.println("Peer ID: " + estado.meuId + "\n");

            System.out.println("[1/4] A conectar ao IPFS (" + Config.ipfsMultiAddr() + ")...");
            ipfs = new IPFS(new MultiAddress(Config.ipfsMultiAddr()));
            System.out.println("      IPFS conectado.\n");

            faiss = new FaissClient(
                    "http://" + Config.get("faiss.host", "localhost") + ":" + Config.getInt("faiss.port", 9000),
                    Config.getInt("faiss.timeout.connect.ms", 3000),
                    Config.getInt("faiss.timeout.read.ms", 5000));

            consenso = new PeerConsenso(ipfs, TOPICO_PUBSUB, estado, faiss);
            eleicao = new PeerEleicao(estado, ipfs, TOPICO_PUBSUB);
            pesquisa = new PeerPesquisa(ipfs, TOPICO_PUBSUB, estado, faiss);

            System.out.println("[2/4] A subscrever tópico PubSub...");
            subscreverPubSub();
            System.out.println("      Subscrito com sucesso!\n");

            eleicao.iniciarDetetor();
            iniciarAnuncioPeriodico();

            System.out.println("[3/4] A iniciar API de debug (proxy FAISS)...");
            port(Config.getInt("peer.faiss.proxy.port", 8090));
            configurarRotasDebug();
            System.out.println("      API de debug em: http://localhost:" + Config.getInt("peer.faiss.proxy.port", 8090) + "\n");

            System.out.println("[4/4] Pronto.");
            System.out.println("===========================================");
            System.out.println("  PEER OPERACIONAL - PubSub ativo");
            System.out.println("===========================================\n");

            while (true) {
                Thread.sleep(1000);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("\nERRO ao iniciar Peer:");
            e.printStackTrace();
        }
    }

    /**
     * Rotas de debug/inspeção manual (não fazem parte do fluxo normal do RF1/RF2 - tudo
     * o resto chega via PubSub, não por aqui). Servem só para inspecionar manualmente,
     * durante os testes, o estado deste peer e as pesquisas que ele já processou.
     */
    private static void configurarRotasDebug() {
        get("/health", (req, res) -> "OK");
        get("/estado", (req, res) -> {
            res.type("application/json");
            JsonObject o = new JsonObject();
            o.addProperty("peerId", estado.meuId);
            o.addProperty("versaoConfirmada", estado.getVersaoConfirmada());
            o.addProperty("documentos", estado.getVetorConfirmado().size());
            o.add("peersConhecidos", JsonUtil.GSON.toJsonTree(estado.getPeersConhecidos()));
            return JsonUtil.GSON.toJson(o);
        });
        get("/pesquisas/:id", (req, res) -> {
            res.type("application/json");
            JsonObject resposta = pesquisa.getRespostaLocal(req.params(":id"));
            if (resposta == null) {
                res.status(404);
                return "{\"erro\":\"este peer não processou essa query (ou não foi o responsável)\"}";
            }
            return JsonUtil.GSON.toJson(resposta);
        });
    }

    private static void iniciarAnuncioPeriodico() {
        new Thread(() -> {
            while (true) {
                try {
                    JsonObject hello = new JsonObject();
                    hello.addProperty("tipo", TipoMensagem.PEER_HELLO);
                    hello.addProperty("peerId", estado.meuId);
                    ipfs.pubsub.pub(TOPICO_PUBSUB, JsonUtil.GSON.toJson(hello));
                    Thread.sleep(Config.getLong("heartbeat.interval.ms", 5000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    System.err.println("Erro ao anunciar presença (peer_hello): " + e.getMessage());
                }
            }
        }, "peer-hello-thread").start();
    }

    private static void subscreverPubSub() {
        ForkJoinPool.commonPool().submit(() -> {
            while (true) {
                try {
                    System.out.println("A subscrever ao tópico '" + TOPICO_PUBSUB + "'...");
                    var stream = ipfs.pubsub.sub(TOPICO_PUBSUB);
                    System.out.println("Subscrição realizada! A ouvir mensagens...\n");

                    stream.forEach(msg -> {
                        try {
                            String mensagem = JsonUtil.extrairMensagem(msg);
                            if (mensagem != null && !mensagem.isEmpty()) {
                                processarMensagem(mensagem);
                            }
                        } catch (Exception e) {
                            System.err.println("Erro ao processar mensagem individual: " + e.getMessage());
                        }
                    });

                } catch (Exception e) {
                    System.err.println("ERRO no PubSub: " + e.getMessage());
                    System.err.println("Tentando re-subscrever em 3 segundos...");
                    dormir(3000);
                }
            }
        });
    }

    private static void processarMensagem(String mensagem) {
        try {
            String trimmed = mensagem.trim();
            if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return;

            JsonObject json = JsonUtil.GSON.fromJson(trimmed, JsonObject.class);
            if (json == null || !json.has("tipo")) return;

            String tipo = json.get("tipo").getAsString();
            switch (tipo) {
                case TipoMensagem.HEARTBEAT -> eleicao.tratarHeartbeat();
                case TipoMensagem.ELECTION -> eleicao.tratarElection(json);
                case TipoMensagem.PEER_HELLO -> {
                    if (json.has("peerId")) estado.registarPeerConhecido(json.get("peerId").getAsString());
                }
                case TipoMensagem.ATUALIZACAO -> consenso.tratarAtualizacao(json);
                case TipoMensagem.COMMIT -> consenso.tratarCommit(json);
                case TipoMensagem.QUERY -> queryExecutor.submit(() -> pesquisa.tratarQuery(json));
                default -> { /* mensagem de tipo desconhecido - ignorar */ }
            }

        } catch (JsonSyntaxException e) {
            System.err.println("JSON inválido recebido: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("Erro ao processar mensagem: " + e.getMessage());
        }
    }

    private static void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
