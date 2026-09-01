package common;

import java.util.Arrays;

/**
 * Geração de embeddings (vetores de características) para indexação/pesquisa no FAISS.
 *
 * CORREÇÃO IMPORTANTE (ver DOCUMENTACAO_SPRINTS.md, Sprint 2 e Sprint 7):
 * Na versão anterior existiam DUAS implementações diferentes — uma no Lider (para o
 * conteúdo dos documentos) e outra no Peer (para a prompt de pesquisa) — que distribuíam
 * a informação por posições diferentes do vetor. Como o FAISS mede similaridade por
 * posição (produto interno / distância), comparar vetores gerados por fórmulas diferentes
 * não tem significado nenhum — foi por isso que os resultados de pesquisa no teste do
 * Sprint 7 não faziam sentido (scores baixos, CIDs repetidos).
 *
 * Esta classe é o único sítio do sistema onde se geram embeddings. É usada tanto pelo
 * líder (conteúdo do ficheiro) como pelo peer (texto da prompt), garantindo que ambos
 * vivem no mesmo espaço vetorial.
 *
 * NOTA HONESTA: continua a ser uma implementação simplificada baseada em hashing dos
 * bytes, não um modelo de linguagem real. É suficiente para demonstrar o pipeline
 * distribuído (RF1/RF2) de ponta a ponta. Para produção, substituir o corpo deste método
 * por uma chamada a um modelo de embeddings real (ex.: SentenceTransformer via um novo
 * endpoint /embed no faiss_service.py) — ver README.md, secção "Possíveis evoluções".
 */
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
