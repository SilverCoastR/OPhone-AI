package ai.ophone.bridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.DateFormat;
import java.util.Date;

/** Scheduled tasks: what runs when, where, and what the last run did. */
public final class ScheduleActivity extends Activity {
    @Override protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(base); UiKit.applyTheme(this); }
    private LinearLayout page, list, warnings;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        page = UiKit.page(this);
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        page.addView(UiKit.title(this, getString(R.string.schedule_title)));
        page.addView(UiKit.subtitle(this, getString(R.string.schedule_intro)));
        page.addView(UiKit.spacer(this, 12));
        warnings = new LinearLayout(this); warnings.setOrientation(LinearLayout.VERTICAL); page.addView(warnings);
        Button add = UiKit.actionButton(this, getString(R.string.schedule_add));
        add.setOnClickListener(v -> startActivity(new Intent(this, ScheduleEditActivity.class)));
        LinearLayout addCard = UiKit.card(this); addCard.addView(add, new LinearLayout.LayoutParams(-1, -2)); page.addView(addCard, UiKit.cardParams(this));
        list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); page.addView(list);
        setContentView(scroll);
    }

    @Override protected void onResume() { super.onResume(); render(); }

    private void render() {
        warnings.removeAllViews(); list.removeAllViews();
        JSONArray items = ScheduleStore.all(this);
        boolean web = false;
        for (int i = 0; i < items.length(); i++) if (PlatformSession.CHATGPT.equals(items.optJSONObject(i).optString("platform"))) web = true;
        if (!ScheduleStore.exactAllowed(this) && Build.VERSION.SDK_INT >= 31)
            warning(R.string.schedule_exact_warning, R.string.schedule_exact_grant, () -> startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName()))));
        if (web && Build.VERSION.SDK_INT >= 29 && !Settings.canDrawOverlays(this))
            warning(R.string.schedule_overlay_warning, R.string.schedule_overlay_grant, () -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        if (items.length() == 0) {
            TextView empty = UiKit.body(this, getString(R.string.schedule_empty)); empty.setGravity(Gravity.CENTER); empty.setPadding(0, UiKit.dp(this, 24), 0, 0);
            list.addView(empty); return;
        }
        for (int i = 0; i < items.length(); i++) list.addView(card(items.optJSONObject(i)), UiKit.cardParams(this));
    }

    private void warning(int text, int action, Runnable run) {
        LinearLayout card = UiKit.card(this);
        TextView t = UiKit.body(this, getString(text)); t.setTextSize(14); card.addView(t);
        Button b = UiKit.plainButton(this, getString(action), false); b.setOnClickListener(v -> { try { run.run(); } catch (Exception ignored) {} });
        card.addView(b, new LinearLayout.LayoutParams(-1, UiKit.dp(this, 46)));
        warnings.addView(card, UiKit.cardParams(this));
    }

    private View card(JSONObject s) {
        String id = s.optString("id");
        LinearLayout card = UiKit.card(this);
        LinearLayout head = new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this); title.setText(s.optString("title")); title.setTextSize(17); title.setTypeface(Typeface.DEFAULT_BOLD); title.setTextColor(UiKit.primaryTextColor(this));
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Switch on = new Switch(this); on.setChecked(s.optBoolean("enabled", true));
        on.setOnCheckedChangeListener((b, checked) -> { ScheduleStore.setEnabled(this, id, checked); render(); });
        head.addView(on);
        card.addView(head);
        card.addView(line(when(this, s)));
        card.addView(line(target(this, s)));
        long next = s.optLong("next_at");
        card.addView(line(s.optBoolean("enabled", true) && next > 0 ? getString(R.string.schedule_next, format(next)) : getString(R.string.schedule_finished)));
        if (s.optLong("last_at") > 0) {
            String detail = s.optString("last_detail");
            card.addView(line(getString(R.string.schedule_last, format(s.optLong("last_at")), status(s.optString("last_status")) + (detail.isEmpty() ? "" : " · " + detail))));
        }
        LinearLayout actions = new LinearLayout(this); actions.setPadding(0, UiKit.dp(this, 6), 0, 0);
        actions.addView(button(R.string.schedule_run_now, false, () -> { ScheduleRunner.runNow(this, id); Toast.makeText(this, R.string.schedule_run_started, Toast.LENGTH_SHORT).show(); list.postDelayed(this::render, 1500); }), weight());
        actions.addView(button(R.string.schedule_edit, false, () -> startActivity(new Intent(this, ScheduleEditActivity.class).putExtra("id", id))), weight());
        actions.addView(button(R.string.delete_chat, true, () -> new AlertDialog.Builder(this).setTitle(R.string.schedule_delete_title).setMessage(s.optString("title"))
                .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.delete_chat, (d, w) -> { ScheduleStore.delete(this, id); render(); }).show()), weight());
        card.addView(actions);
        return card;
    }
    private LinearLayout.LayoutParams weight() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, UiKit.dp(this, 44), 1); p.setMargins(UiKit.dp(this, 2), 0, UiKit.dp(this, 2), 0); return p; }
    private Button button(int text, boolean danger, Runnable run) { Button b = UiKit.plainButton(this, getString(text), danger); b.setTextSize(14); b.setOnClickListener(v -> run.run()); return b; }
    private TextView line(String text) { TextView t = UiKit.body(this, text); t.setTextSize(14); t.setPadding(0, UiKit.dp(this, 2), 0, 0); return t; }
    private String format(long at) { return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(at)); }
    private String status(String s) {
        switch (s) {
            case "started": return getString(R.string.schedule_status_started);
            case "sent": return getString(R.string.schedule_status_sent);
            case "skipped": return getString(R.string.schedule_status_skipped);
            case "waiting": return getString(R.string.schedule_status_waiting);
            case "failed": return getString(R.string.schedule_status_failed);
            default: return s;
        }
    }

    /** "Every day 08:00", "Every 2 hours from …", "Once at …". */
    static String when(Context c, JSONObject s) {
        String mode = s.optString("mode");
        if (ScheduleStore.DAILY.equals(mode)) return c.getString(R.string.schedule_when_daily, s.optString("time"));
        String start = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(s.optLong("at")));
        if (ScheduleStore.ONCE.equals(mode)) return c.getString(R.string.schedule_when_once, start);
        return c.getString(R.string.schedule_when_interval, every(c, s.optLong("interval_min")), start);
    }
    static String every(Context c, long minutes) {
        if (minutes % 1440 == 0) return c.getString(R.string.schedule_unit_days_n, minutes / 1440);
        if (minutes % 60 == 0) return c.getString(R.string.schedule_unit_hours_n, minutes / 60);
        return c.getString(R.string.schedule_unit_minutes_n, minutes);
    }
    /** "ChatGPT web · Thinking · new conversation each time". */
    static String target(Context c, JSONObject s) {
        String platform = s.optString("platform");
        ModelProvider p = ModelProvider.forPlatform(platform);
        StringBuilder b = new StringBuilder(p == null ? c.getString(R.string.platform_chatgpt) : p.label);
        if (!s.optString("model").isEmpty()) b.append(" · ").append(s.optString("model"));
        if (!s.optString("effort").isEmpty()) b.append(" · ").append(s.optString("effort"));
        String mode = s.optString("conversation_mode");
        b.append(" · ").append(ScheduleStore.CONV_FIXED.equals(mode) ? c.getString(R.string.schedule_conv_fixed_named, s.optString("conversation_title", s.optString("conversation")))
                : ScheduleStore.CONV_THREAD.equals(mode) ? c.getString(R.string.schedule_conv_thread) : c.getString(R.string.schedule_conv_new));
        return b.toString();
    }
}
