package ai.ophone.bridge;

import android.content.Context;
import android.content.SharedPreferences;

public final class RelayConfig {
    private static final String PREFS = "relay";
    private static final String KEY_URL = "base_url";
    private static final String KEY_DEVICE = "device_id";
    private static final String KEY_SECRET = "secret";

    public static final class Config {
        public final String baseUrl;
        public final String deviceId;
        public final String secret;

        Config(String baseUrl, String deviceId, String secret) {
            String url = baseUrl == null ? "" : baseUrl.trim();
            while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
            this.baseUrl = url;
            this.deviceId = deviceId == null ? "" : deviceId.trim();
            this.secret = secret == null ? "" : secret.trim();
        }

        public boolean isReady() {
            return (baseUrl.startsWith("https://") || baseUrl.startsWith("http://127.0.0.1"))
                    && !deviceId.isEmpty() && secret.length() >= 16;
        }
    }

    private RelayConfig() {}

    public static Config load(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new Config(
                p.getString(KEY_URL, ""),
                p.getString(KEY_DEVICE, ""),
                p.getString(KEY_SECRET, ""));
    }

    public static void save(Context context, String url, String deviceId, String secret) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_URL, url == null ? "" : url)
                .putString(KEY_DEVICE, deviceId == null ? "" : deviceId)
                .putString(KEY_SECRET, secret == null ? "" : secret)
                .apply();
    }
}
