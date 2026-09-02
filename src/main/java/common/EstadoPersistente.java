package common;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class EstadoPersistente {

    public static final String FICHEIRO_OMISSAO = "estado_lider.json";


    public record VersaoPendente(int versao, String cid, List<String> novoVetor,
                                  List<String> peersPin, float[] embeddings) {
    }

    public record Snapshot(List<String> vetorConfirmado, int versaoConfirmada, VersaoPendente pendente) {
    }

    private EstadoPersistente() {
    }


    public static void escrever(String ficheiro, List<String> vetorConfirmado, int versaoConfirmada) {
        escrever(ficheiro, vetorConfirmado, versaoConfirmada, null);
    }

    public static void escrever(String ficheiro, List<String> vetorConfirmado, int versaoConfirmada,
                                 VersaoPendente pendente) {
        try (FileWriter fw = new FileWriter(ficheiro)) {
            JsonObject obj = new JsonObject();
            obj.add("vetorConfirmado", JsonUtil.GSON.toJsonTree(vetorConfirmado));
            obj.addProperty("versaoConfirmada", versaoConfirmada);
            if (pendente != null) {
                JsonObject p = new JsonObject();
                p.addProperty("versao", pendente.versao());
                p.addProperty("cid", pendente.cid());
                p.add("novoVetor", JsonUtil.GSON.toJsonTree(pendente.novoVetor()));
                p.add("peersPin", JsonUtil.GSON.toJsonTree(
                        pendente.peersPin() != null ? pendente.peersPin() : List.of()));
                if (pendente.embeddings() != null) {
                    p.add("embeddings", JsonUtil.GSON.toJsonTree(pendente.embeddings()));
                }
                obj.add("pendente", p);
            }
            fw.write(JsonUtil.GSON.toJson(obj));
        } catch (IOException e) {
            System.err.println("[EstadoPersistente] Erro a escrever " + ficheiro + ": " + e.getMessage());
        }
    }

    public static Snapshot ler(String ficheiro) {
        File f = new File(ficheiro);
        if (!f.exists()) {
            return new Snapshot(new ArrayList<>(), 0, null);
        }
        try (FileReader fr = new FileReader(f)) {
            JsonObject obj = JsonUtil.GSON.fromJson(fr, JsonObject.class);
            if (obj == null) {
                return new Snapshot(new ArrayList<>(), 0, null);
            }
            List<String> vetor = new ArrayList<>();
            if (obj.has("vetorConfirmado")) {
                for (JsonElement e : obj.getAsJsonArray("vetorConfirmado")) {
                    vetor.add(e.getAsString());
                }
            }
            int versao = obj.has("versaoConfirmada") ? obj.get("versaoConfirmada").getAsInt() : 0;

            VersaoPendente pendente = null;
            if (obj.has("pendente") && obj.get("pendente").isJsonObject()) {
                JsonObject p = obj.getAsJsonObject("pendente");
                int versaoPendente = p.has("versao") ? p.get("versao").getAsInt() : -1;
                String cid = p.has("cid") ? p.get("cid").getAsString() : null;
                if (versaoPendente >= 0 && cid != null) {
                    List<String> novoVetor = new ArrayList<>();
                    if (p.has("novoVetor")) {
                        for (JsonElement e : p.getAsJsonArray("novoVetor")) {
                            novoVetor.add(e.getAsString());
                        }
                    }
                    List<String> peersPin = new ArrayList<>();
                    if (p.has("peersPin")) {
                        for (JsonElement e : p.getAsJsonArray("peersPin")) {
                            peersPin.add(e.getAsString());
                        }
                    }
                    float[] embeddings = null;
                    if (p.has("embeddings")) {
                        JsonArray arr = p.getAsJsonArray("embeddings");
                        embeddings = new float[arr.size()];
                        for (int i = 0; i < arr.size(); i++) {
                            embeddings[i] = arr.get(i).getAsFloat();
                        }
                    }
                    pendente = new VersaoPendente(versaoPendente, cid, novoVetor, peersPin, embeddings);
                }
            }

            return new Snapshot(vetor, versao, pendente);
        } catch (Exception e) {
            System.err.println("[EstadoPersistente] Erro a ler " + ficheiro + ": " + e.getMessage());
            return new Snapshot(new ArrayList<>(), 0, null);
        }
    }
}
