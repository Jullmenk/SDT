package lider;

import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import common.Config;
import common.JsonUtil;
import common.TipoMensagem;
import io.ipfs.api.IPFS;
import io.ipfs.api.MerkleNode;
import io.ipfs.api.NamedStreamable;
import spark.Request;
import spark.Response;

import javax.servlet.MultipartConfigElement;
import javax.servlet.http.Part;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.ForkJoinPool;

import static spark.Spark.get;
import static spark.Spark.port;
import static spark.Spark.post;

/**
 * LÍDER - Sistema IPFS Distribuído P2P.
 *
 * Esta classe ficou deliberadamente "fina": só trata de I/O (REST + PubSub) e delega toda
 * a lógica de negócio para {@link LiderEstado} (estado + persistência), {@link LiderConsenso}
 * (RF1 - PREPARE/CONFIRMAÇÃO/COMMIT) e {@link LiderPesquisa} (RF2 - pesquisa de informação).
 *
 * Ver DOCUMENTACAO_SPRINTS.md para o mapeamento completo requisito -> ficheiro -> linhas,
 * e README.md para instruções de execução.
 */
public class Lider {

    private static IPFS ipfs;
    private static LiderEstado estado;
    private static LiderConsenso consenso;
    private static LiderPesquisa pesquisa;

    private static final String TOPICO_PUBSUB = Config.get("pubsub.topic", "atualizacoes");
    private static final long HEARTBEAT_INTERVAL_MS = Config.getLong("heartbeat.interval.ms", 5000);

    public static void main(String[] args) {
        try {
            System.out.println("===========================================");
            System.out.println("        LÍDER - Sistema IPFS P2P");
            System.out.println("===========================================\n");

            System.out.println("[1/3] A conectar ao IPFS (" + Config.ipfsMultiAddr() + ")...");
            ipfs = new IPFS(Config.ipfsMultiAddr());
            System.out.println("      IPFS ligado.\n");

            System.out.println("[2/3] A carregar estado persistido (se existir)...");
            estado = new LiderEstado();
            consenso = new LiderConsenso(ipfs, TOPICO_PUBSUB, estado);
            pesquisa = new LiderPesquisa(ipfs, TOPICO_PUBSUB, estado);
            System.out.println("      Versão confirmada carregada: " + estado.getVersaoConfirmada() +
                    " (" + estado.getVetorConfirmado().size() + " CIDs)\n");

            System.out.println("[3/3] A iniciar API REST...");
            port(Config.getInt("lider.api.port", 8080));
            configurarRotas();
            System.out.println("      API disponível em: http://localhost:" + Config.getInt("lider.api.port", 8080) + "\n");

            subscreverPubSub();
            iniciarHeartbeats();
            iniciarVerificacaoTimeoutsQuery();

            // RNF3 (Sprint 6) - se este líder arrancou com uma versão pendente recuperada
            // do handoff da eleição (ver LiderEstado, construtor), republica-a para reunir
            // confirmações de novo - ver LiderConsenso#republicarPendente.
            Integer versaoPendente = estado.getVersaoPendenteRecuperada();
            if (versaoPendente != null) {
                republicarPendenteAposArranque(versaoPendente);
            }

            System.out.println("===========================================");
            System.out.println("  LÍDER OPERACIONAL - PubSub ativo");
            System.out.println("  Tópico: " + TOPICO_PUBSUB);
            System.out.println("===========================================\n");
            System.out.println("A aguardar ficheiros...\n");

            while (true) {
                Thread.sleep(1000);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("ERRO ao iniciar o Líder:");
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void configurarRotas() {

        // RF1 - Líder, passos 1-2: recebe o ficheiro do cliente e guarda-o no IPFS.
        post("/upload", (Request req, Response res) -> {
            try {
                System.out.println("\n>>> Ficheiro recebido");

                String location = System.getProperty("java.io.tmpdir");
                long maxFileSize = 100_000_000;
                int fileSizeThreshold = 1024;

                MultipartConfigElement multipartConfig = new MultipartConfigElement(
                        location, maxFileSize, maxFileSize, fileSizeThreshold);
                req.raw().setAttribute("org.eclipse.jetty.multipartConfig", multipartConfig);

                Part filePart = req.raw().getPart("file");
                String filename = filePart.getSubmittedFileName();
                System.out.println("    Nome: " + filename);
                System.out.println("    Tamanho: " + filePart.getSize() + " bytes");

                File temp = File.createTempFile("ipfs-", "-" + filename);
                temp.deleteOnExit();
                try (InputStream in = filePart.getInputStream()) {
                    Files.copy(in, temp.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }

                byte[] fileBytes = Files.readAllBytes(temp.toPath());
                NamedStreamable.ByteArrayWrapper wrapper = new NamedStreamable.ByteArrayWrapper(filename, fileBytes);

                List<MerkleNode> nodes = ipfs.add(wrapper);
                String cid = nodes.get(0).hash.toBase58();
                System.out.println("    CID: " + cid);

                // RF1 - Líder, passos 3-5: nova versão do vetor, embeddings e propagação (PREPARE).
                consenso.publicarPrepare(filename, cid, fileBytes);

                temp.delete();

                res.status(200);
                res.type("text/plain");
                return cid;

            } catch (Exception e) {
                System.err.println("ERRO no /upload: " + e.getMessage());
                e.printStackTrace();
                res.status(500);
                return "Erro: " + e.getMessage();
            }
        });

        get("/health", (req, res) -> {
            res.type("text/plain");
            return "OK";
        });

        get("/vetor", (req, res) -> {
            res.type("application/json");
            JsonObject resposta = new JsonObject();
            resposta.addProperty("versao", estado.getVersaoConfirmada());
            resposta.addProperty("total", estado.getVetorConfirmado().size());
            resposta.add("cids", JsonUtil.GSON.toJsonTree(estado.getVetorConfirmado()));
            resposta.addProperty("hash", estado.getHashVetorConfirmado());
            return JsonUtil.GSON.toJson(resposta);
        });

        // RF2 - Líder, passos 1-3: recebe a prompt, escolhe o peer responsável e devolve o id.
        post("/prompt", (Request req, Response res) -> {
            res.type("application/json");
            try {
                JsonObject body = JsonUtil.GSON.fromJson(req.body(), JsonObject.class);
                if (body == null || !body.has("prompt")) {
                    res.status(400);
                    return "{\"erro\":\"campo 'prompt' em falta\"}";
                }
                String queryId = pesquisa.publicarQuery(body.get("prompt").getAsString());

                JsonObject resp = new JsonObject();
                resp.addProperty("id", queryId);
                res.status(200);
                return JsonUtil.GSON.toJson(resp);
            } catch (Exception e) {
                res.status(500);
                return "{\"erro\":\"" + e.getMessage() + "\"}";
            }
        });

        // RF2 - Líder: 2º pedido do cliente, obtenção da resposta a partir do id.
        get("/prompt/:id", (Request req, Response res) -> {
            res.type("application/json");
            JsonObject resultado = pesquisa.obterResultado(req.params(":id"));
            if (resultado == null) {
                res.status(202); // Accepted, ainda em processamento
                return "{\"estado\":\"pendente\"}";
            }
            res.status(200);
            return JsonUtil.GSON.toJson(resultado);
        });
    }

    /**
     * RNF3 (Sprint 6) - dá uns segundos à subscrição PubSub (e aos peers, que podem ainda
     * estar a anunciar-se via peer_hello) para "assentar" antes de republicar a versão
     * pendente herdada - é um atraso por prudência (best-effort), não uma garantia; se
     * calcularNumPeers() ainda não tiver visto todos os peers vivos a este ponto, o
     * quórum recalculado pode ficar temporariamente subestimado (mesma "aproximação" já
     * assumida em LiderConsenso#calcularNumPeers).
     */
    private static void republicarPendenteAposArranque(int versao) {
        new Thread(() -> {
            try {
                Thread.sleep(4000);
                consenso.republicarPendente(versao);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "republicar-pendente-thread").start();
    }

    // RNF3/RNF4 - heartbeats periódicos, usados pelos peers para detetar a falha do líder.
    private static void iniciarHeartbeats() {
        new Thread(() -> {
            while (true) {
                try {
                    JsonObject hb = new JsonObject();
                    hb.addProperty("tipo", TipoMensagem.HEARTBEAT);
                    hb.addProperty("timestamp", System.currentTimeMillis());
                    ipfs.pubsub.pub(TOPICO_PUBSUB, hb.toString());
                    Thread.sleep(HEARTBEAT_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    System.err.println("Erro ao enviar heartbeat: " + e.getMessage());
                }
            }
        }, "heartbeat-thread").start();
    }

    // RF2 (correção) - verifica periodicamente se alguma query ficou sem resposta durante
    // demasiado tempo (peer responsável em baixo, mensagem perdida, etc.) e reatribui a
    // outro peer - ver lider.LiderPesquisa#verificarTimeouts para o detalhe. Corre com
    // uma cadência bem menor que "query.timeout.ms" para detetar o timeout com pouco
    // atraso, sem sobrecarregar o líder.
    private static void iniciarVerificacaoTimeoutsQuery() {
        new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(1000);
                    pesquisa.verificarTimeouts();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    System.err.println("Erro ao verificar timeouts de query: " + e.getMessage());
                }
            }
        }, "query-timeout-thread").start();
    }

    private static void subscreverPubSub() {
        ForkJoinPool.commonPool().submit(() -> {
            while (true) {
                try {
                    System.out.println("Líder a subscrever ao tópico '" + TOPICO_PUBSUB + "'...");
                    var stream = ipfs.pubsub.sub(TOPICO_PUBSUB);
                    System.out.println("Líder a ouvir mensagens...\n");

                    stream.forEach(msg -> {
                        try {
                            String mensagem = JsonUtil.extrairMensagem(msg);
                            if (mensagem != null && !mensagem.isEmpty()) {
                                processarMensagemPubSub(mensagem);
                            }
                        } catch (Exception e) {
                            System.err.println("Erro ao processar mensagem PubSub no líder: " + e.getMessage());
                        }
                    });

                } catch (Exception e) {
                    System.err.println("ERRO na subscrição PubSub do líder: " + e.getMessage());
                    System.err.println("Líder irá tentar re-subscrever em 3 segundos...");
                    dormir(3000);
                }
            }
        });
    }

    private static void processarMensagemPubSub(String mensagem) {
        try {
            String trimmed = mensagem.trim();
            if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return;

            JsonObject json = JsonUtil.GSON.fromJson(trimmed, JsonObject.class);
            if (json == null || !json.has("tipo")) return;

            String tipo = json.get("tipo").getAsString();
            switch (tipo) {
                case TipoMensagem.CONFIRMACAO -> consenso.tratarConfirmacao(json);
                case TipoMensagem.QUERY_RESULT -> pesquisa.registarResultado(json);
                case TipoMensagem.PEER_HELLO -> {
                    if (json.has("peerId")) estado.registarPeerConhecido(json.get("peerId").getAsString());
                }
                default -> { /* heartbeat/election/atualizacao/query não interessam ao próprio líder */ }
            }

        } catch (JsonSyntaxException e) {
            System.err.println("JSON inválido no líder: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("Erro ao processar mensagem PubSub no líder: " + e.getMessage());
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
