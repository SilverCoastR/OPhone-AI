package ai.ophone.bridge;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

public final class AppControlPolicy {
    private static final String PREFS = "app_control_policy";
    private static final String KEY_BLOCKED = "blocked_packages";

    private AppControlPolicy() {}

    public static boolean isAllowed(Context context, String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) return true;
        if (context.getPackageName().equals(packageName)) return true;
        return !blocked(context).contains(packageName);
    }

    public static void setAllowed(Context context, String packageName, boolean allowed) {
        if (packageName == null || packageName.trim().isEmpty()) return;
        if (context.getPackageName().equals(packageName)) return;

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> next = new HashSet<>(prefs.getStringSet(KEY_BLOCKED, new HashSet<>()));
        if (allowed) next.remove(packageName);
        else next.add(packageName);
        prefs.edit().putStringSet(KEY_BLOCKED, next).apply();
    }

    public static Set<String> blocked(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new HashSet<>(prefs.getStringSet(KEY_BLOCKED, new HashSet<>()));
    }
}
