package common;

import java.util.Arrays;

public final class EmbeddingUtil {

    public static final int DIM = Config.getInt("embeddings.dim", 128);

    private EmbeddingUtil() {
    }

    public static float[] gerarEmbedding(byte[] conteudo) {
        float[] emb = new float[DIM];
        if (conteudo == null || conteudo.length == 0) {
            return emb;
        }

        int hashConteudo = Arrays.hashCode(conteudo);
        int metade = DIM / 2;

        emb[0] = (float) Math.log10(conteudo.length + 1) / 10.0f;

        for (int i = 1; i < metade; i++) {
            int bit = (hashConteudo >> (i % 32)) & 1;
            emb[i] = bit == 1 ? 0.5f : -0.5f;
        }

        long soma = 0;
        for (byte b : conteudo) soma += (b & 0xFF);
        float media = soma / (float) conteudo.length;
        emb[metade] = media / 255.0f;

        for (int i = metade + 1; i < DIM; i++) {
            int idx = i % conteudo.length;
            emb[i] = (conteudo[idx] & 0xFF) / 512.0f;
        }

        normalizar(emb);
        return emb;
    }

    private static void normalizar(float[] emb) {
        float norma = 0.0f;
        for (float v : emb) norma += v * v;
        norma = (float) Math.sqrt(norma);
        if (norma > 0.0001f) {
            for (int i = 0; i < emb.length; i++) emb[i] /= norma;
        }
    }
}
