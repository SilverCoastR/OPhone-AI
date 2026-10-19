package ai.ophone.bridge;

import android.content.Context;

/** Remembers the user's selected control platform without storing credentials. */
final class PlatformPreference {
    static final String EXTRA_SHOW_CHOOSER="show_platform_chooser";
    private static final String PREFS="platform_preference_v1",KEY="selected_provider";
    private PlatformPreference(){}
    static String get(Context context){String value=context.getSharedPreferences(PREFS,0).getString(KEY,"");return valid(value)?value:"";}
    static void set(Context context,String provider){if(!valid(provider))throw new IllegalArgumentException("invalid_provider");context.getSharedPreferences(PREFS,0).edit().putString(KEY,provider).apply();}
    static void clear(Context context){context.getSharedPreferences(PREFS,0).edit().remove(KEY).apply();}
    static boolean valid(String provider){return PlatformSession.CHATGPT.equals(provider)||PlatformSession.DEEPSEEK.equals(provider);}
    static IntentTarget target(Context context,String provider){return PlatformSession.DEEPSEEK.equals(provider)?new IntentTarget(ApiChatActivity.class):new IntentTarget(ChatActivity.class);}
    static final class IntentTarget {final Class<?> activity;IntentTarget(Class<?> activity){this.activity=activity;}}
}
