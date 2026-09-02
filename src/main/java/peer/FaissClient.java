package peer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class FaissClient {

    private final String baseUrl;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public FaissClient(String baseUrl, int connectTimeoutMs, int readTimeoutMs) {
        this.baseUrl = baseUrl;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    public String post(String path, String bodyJson) {
        HttpURLConnection con = null;
        try {
            URL url = new URL(baseUrl + path);
            con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("POST");
            con.setRequestProperty("Content-Type", "application/json");
            con.setDoOutput(true);
            con.setConnectTimeout(connectTimeoutMs);
            con.setReadTimeout(readTimeoutMs);

            try (OutputStream os = con.getOutputStream()) {
                os.write(bodyJson.getBytes(StandardCharsets.UTF_8));
            }

            int code = con.getResponseCode();
            InputStreamReader isr = new InputStreamReader(
                    (code >= 200 && code < 300) ? con.getInputStream() : con.getErrorStream(),
                    StandardCharsets.UTF_8);
            try (BufferedReader br = new BufferedReader(isr)) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                return sb.toString();
            }
        } catch (Exception e) {
            System.err.println("Erro ao comunicar com FAISS (" + baseUrl + path + "): " + e.getMessage());
            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }
}
