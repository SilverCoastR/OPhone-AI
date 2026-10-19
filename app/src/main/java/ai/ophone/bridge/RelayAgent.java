package ai.ophone.bridge;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Legacy development relay client.
 *
 * BridgeService no longer starts this class. It remains only as an optional
 * development adapter and delegates all phone actions to LocalToolKernel.
 */
public class RelayAgent implements Runnable {
    private final Context context;
    private final LocalToolKernel kernel;
    private volatile boolean running = true;
    private long lastHeartbeatAt = 0L;

    public RelayAgent(Context context) {
        this.context = context.getApplicationContext();
        this.kernel = new LocalToolKernel(this.context);
    }

    public void stop() {
        running = false;
    }

    @Override
    public void run() {
        while (running) {
            try {
                RelayConfig.Config cfg = RelayConfig.load(context);
                if (!cfg.isReady()) {
                    sleep(3000);
                    continue;
                }

                long now = System.currentTimeMillis();
                if (now - lastHeartbeatAt > 15000L) {
                    try {
                        JSONObject heartbeat = kernel.deviceStatus();
                        heartbeat.put("legacy_relay", true);
                        post(cfg, "/api/device/heartbeat", heartbeat);
                        lastHeartbeatAt = now;
                    } catch (Exception ignored) {
                    }
                }

                JSONObject request = new JSONObject();
                request.put("wait_ms", 8000);
                JSONObject polled = post(cfg, "/api/device/poll", request);
                JSONObject job = polled.optJSONObject("job");
                if (job == null) {
                    sleep(800);
                    continue;
                }

                JSONObject response =
                        executeJobWithTimeout(job, 15000L);
                response.put("job_id", job.optString("id"));
                post(cfg, "/api/device/result", response);
            } catch (Exception ignored) {
                sleep(2500);
            }
        }
    }

    private JSONObject executeJobWithTimeout(
            final JSONObject job, long timeoutMs) throws Exception {
        FutureTask<JSONObject> task =
                new FutureTask<>(() -> executeJob(job));
        Thread worker = new Thread(
                task,
                "OPhoneAI-Job-" + job.optString("id", "unknown"));
        worker.start();

        try {
            return task.get(
                    Math.max(1000L, timeoutMs),
                    TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            JSONObject out = new JSONObject();
            out.put("success", false);
            out.put("error", "job_timeout");
            return out;
        }
    }

    private JSONObject executeJob(JSONObject job) throws Exception {
        JSONObject envelope = new JSONObject();
        String tool = job.optString("tool");
        JSONObject args = job.optJSONObject("arguments");
        if (args == null) args = new JSONObject();

        try {
            envelope.put("success", true);
            envelope.put("result", kernel.call(tool, args));
        } catch (Exception e) {
            envelope.put("success", false);
            envelope.put(
                    "error",
                    e.getClass().getSimpleName() + ": "
                            + String.valueOf(e.getMessage()));
        }

        return envelope;
    }

    private JSONObject post(
            RelayConfig.Config cfg,
            String path,
            JSONObject body) throws Exception {
        HttpURLConnection c =
                (HttpURLConnection) new URL(cfg.baseUrl + path)
                        .openConnection();

        c.setRequestMethod("POST");
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("X-OPhoneAI-Device", cfg.deviceId);
        c.setRequestProperty(
                "Authorization",
                "Bearer " + cfg.secret);

        byte[] data = body.toString()
                .getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(data.length);

        OutputStream out = c.getOutputStream();
        out.write(data);
        out.flush();
        out.close();

        int code = c.getResponseCode();
        InputStream in = code >= 200 && code < 300
                ? c.getInputStream()
                : c.getErrorStream();

        String text = readText(in, 1024 * 1024);
        c.disconnect();

        if (code < 200 || code >= 300) {
            throw new IllegalStateException(
                    "relay_http_" + code + ": " + text);
        }

        if (text == null || text.trim().isEmpty()) {
            return new JSONObject();
        }
        return new JSONObject(text);
    }

    private String readText(InputStream in, int max)
            throws Exception {
        if (in == null) return "";

        ByteArrayOutputStream out =
                new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int total = 0;

        while (running) {
            int n = in.read(buf);
            if (n < 0) break;

            int take = Math.min(n, max - total);
            if (take > 0) out.write(buf, 0, take);
            total += take;

            if (total >= max) break;
        }

        return new String(
                out.toByteArray(),
                StandardCharsets.UTF_8);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
