package ai.ophone.bridge;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Calendar;
import java.util.UUID;

/**
 * Scheduled tasks: a prompt sent at a set time, every N minutes, or every day at a time, to a chosen platform
 * (ChatGPT web or an API platform), optionally with a model and reasoning level, into a new conversation each time,
 * one conversation that every run continues, or an existing conversation. Stored on the phone only.
 *
 * One alarm is kept for the earliest due schedule; when it fires every due schedule runs (ScheduleRunner) and the
 * alarm is set again. A run missed while the phone was off runs once when the alarms are restored.
 */
final class ScheduleStore {
    static final String ONCE = "once", INTERVAL = "interval", DAILY = "daily";
    static final String CONV_NEW = "new", CONV_THREAD = "thread", CONV_FIXED = "fixed";
    static final long MIN_INTERVAL_MIN = 5;
    private static final String PREFS = "schedules_v1";
    private static final Object LOCK = new Object();
    private ScheduleStore() {}

    private static SharedPreferences prefs(Context c) { return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static JSONArray all(Context c) {
        synchronized (LOCK) { try { return new JSONArray(prefs(c).getString("items", "[]")); } catch (Exception e) { return new JSONArray(); } }
    }
    private static void write(Context c, JSONArray items) { prefs(c).edit().putString("items", items.toString()).commit(); }

    static JSONObject get(Context c, String id) {
        JSONArray items = all(c);
        for (int i = 0; i < items.length(); i++) { JSONObject s = items.optJSONObject(i); if (s != null && s.optString("id").equals(id)) return s; }
        return null;
    }

    /** Validates and stores a schedule (new when it has no id), computes its next run and re-arms the alarm. */
    static JSONObject save(Context c, JSONObject in) throws Exception {
        JSONObject s = new JSONObject(in.toString());
        String prompt = s.optString("prompt").trim();
        if (prompt.isEmpty()) throw new IllegalArgumentException("schedule_prompt_required");
        if (prompt.length() > 8000) throw new IllegalArgumentException("schedule_prompt_too_long");
        s.put("prompt", prompt);
        String title = s.optString("title").trim();
        s.put("title", (title.isEmpty() ? prompt.replaceAll("\\s+", " ") : title).substring(0, Math.min(60, (title.isEmpty() ? prompt.replaceAll("\\s+", " ") : title).length())));
        String mode = s.optString("mode");
        if (!ONCE.equals(mode) && !INTERVAL.equals(mode) && !DAILY.equals(mode)) throw new IllegalArgumentException("schedule_mode_invalid");
        if (INTERVAL.equals(mode) && s.optLong("interval_min") < MIN_INTERVAL_MIN) throw new IllegalArgumentException("schedule_interval_too_short");
        if (DAILY.equals(mode) && !s.optString("time").matches("([01]\\d|2[0-3]):[0-5]\\d")) throw new IllegalArgumentException("schedule_time_invalid");
        if (!DAILY.equals(mode) && s.optLong("at") <= 0) throw new IllegalArgumentException("schedule_start_required");
        String platform = s.optString("platform");
        if (!PlatformPreference.valid(platform)) throw new IllegalArgumentException("schedule_platform_invalid");
        String conv = s.optString("conversation_mode", CONV_NEW);
        if (!CONV_NEW.equals(conv) && !CONV_THREAD.equals(conv) && !CONV_FIXED.equals(conv)) conv = CONV_NEW;
        if (CONV_FIXED.equals(conv) && s.optString("conversation").isEmpty()) throw new IllegalArgumentException("schedule_conversation_required");
        s.put("conversation_mode", conv);
        if (s.optString("id").isEmpty()) s.put("id", "s-" + UUID.randomUUID().toString().substring(0, 13)).put("created_at", System.currentTimeMillis()).put("enabled", true);
        long now = System.currentTimeMillis();
        if (ONCE.equals(mode) && s.optLong("at") <= now) throw new IllegalArgumentException("schedule_time_in_past");
        s.put("next_at", s.optBoolean("enabled", true) ? next(s, now - 1) : 0);
        synchronized (LOCK) {
            JSONArray items = all(c), out = new JSONArray(); boolean replaced = false;
            for (int i = 0; i < items.length(); i++) {
                JSONObject old = items.optJSONObject(i); if (old == null) continue;
                if (old.optString("id").equals(s.getString("id"))) {
                    // Keep run history; a changed target conversation starts a new thread.
                    for (String k : new String[]{"last_at", "last_status", "last_detail", "run_count", "created_at"}) if (old.has(k) && !s.has(k)) s.put(k, old.get(k));
                    if (old.optString("platform").equals(platform) && old.optString("conversation_mode").equals(conv) && old.has("thread")) s.put("thread", old.get("thread"));
                    out.put(s); replaced = true;
                } else out.put(old);
            }
            if (!replaced) out.put(s);
            write(c, out);
        }
        arm(c);
        return s;
    }

    static void delete(Context c, String id) {
        synchronized (LOCK) { JSONArray items = all(c), out = new JSONArray();
            for (int i = 0; i < items.length(); i++) { JSONObject s = items.optJSONObject(i); if (s != null && !s.optString("id").equals(id)) out.put(s); }
            write(c, out); }
        arm(c);
    }

    static void setEnabled(Context c, String id, boolean on) {
        update(c, id, s -> { s.put("enabled", on); s.put("next_at", on ? next(s, System.currentTimeMillis()) : 0); if (on && s.optLong("next_at") <= 0) s.put("enabled", false); });
        arm(c);
    }

    interface Edit { void apply(JSONObject s) throws Exception; }
    static JSONObject update(Context c, String id, Edit edit) {
        synchronized (LOCK) {
            JSONArray items = all(c); JSONObject hit = null;
            for (int i = 0; i < items.length(); i++) { JSONObject s = items.optJSONObject(i); if (s != null && s.optString("id").equals(id)) { try { edit.apply(s); hit = s; } catch (Exception ignored) {} } }
            if (hit != null) write(c, items);
            return hit;
        }
    }

    /** The first run strictly after `after`, or 0 when a one-time schedule has passed. */
    static long next(JSONObject s, long after) {
        String mode = s.optString("mode");
        if (ONCE.equals(mode)) { long at = s.optLong("at"); return at > after ? at : 0; }
        if (INTERVAL.equals(mode)) {
            long start = s.optLong("at"), step = Math.max(MIN_INTERVAL_MIN, s.optLong("interval_min")) * 60000L;
            if (start > after) return start;
            return start + ((after - start) / step + 1) * step;
        }
        String[] hm = s.optString("time", "08:00").split(":");
        Calendar cal = Calendar.getInstance(); cal.setTimeInMillis(after);
        cal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(hm[0])); cal.set(Calendar.MINUTE, Integer.parseInt(hm[1])); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= after) cal.add(Calendar.DAY_OF_YEAR, 1);
        return cal.getTimeInMillis();
    }

    /** Takes every enabled schedule that is due now and moves it to its next run; returns the taken ones. */
    static JSONArray takeDue(Context c) {
        long now = System.currentTimeMillis(); JSONArray due = new JSONArray();
        synchronized (LOCK) {
            JSONArray items = all(c);
            for (int i = 0; i < items.length(); i++) {
                JSONObject s = items.optJSONObject(i);
                if (s == null || !s.optBoolean("enabled", true) || s.optLong("next_at") <= 0 || s.optLong("next_at") > now + 30000) continue;
                try {
                    due.put(new JSONObject(s.toString()).put("late_ms", Math.max(0, now - s.optLong("next_at"))));
                    long following = next(s, now + 30000);
                    s.put("next_at", following);
                    if (following <= 0) s.put("enabled", false);
                } catch (Exception ignored) {}
            }
            if (due.length() > 0) write(c, items);
        }
        return due;
    }

    /** Records what a run did, shown on the schedule list ("started", "sent", "skipped", "failed"). */
    static void record(Context c, String id, String status, String detail) {
        update(c, id, s -> { s.put("last_at", System.currentTimeMillis()).put("last_status", status).put("last_detail", detail == null ? "" : detail);
            if ("started".equals(status) || "sent".equals(status)) s.put("run_count", s.optInt("run_count") + 1); });
        DetailedDiagnostics.event(c, "failed".equals(status) ? "warning" : "info", "schedule", "", "", "schedule_" + status, id + (detail == null || detail.isEmpty() ? "" : ": " + detail), null);
    }

    /** One-time token for the ChatGPT screen, so only a run this app started can make it send a scheduled prompt. */
    static String issueToken(Context c, String id) {
        String token = UUID.randomUUID().toString();
        update(c, id, s -> s.put("token", token).put("token_at", System.currentTimeMillis()));
        return token;
    }
    static JSONObject redeem(Context c, String id, String token) {
        if (id == null || token == null) return null;
        JSONObject[] out = {null};
        update(c, id, s -> { if (token.equals(s.optString("token")) && System.currentTimeMillis() - s.optLong("token_at") < 6 * 3600 * 1000L) { out[0] = new JSONObject(s.toString()); s.remove("token"); } });
        return out[0];
    }

    /** Sets the single alarm for the earliest due schedule; a missed one becomes due right away. */
    static void arm(Context c) {
        Context app = c.getApplicationContext();
        long earliest = 0; JSONArray items = all(app);
        for (int i = 0; i < items.length(); i++) {
            JSONObject s = items.optJSONObject(i);
            if (s == null || !s.optBoolean("enabled", true) || s.optLong("next_at") <= 0) continue;
            if (earliest == 0 || s.optLong("next_at") < earliest) earliest = s.optLong("next_at");
        }
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = PendingIntent.getBroadcast(app, 7401, new Intent(app, ScheduleReceiver.class).setAction(ScheduleReceiver.ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        if (earliest == 0) { am.cancel(pi); return; }
        long at = Math.max(System.currentTimeMillis() + 1000, earliest);
        try {
            if (exactAllowed(app)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (SecurityException e) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi); }
    }
    /** Android 12+ asks the user before an app may use exact alarms; without it runs can be a few minutes late. */
    static boolean exactAllowed(Context c) {
        if (Build.VERSION.SDK_INT < 31) return true;
        try { return ((AlarmManager) c.getSystemService(Context.ALARM_SERVICE)).canScheduleExactAlarms(); } catch (Exception e) { return false; }
    }
}
