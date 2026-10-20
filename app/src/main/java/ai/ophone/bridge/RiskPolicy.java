package ai.ophone.bridge;

import android.content.Context;
import android.content.SharedPreferences;

public final class RiskPolicy {
    private static final String PREFS = "risk_policy";
    public static final String KEY_AUTOMATION = "automation_enabled";
    public static final String KEY_PACKAGE_CHANGES = "package_changes_enabled";
    public static final String KEY_DOWNLOADS = "downloads_enabled";
    public static final String KEY_BOOT = "boot_enabled";
    public static final String KEY_OVERLAY = "overlay_enabled";
    public static final String KEY_PIP = "automation_pip_enabled";
    public static final String KEY_ROOT = "root_shell_enabled";

    private RiskPolicy() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean automationEnabled(Context c) {
        return prefs(c).getBoolean(KEY_AUTOMATION, true);
    }

    public static boolean packageChangesEnabled(Context c) {
        return prefs(c).getBoolean(KEY_PACKAGE_CHANGES, false);
    }

    public static boolean downloadsEnabled(Context c) {
        return prefs(c).getBoolean(KEY_DOWNLOADS, true);
    }

    public static boolean bootEnabled(Context c) {
        return prefs(c).getBoolean(KEY_BOOT, true);
    }

    public static boolean overlayEnabled(Context c) {
        return prefs(c).getBoolean(KEY_OVERLAY, false);
    }

    public static boolean pipEnabled(Context c) {
        return prefs(c).getBoolean(KEY_PIP, true);
    }

    /** Task size level: {state-changing actions, model tool calls per run, task minutes}. */
    public static final String KEY_BUDGET = "task_budget_level";
    public static final int[][] BUDGETS = {{48, 80, 30}, {150, 250, 90}, {400, 600, 240}};
    public static int budgetLevel(Context c) {
        return Math.max(0, Math.min(BUDGETS.length - 1, prefs(c).getInt(KEY_BUDGET, 1)));
    }
    public static int[] budget(Context c) { return BUDGETS[budgetLevel(c)]; }
    public static void setBudgetLevel(Context c, int level) { prefs(c).edit().putInt(KEY_BUDGET, level).apply(); }

    public static boolean rootShellEnabled(Context c) {return RootAccess.enabled(c);}

    public static void set(Context c, String key, boolean value) {
        prefs(c).edit().putBoolean(key, value).apply();
    }
}
