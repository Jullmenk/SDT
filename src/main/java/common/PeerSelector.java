package common;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/**
 * Algoritmo distribuído (hashing determinístico sobre a lista ordenada de peers conhecidos,
 * ao estilo simplificado de um anel de consistent hashing) usado para decidir, sem
 * coordenação central extra:
 *
 *  - RF1 (regra de pinning): que peers devem fazer pinning de um determinado CID.
 *  - RF2 (distribuição de carga): que peer deve aceitar/processar uma determinada query.
 *
 * Qualquer nó (líder ou peer) que tenha a mesma vista de "peers conhecidos" chega
 * exatamente ao mesmo resultado para a mesma chave — não é preciso pedir permissão a
 * ninguém. Neste projeto é o líder que corre este cálculo (porque já é o coordenador
 * central do protocolo de consenso), mas o algoritmo em si é o mesmo que qualquer peer
 * podia correr de forma independente dado o mesmo conjunto de peers conhecidos.
 *
 * Ver DOCUMENTACAO_SPRINTS.md, Sprint 1 (regra de pinning) e Sprint 7 (RF2), para a
 * explicação de como isto responde às perguntas de defesa mais prováveis.
 */
public final class PeerSelector {

    private PeerSelector() {
    }

    /** Escolhe, de forma determinística, até "quantos" peers responsáveis por "chave". */
    public static List<String> escolherPeersResponsaveis(String chave, Collection<String> peersConhecidos, int quantos) {
        List<String> ordenados = new ArrayList<>(new TreeSet<>(peersConhecidos));
        if (ordenados.isEmpty()) {
            return List.of();
        }
        int n = Math.min(quantos, ordenados.size());
        int inicio = Math.floorMod(chave.hashCode(), ordenados.size());

        List<String> escolhidos = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            escolhidos.add(ordenados.get((inicio + i) % ordenados.size()));
        }
        return escolhidos;
    }

    /** Escolhe um único peer responsável por "chave" (usado na distribuição de carga do RF2). */
    public static String escolherPeerUnico(String chave, Collection<String> peersConhecidos) {
        List<String> escolhidos = escolherPeersResponsaveis(chave, peersConhecidos, 1);
        return escolhidos.isEmpty() ? null : escolhidos.get(0);
    }
}
