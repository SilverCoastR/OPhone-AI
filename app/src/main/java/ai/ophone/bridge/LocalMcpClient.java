package ai.ophone.bridge;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class LocalMcpClient {
    private LocalMcpClient() {}

    public static String post(Context context, String requestJson) throws Exception {
        if (requestJson == null || requestJson.length() > 128 * 1024) {
            throw new IllegalArgumentException("invalid_request_size");
        }

        int port = LocalServerConfig.port(context.getApplicationContext());
        HttpURLConnection c = (HttpURLConnection)
                new URL("http://127.0.0.1:" + port + "/mcp").openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(3000);
        c.setReadTimeout(120000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("X-OPhoneAI-Local",
                LocalMcpSecurity.token(context.getApplicationContext()));

        byte[] data = requestJson.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(data.length);

        OutputStream out = c.getOutputStream();
        out.write(data);
        out.flush();
        out.close();

        int code = c.getResponseCode();
        InputStream in = code >= 200 && code < 300
                ? c.getInputStream() : c.getErrorStream();
        String text = readText(in, 12 * 1024 * 1024);
        c.disconnect();

        if (code < 200 || code >= 300) {
            throw new IllegalStateException("local_mcp_http_" + code + ": " + text);
        }
        return text == null || text.isEmpty() ? "{}" : text;
    }

    private static String readText(InputStream in, int max) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) >= 0) {
            int take = Math.min(n, max - total);
            if (take > 0) out.write(buf, 0, take);
            total += take;
            if (total >= max) break;
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
