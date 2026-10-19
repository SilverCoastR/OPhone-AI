package ai.ophone.bridge;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

public final class AuditLog {
    private static final Object LOCK = new Object();
    private static final String FILE = "tool_audit.jsonl";
    private static final long MAX_BYTES = 2L * 1024L * 1024L;

    private AuditLog() {}

    public static void record(Context context, String tool, String packageName,
                              boolean ok, String detail) {
        synchronized (LOCK) {
            try {
                File file = new File(context.getFilesDir(), FILE);
                if (file.exists() && file.length() > MAX_BYTES) {
                    file.delete();
                }

                JSONObject item = new JSONObject();
                item.put("time_ms", System.currentTimeMillis());
                item.put("tool", tool == null ? "" : tool);
                item.put("package", packageName == null ? "" : packageName);
                item.put("ok", ok);
                if (detail != null && !detail.isEmpty()) {
                    item.put("detail", detail.length() > 300
                            ? detail.substring(0, 300) : detail);
                }

                FileOutputStream out = new FileOutputStream(file, true);
                out.write((item.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                out.close();
            } catch (Exception ignored) {
            }
        }
    }

    public static String readRecent(Context context, int maxLines) {
        synchronized (LOCK) {
            try {
                File file = new File(context.getFilesDir(), FILE);
                if (!file.exists()) return context.getString(R.string.audit_empty);

                Deque<String> lines = new ArrayDeque<>();
                BufferedReader br = new BufferedReader(new InputStreamReader(
                        new FileInputStream(file), StandardCharsets.UTF_8));
                String line;
                while ((line = br.readLine()) != null) {
                    lines.addLast(line);
                    while (lines.size() > Math.max(1, maxLines)) lines.removeFirst();
                }
                br.close();

                StringBuilder out = new StringBuilder();
                for (String s : lines) out.append(s).append('\n');
                return out.length() == 0
                        ? context.getString(R.string.audit_empty)
                        : out.toString();
            } catch (Exception e) {
                return context.getString(R.string.audit_read_failed);
            }
        }
    }

    public static void clear(Context context) {
        synchronized (LOCK) {
            try {
                new File(context.getFilesDir(), FILE).delete();
            } catch (Exception ignored) {
            }
        }
    }
}
