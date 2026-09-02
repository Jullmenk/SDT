package common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

public final class HashUtil {

    private HashUtil() {
    }

    public static String calcularHashVetor(List<String> cids) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (String cid : cids) {
                sb.append(cid).append(";");
            }
            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 existe em qualquer JVM standard; isto é só uma rede de segurança.
            return Integer.toHexString(cids.hashCode());
        }
    }
}
