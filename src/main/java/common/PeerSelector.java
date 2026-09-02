package common;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

public final class PeerSelector {

    private PeerSelector() {
    }

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

    public static String escolherPeerUnico(String chave, Collection<String> peersConhecidos) {
        List<String> escolhidos = escolherPeersResponsaveis(chave, peersConhecidos, 1);
        return escolhidos.isEmpty() ? null : escolhidos.get(0);
    }
}
