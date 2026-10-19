package ai.ophone.bridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.security.SecureRandom;

public final class LocalMcpSecurity {
    private static final String PREFS = "local_mcp";
    private static final String KEY_TOKEN = "token";

    private LocalMcpSecurity() {}

    public static String token(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String value = prefs.getString(KEY_TOKEN, "");
        if (value != null && value.length() >= 32) return value;

        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        value = Base64.encodeToString(bytes,
                Base64.NO_WRAP | Base64.URL_SAFE | Base64.NO_PADDING);
        prefs.edit().putString(KEY_TOKEN, value).apply();
        return value;
    }

    public static boolean matches(Context context, String candidate) {
        if (candidate == null || candidate.length() < 32) return false;
        String expected = token(context);
        if (expected.length() != candidate.length()) return false;
        int diff = 0;
        for (int i = 0; i < expected.length(); i++) {
            diff |= expected.charAt(i) ^ candidate.charAt(i);
        }
        return diff == 0;
    }
}
