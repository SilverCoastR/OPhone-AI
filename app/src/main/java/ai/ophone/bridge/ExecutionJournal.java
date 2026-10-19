package ai.ophone.bridge;

import android.content.Context;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Durable at-most-once bridge receipts. Started work is NEVER replayed after a crash. */
public final class ExecutionJournal {
    private static final Set<String> running = new HashSet<>();
    private static final long MAX_ENTRY = 12L * 1024 * 1024;
    private static final long MAX_TOTAL = 32L * 1024 * 1024;
    private static final long TTL = 24L * 60 * 60 * 1000;

    private ExecutionJournal() {}

    public static JSONObject context(JSONObject request) throws Exception {
        JSONObject value = request.optJSONObject("ophoneai_context");
        if (value == null) return null;
        String conversation = value.optString("conversation", "");
        String id = value.optString("call_id", "");
        if (!conversation.matches("c:[A-Za-z0-9:._-]{1,180}")
                || !id.matches("[A-Za-z0-9._-]{1,80}")) {
            throw new IllegalArgumentException("invalid_execution_context");
        }
        return value;
    }

    private static String key(JSONObject context) throws Exception {
        return hash(context.getString("conversation") + "\n" + context.getString("call_id"));
    }

    private static String hash(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (byte value : digest) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return out.toString();
    }

    private static File directory(Context context) throws Exception {
        File dir = new File(context.getFilesDir(), "execution_receipts");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("journal_unavailable");
        return dir;
    }

    private static JSONObject read(File file) throws Exception {
        if (!file.isFile()) return null;
        if (file.length() > MAX_ENTRY) throw new IllegalStateException("journal_entry_too_large");
        try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    private static void write(File file, JSONObject value) throws Exception {
        byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_ENTRY) throw new IllegalStateException("journal_entry_too_large");
        File temp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) { out.write(bytes); out.getFD().sync(); }
        if (!temp.renameTo(file)) throw new IllegalStateException("journal_commit_failed");
    }

    private static JSONObject publicReceipt(JSONObject value, String key) throws Exception {
        if (value == null) return new JSONObject().put("state", "missing");
        if (!"done".equals(value.optString("state"))) {
            return new JSONObject().put("state", running.contains(key) ? "running" : "unknown")
                    .put("error", "action_outcome_not_confirmed_do_not_replay");
        }
        return new JSONObject().put("state", "done").put("response", value.getJSONObject("response"));
    }

    public static synchronized JSONObject receipt(Context context, JSONObject scope) throws Exception {
        String key = key(scope);
        return publicReceipt(read(new File(directory(context), key + ".json")), key);
    }

    /** Null authorizes ONE execution; every other return is a cached/uncertain receipt. */
    public static synchronized JSONObject begin(Context context, JSONObject scope, JSONObject params) throws Exception {
        String key = key(scope);
        File file = new File(directory(context), key + ".json");
        JSONObject previous = read(file);
        String fingerprint = hash(params.toString());
        if (previous != null) {
            if (!fingerprint.equals(previous.optString("fingerprint"))) {
                throw new IllegalArgumentException("call_id_reused_with_different_arguments");
            }
            return publicReceipt(previous, key);
        }
        prune(context);
        JSONObject entry = new JSONObject().put("state", "started")
                .put("time_ms", System.currentTimeMillis()).put("fingerprint", fingerprint)
                .put("tool", params.optString("name")).put("call_id", scope.optString("call_id"));
        write(file, entry); // If durable begin fails, no Android action is allowed.
        running.add(key);
        RuntimeDiagnostics.record(context, "execution_started", scope.optString("call_id"), params.optString("name"));
        return null;
    }

    public static synchronized void finish(Context context, JSONObject scope, String response) throws Exception {
        String key = key(scope);
        File file = new File(directory(context), key + ".json");
        try {
            JSONObject entry = read(file);
            if (entry == null) throw new IllegalStateException("execution_receipt_missing");
            JSONObject reply = new JSONObject(response);
            entry.put("state", "done").put("response", reply);
            write(file, entry); // Commit result before attempting WebView delivery.
            JSONObject result = reply.optJSONObject("result");
            boolean failed = reply.has("error") || (result != null && result.optBoolean("isError"));
            RuntimeDiagnostics.record(context, failed ? "execution_failed" : "execution_finished",
                    scope.optString("call_id"), entry.optString("tool"));
        } finally { running.remove(key); }
    }

    public static synchronized JSONObject summary(Context context) throws Exception {
        File[] files = directory(context).listFiles((dir, name) -> name.endsWith(".json"));
        return new JSONObject().put("receipts", files == null ? 0 : files.length)
                .put("in_flight", running.size()).put("retention_hours", 24)
                .put("replay_policy", "never_reexecute_started_calls");
    }

    private static void prune(Context context) throws Exception {
        File[] files = directory(context).listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        long total = 0; int count=0;
        for (File file : files) {
            total += file.length();
            JSONObject entry=read(file);
            // Tombstones preserve non-replayability, but must not evict every newer receipt.
            if(entry!=null&&"done".equals(entry.optString("state")))count++;
        }
        for (File file : files) {
            String key = file.getName().replace(".json", "");
            // Never expire a started/uncertain action: a delayed duplicate must stay non-replayable.
            JSONObject entry = read(file);
            if (entry == null || !"done".equals(entry.optString("state")) || running.contains(key)) continue;
            if (count < 96 && total < MAX_TOTAL && System.currentTimeMillis() - file.lastModified() < TTL) continue;
            // Keep a small tombstone so pruning large results cannot authorize a repeated action.
            long oldSize = file.length();
            entry.remove("response"); entry.put("state", "expired"); write(file, entry);
            total -= oldSize - file.length(); count--;
        }
        if (total >= MAX_TOTAL) throw new IllegalStateException("journal_capacity_reached");
    }
}
