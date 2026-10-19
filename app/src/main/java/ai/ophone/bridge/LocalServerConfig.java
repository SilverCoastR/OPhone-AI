package ai.ophone.bridge;

import android.content.Context;
import android.content.SharedPreferences;

public final class LocalServerConfig {
    public static final int DEFAULT_PORT = 8765;
    public static final int MIN_PORT = 8765;
    public static final int MAX_PORT = 8795;

    private static final String PREFS = "local_server";
    private static final String KEY_PORT = "port";

    private LocalServerConfig() {}

    public static int port(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int port = prefs.getInt(KEY_PORT, DEFAULT_PORT);
        if (port < MIN_PORT || port > MAX_PORT) return DEFAULT_PORT;
        return port;
    }

    public static void setPort(Context context, int port) {
        if (port < MIN_PORT || port > MAX_PORT) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_PORT, port)
                .apply();
    }

    public static String bind(Context context) {
        return "127.0.0.1:" + port(context);
    }
}
