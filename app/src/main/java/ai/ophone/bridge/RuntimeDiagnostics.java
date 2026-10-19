package ai.ophone.bridge;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

/** Small, private diagnostic ring. Never records prompts, arguments, UI text or credentials. */
public final class RuntimeDiagnostics {
    private static volatile JSONObject snapshot = new JSONObject();
    private static volatile long observedAt;

    private RuntimeDiagnostics() {}

    public static synchronized void observe(JSONObject value) {
        snapshot = value;
        observedAt = System.currentTimeMillis();
    }

    public static boolean modelGenerating(String conversation) {
        JSONObject value=snapshot;
        return System.currentTimeMillis()-observedAt<8000&&conversation.equals(value.optString("conversation"))
                &&value.optBoolean("model_generating")&&value.optString("model_transport_error").isEmpty();
    }

    public static JSONObject status(Context context) throws Exception {
        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("runtime", new JSONObject(snapshot.toString()));
        out.put("observed_at_ms", observedAt);
        out.put("observation_age_ms", observedAt == 0 ? -1 : System.currentTimeMillis() - observedAt);
        out.put("chat_available", ChatActivity.hasRuntimeHost());
        out.put("accessibility_connected", DeviceAutomationService.getInstance() != null);
        out.put("execution_journal", ExecutionJournal.summary(context));
        out.put("recovery_modes", new JSONArray("[\"auto\",\"retry_delivery\",\"restart_observers\"]"));
        out.put("next_step", "Inspect runtime stage and runtime_logs on errors. Verify actual Android UI after recovery; never repeat an action with an unknown outcome without inspecting it first.");
        return out;
    }

    public static synchronized void record(Context context, String code, String callId, String tool) {
        try {
            JSONArray saved = new JSONArray(context.getSharedPreferences("runtime_diagnostics", 0)
                    .getString("events", "[]"));
            JSONArray bounded = new JSONArray();
            for (int i = Math.max(0, saved.length() - 79); i < saved.length(); i++) bounded.put(saved.get(i));
            JSONObject event = new JSONObject();
            event.put("time_ms", System.currentTimeMillis());
            event.put("code", safe(code, 80));
            event.put("call_id", safe(callId, 80));
            event.put("tool", safe(tool, 100));
            bounded.put(event);
            DetailedDiagnostics.event(context,"info","runtime","",callId,code,"Runtime event: "+code,null);
            context.getSharedPreferences("runtime_diagnostics", 0).edit()
                    .putString("events", bounded.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static synchronized JSONObject logs(Context context, int limit) throws Exception {
        JSONArray saved = new JSONArray(context.getSharedPreferences("runtime_diagnostics", 0)
                .getString("events", "[]"));
        JSONArray result = new JSONArray();
        for (int i = Math.max(0, saved.length() - Math.max(1, Math.min(80, limit))); i < saved.length(); i++) {
            result.put(saved.get(i));
        }
        return new JSONObject().put("ok", true).put("events", result)
                .put("contains", "Diagnostic codes and call identifiers only; no arguments, typed text, UI contents or credentials.");
    }

    private static String safe(String value, int max) {
        if (value == null || !value.matches("[A-Za-z0-9._:-]*")) return "redacted";
        return value.substring(0, Math.min(max, value.length()));
    }
}
