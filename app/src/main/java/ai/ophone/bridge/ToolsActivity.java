package ai.ophone.bridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every built-in tool, grouped. A group with a screen of its own (the browser, memory, mini apps) opens it; the
 * others are shown grey: only the AI calls them during a task.
 */
public final class ToolsActivity extends Activity {
    @Override protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(base); UiKit.applyTheme(this); }
    private LinearLayout list;

    /** Group id by tool name. */
    private static String group(String tool) {
        if (tool.startsWith("browser_") || tool.startsWith("stream_record")) return "browser";
        if (tool.startsWith("memory_")) return "memory";
        if (tool.startsWith("miniapp_")) return "miniapps";
        if (tool.startsWith("screen_record")) return "recording";
        if (tool.startsWith("terminal_") || tool.startsWith("workbench_") || tool.startsWith("workspace_") || tool.startsWith("program_") || tool.equals("python_run") || tool.startsWith("root_")) return "files";
        if (tool.equals("http_request") || tool.equals("download_file") || tool.equals("open_url")) return "network";
        if (tool.startsWith("android_") || tool.equals("install_apk") || tool.equals("uninstall_app")) return "system";
        if (tool.startsWith("task_") || tool.startsWith("event_") || tool.equals("capabilities") || tool.startsWith("runtime_") || tool.equals("ask_user") || tool.equals("return_to_chat") || tool.startsWith("diagnostic")) return "tasks";
        if (tool.equals("tap") || tool.equals("swipe") || tool.equals("click_text") || tool.equals("input_text") || tool.equals("press_global") || tool.equals("launch_app")
                || tool.equals("get_ui") || tool.equals("screenshot") || tool.equals("find_apps") || tool.equals("wait_for_ui") || tool.startsWith("ui_")) return "phone";
        return "other";
    }
    private static final String[] ORDER = {"browser", "memory", "miniapps", "phone", "files", "network", "system", "recording", "tasks", "other"};
    private int groupTitle(String g) {
        switch (g) {
            case "browser": return R.string.tools_group_browser;
            case "memory": return R.string.tools_group_memory;
            case "miniapps": return R.string.tools_group_miniapps;
            case "phone": return R.string.tools_group_phone;
            case "files": return R.string.tools_group_files;
            case "network": return R.string.tools_group_network;
            case "system": return R.string.tools_group_system;
            case "recording": return R.string.tools_group_recording;
            case "tasks": return R.string.tools_group_tasks;
            default: return R.string.tools_group_other;
        }
    }
    /** What the user can open for a group, or null when only the AI uses it. */
    private Runnable action(String g) {
        switch (g) {
            case "browser":
                if (Build.VERSION.SDK_INT < 28) return null;
                return () -> startActivity(new Intent().setComponent(new android.content.ComponentName(getPackageName(), WorkbenchClient.BROWSER)));
            case "memory": return () -> startActivity(new Intent(this, MemoryActivity.class));
            case "miniapps": return this::chooseMiniApp;
            default: return null;
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = UiKit.page(this);
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        page.addView(UiKit.title(this, getString(R.string.tools_title)));
        page.addView(UiKit.subtitle(this, getString(R.string.tools_intro)));
        page.addView(UiKit.spacer(this, 12));
        list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); page.addView(list);
        setContentView(scroll);
        new Thread(() -> {
            JSONArray tools;
            try { tools = new LocalToolKernel(this).mcpTools(); } catch (Exception e) { tools = new JSONArray(); }
            JSONArray done = tools;
            runOnUiThread(() -> { if (!isFinishing()) render(done); });
        }, "OPhoneAI-tools").start();
    }

    private void render(JSONArray tools) {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (String g : ORDER) groups.put(g, new ArrayList<>());
        for (int i = 0; i < tools.length(); i++) {
            JSONObject t = tools.optJSONObject(i); if (t == null) continue;
            String name = t.optString("name"); if (name.isEmpty()) continue;
            groups.get(group(name)).add(name);
        }
        for (String g : ORDER) {
            List<String> names = groups.get(g);
            if (names.isEmpty()) continue;
            Runnable open = action(g);
            list.addView(UiKit.section(this, getString(groupTitle(g)) + " · " + names.size()));
            LinearLayout card = UiKit.card(this);
            if (open != null) {
                android.widget.Button button = UiKit.actionButton(this, getString(R.string.tools_open, getString(groupTitle(g))));
                button.setOnClickListener(v -> open.run());
                card.addView(button, new LinearLayout.LayoutParams(-1, -2));
            }
            for (String name : names) card.addView(row(name, open));
            list.addView(card, UiKit.cardParams(this));
        }
        TextView log = UiKit.body(this, getString(R.string.tools_activity_log));
        log.setGravity(Gravity.CENTER);
        log.setTextColor(UiKit.accentColor(this));
        log.setPadding(0, UiKit.dp(this, 8), 0, UiKit.dp(this, 8));
        log.setOnClickListener(v -> startActivity(new Intent(this, AuditActivity.class)));
        list.addView(log);
    }

    private View row(String name, Runnable open) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, UiKit.dp(this, 7), 0, UiKit.dp(this, 7));
        TextView label = new TextView(this);
        label.setText(TaskProgress.label(name));
        label.setTextSize(15.5f);
        label.setTextColor(UiKit.primaryTextColor(this));
        label.setTypeface(Typeface.DEFAULT_BOLD);
        TextView id = new TextView(this);
        id.setText(open == null ? name + " · " + getString(R.string.tools_ai_only) : name);
        id.setTextSize(12.5f);
        id.setTextColor(UiKit.secondaryTextColor(this));
        row.addView(label); row.addView(id);
        if (open == null) {
            row.setAlpha(0.45f);
            row.setOnClickListener(v -> Toast.makeText(this, R.string.tools_ai_only_toast, Toast.LENGTH_SHORT).show());
        } else row.setOnClickListener(v -> open.run());
        return row;
    }

    private void chooseMiniApp() {
        try {
            JSONArray apps = MiniAppStore.list(this).getJSONArray("apps");
            if (apps.length() == 0) { Toast.makeText(this, R.string.tools_no_miniapps, Toast.LENGTH_SHORT).show(); return; }
            String[] names = new String[apps.length()], labels = new String[apps.length()];
            for (int i = 0; i < apps.length(); i++) {
                JSONObject a = apps.getJSONObject(i);
                names[i] = a.optString("name");
                labels[i] = a.optString("title", names[i]).isEmpty() ? names[i] : a.optString("title", names[i]);
            }
            new AlertDialog.Builder(this).setTitle(R.string.tools_group_miniapps)
                    .setItems(labels, (d, w) -> startActivity(MiniAppActivity.intent(this, names[w]))).show();
        } catch (Exception e) {
            Toast.makeText(this, R.string.tools_no_miniapps, Toast.LENGTH_SHORT).show();
        }
    }
}
