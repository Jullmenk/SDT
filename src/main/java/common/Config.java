package common;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Configuração central do sistema.
 *
 * Antes desta refatoração, o ficheiro "application.properties" existia mas NUNCA era lido
 * pelo código (todas as portas e endereços estavam "hardcoded" em Lider.java/Peer.java).
 * Esta classe corrige isso: lê o ficheiro uma única vez (classpath) e expõe getters
 * tipados com valores por omissão sensatos, para que o sistema continue a funcionar
 * mesmo que o ficheiro não exista ou esteja incompleto.
 *
 * Cada chave pode ser sobreposta de duas formas, sem editar o ficheiro nem recompilar:
 *
 *  1) Variável de ambiente (RECOMENDADO): a chave em maiúsculas, com "." trocado por "_".
 *     Ex.: `peer.faiss.proxy.port` -> `PEER_FAISS_PROXY_PORT`.
 *     `PEER_FAISS_PROXY_PORT=8091 FAISS_PORT=9001 mvn exec:java -Dexec.mainClass=peer.Peer`
 *     Variáveis de ambiente são sempre herdadas por processos filhos, por isso funcionam
 *     mesmo que o `exec:java` do Maven arranque um processo Java à parte (que é o caso
 *     em algumas versões do plugin - foi o que aconteceu ao testar isto na prática: o
 *     `-D` não chegava ao processo novo).
 *  2) "-Dchave=valor" na linha de comandos do `mvn` - só funciona se o `exec:java` correr
 *     dentro do próprio processo do Maven (depende da versão/configuração do plugin).
 *
 * Ver README.md, secção "Correr vários peers na mesma máquina", e
 * DOCUMENTACAO_SPRINTS.md, secção "Qualidade de código", para o antes/depois.
 */
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

    /** Endereço multiaddr do IPFS local, construído a partir de ipfs.host/ipfs.port. */
    public static String ipfsMultiAddr() {
        return "/ip4/" + get("ipfs.host", "127.0.0.1") + "/tcp/" + getInt("ipfs.port", 5001);
    }
}
