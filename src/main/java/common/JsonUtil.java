package common;

import com.google.gson.Gson;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Utilitários JSON / PubSub partilhados entre Lider e Peer.
 *
 * O método extrairMensagem(...) estava duplicado byte-a-byte nas duas classes originais
 * (Lider.java e Peer.java). Uma única cópia aqui evita que as duas divirjam com o tempo.
 */
public final class JsonUtil {

    public static final Gson GSON = new Gson();

    private JsonUtil() {
    }

    /**
     * As mensagens recebidas via ipfs.pubsub.sub(...) chegam envolvidas num Map (com uma
     * chave "data" em bytes ou em Base64, consoante a versão/config do cliente IPFS usado).
     * Este método normaliza todos os formatos observados para uma String JSON simples.
     */
    public static String extrairMensagem(Object obj) {
        if (obj == null) return null;

        try {
            if (obj instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) obj;
                Object data = map.get("data");
                if (data == null) return null;

                if (data instanceof byte[]) {
                    return new String((byte[]) data, StandardCharsets.UTF_8);
                }

                if (data instanceof String) {
                    String dataStr = (String) data;
                    try {
                        if (dataStr.startsWith("u") && dataStr.length() > 1) {
                            dataStr = dataStr.substring(1);
                        }
                        byte[] decoded = Base64.getDecoder().decode(dataStr);
                        return new String(decoded, StandardCharsets.UTF_8);
                    } catch (IllegalArgumentException e) {
                        return dataStr;
                    }
                }
            }

            if (obj instanceof byte[]) {
                return new String((byte[]) obj, StandardCharsets.UTF_8);
            }

            if (obj instanceof String) {
                return (String) obj;
            }

        } catch (Exception e) {
            System.err.println("[JsonUtil] Erro a extrair/descodificar mensagem: " + e.getMessage());
        }

        return null;
    }
}
