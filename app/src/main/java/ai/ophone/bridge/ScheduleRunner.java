package ai.ophone.bridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Executes due schedules. API platforms run headless in this process (the chat shows the run when opened); ChatGPT
 * web needs its page, so the ChatGPT screen is opened with a one-time token and does the rest (ChatActivity:
 * open the conversation, choose the model and reasoning level on the page, send).
 */
final class ScheduleRunner {
    static final String EXTRA_ID = "schedule_id", EXTRA_TOKEN = "schedule_token";
    private static final String CHANNEL = "ophoneai_schedules";
    private ScheduleRunner() {}

    static synchronized void runDue(Context context) {
        Context c = context.getApplicationContext();
        JSONArray due = ScheduleStore.takeDue(c);
        for (int i = 0; i < due.length(); i++) {
            JSONObject s = due.optJSONObject(i);
            if (s != null) run(c, s);
        }
        ScheduleStore.arm(c);
    }

    /** "Run now" from the schedule list. */
    static void runNow(Context context, String id) {
        Context c = context.getApplicationContext();
        JSONObject s = ScheduleStore.get(c, id);
        if (s != null) new Thread(() -> run(c, s), "OPhoneAI-schedule-now").start();
    }

    private static void run(Context c, JSONObject s) {
        String id = s.optString("id");
        try {
            if (PlatformSession.CHATGPT.equals(s.optString("platform"))) runWeb(c, s);
            else runApi(c, s);
        } catch (Exception e) {
            ScheduleStore.record(c, id, "failed", DetailedDiagnostics.clean(String.valueOf(e.getMessage())));
            notify(c, s, c.getString(R.string.schedule_notify_failed, s.optString("title")), DetailedDiagnostics.clean(String.valueOf(e.getMessage())), null);
        }
    }

    private static void runApi(Context c, JSONObject s) throws Exception {
        ModelProvider p = ModelProvider.forPlatform(s.optString("platform"));
        if (p == null) throw new IllegalStateException("schedule_platform_unknown");
        if (!p.store(c).ready()) throw new IllegalStateException(c.getString(R.string.model_api_not_ready, p.label));
        ApiConversationStore history = new ApiConversationStore(c);
        String conversation = target(c, history, s, p);
        if (DirectModelAgent.isConversationRunning(conversation)) { ScheduleStore.record(c, s.optString("id"), "skipped", "conversation_busy"); return; }
        DirectModelAgent agent = new DirectModelAgent(c, p);
        agent.useOverrides(s.optString("model"), s.optString("effort"));
        agent.send(s.optString("prompt"), null);
        ScheduleStore.record(c, s.optString("id"), "started", p.label + (s.optString("model").isEmpty() ? "" : " · " + s.optString("model")));
        notify(c, s, c.getString(R.string.schedule_notify_started, s.optString("title")), p.label, new Intent(c, ApiChatActivity.class));
    }

    /** The conversation this run writes into; it becomes the open one, so the chat shows the run. */
    private static String target(Context c, ApiConversationStore history, JSONObject s, ModelProvider p) {
        String mode = s.optString("conversation_mode");
        if (ScheduleStore.CONV_FIXED.equals(mode)) { history.select(s.optString("conversation")); return s.optString("conversation"); }
        if (ScheduleStore.CONV_THREAD.equals(mode) && !s.optString("thread").isEmpty()) {
            try { history.select(s.optString("thread")); return s.optString("thread"); } catch (Exception ignored) {}
        }
        String id = history.create(p.id);
        if (ScheduleStore.CONV_THREAD.equals(mode)) ScheduleStore.update(c, s.optString("id"), x -> x.put("thread", id));
        return id;
    }

    private static void runWeb(Context c, JSONObject s) throws Exception {
        String id = s.optString("id"), token = ScheduleStore.issueToken(c, id);
        String conversation = ScheduleStore.CONV_FIXED.equals(s.optString("conversation_mode")) ? s.optString("conversation")
                : ScheduleStore.CONV_THREAD.equals(s.optString("conversation_mode")) ? s.optString("thread") : "";
        String url = "https://chatgpt.com/" + (conversation.startsWith("c:") ? "c/" + conversation.substring(2) : "");
        Intent open = new Intent(c, ChatActivity.class).putExtra("url", url).putExtra(EXTRA_ID, id).putExtra(EXTRA_TOKEN, token)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // Without "display over other apps" Android does not let a background app open a screen: ask with a notification.
        boolean canOpen = (Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(c)) && !userBusyElsewhere(c);
        if (canOpen) {
            try { c.startActivity(open); ScheduleStore.record(c, id, "started", "ChatGPT"); return; } catch (Exception ignored) {}
        }
        ScheduleStore.record(c, id, "waiting", "tap_notification");
        notify(c, s, c.getString(R.string.schedule_notify_tap, s.optString("title")), c.getString(R.string.schedule_notify_tap_body), open);
    }

    /** The user is using another app right now: a scheduled run does not take over the screen, it asks instead. */
    private static boolean userBusyElsewhere(Context c) {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE);
            android.app.KeyguardManager km = (android.app.KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
            boolean inUse = (pm == null || pm.isInteractive()) && (km == null || !km.isKeyguardLocked());
            return inUse && !TaskProgress.appOnScreen();
        } catch (Exception e) { return false; }
    }

    private static void notify(Context c, JSONObject s, String title, String body, Intent open) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel(CHANNEL, c.getString(R.string.schedule_channel), NotificationManager.IMPORTANCE_HIGH));
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c).setPriority(Notification.PRIORITY_HIGH);
            Intent target = open != null ? open : new Intent(c, ScheduleActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(c, s.optString("id").hashCode(), target, PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
            nm.notify(7400 + (s.optString("id").hashCode() & 0xff), b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle(title).setContentText(body)
                    .setContentIntent(pi).setAutoCancel(true).build());
        } catch (Exception ignored) {}
    }
}
