package common;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class Config {

    private static final Properties PROPS = new Properties();

    static {
        try (InputStream in = Config.class.getResourceAsStream("/application.properties")) {
            if (in != null) {
                PROPS.load(in);
            } else {
                System.err.println("[Config] application.properties não encontrado no classpath, a usar valores por omissão.");
            }
        } catch (IOException e) {
            System.err.println("[Config] Erro a ler application.properties: " + e.getMessage());
        }
    }

    private Config() {
    }

    public static String get(String chave, String omissao) {
        String viaEnv = System.getenv(paraNomeDeVariavelAmbiente(chave));
        if (viaEnv != null && !viaEnv.isBlank()) {
            return viaEnv;
        }
        String viaSystemProperty = System.getProperty(chave);
        if (viaSystemProperty != null) {
            return viaSystemProperty;
        }
        return PROPS.getProperty(chave, omissao);
    }

    private static String paraNomeDeVariavelAmbiente(String chave) {
        return chave.toUpperCase().replace('.', '_').replace('-', '_');
    }

    public static int getInt(String chave, int omissao) {
        try {
            return Integer.parseInt(get(chave, String.valueOf(omissao)).trim());
        } catch (NumberFormatException e) {
            return omissao;
        }
    }

    public static long getLong(String chave, long omissao) {
        try {
            return Long.parseLong(get(chave, String.valueOf(omissao)).trim());
        } catch (NumberFormatException e) {
            return omissao;
        }
    }

    public static String ipfsMultiAddr() {
        return "/ip4/" + get("ipfs.host", "127.0.0.1") + "/tcp/" + getInt("ipfs.port", 5001);
    }
}
