package ai.ophone.bridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** The schedule alarm fired: run what is due inside the foreground service, so the process stays alive for the task. */
public final class ScheduleReceiver extends BroadcastReceiver {
    static final String ACTION = "ai.ophone.bridge.SCHEDULE_ALARM";

    @Override public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction())) return;
        Context app = context.getApplicationContext();
        Intent service = new Intent(app, BridgeService.class).setAction(BridgeService.ACTION_RUN_SCHEDULES);
        try {
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(service); else app.startService(service);
        } catch (Exception e) {
            // Background service starts can be refused (Android 12+ without exact alarms): run here instead.
            PendingResult result = goAsync();
            new Thread(() -> { try { ScheduleRunner.runDue(app); } finally { result.finish(); } }, "OPhoneAI-schedule").start();
        }
    }
}
