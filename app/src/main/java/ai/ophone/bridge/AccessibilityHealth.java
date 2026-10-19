package ai.ophone.bridge;

import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import org.json.JSONObject;

/** Separates the owner's enabled setting from an actually live Android service. */
public final class AccessibilityHealth {
    private static String lastState="";
    private AccessibilityHealth() {}
    public static boolean enabled(Context context) {
        ComponentName own=new ComponentName(context,DeviceAutomationService.class);
        String list=Settings.Secure.getString(context.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if(list==null)return false;
        for(String entry:list.split(":"))if(own.equals(ComponentName.unflattenFromString(entry)))return true;
        return false;
    }
    public static synchronized JSONObject status(Context context) throws Exception {
        boolean connected=DeviceAutomationService.getInstance()!=null,enabled=enabled(context);
        return new JSONObject().put("ok",true).put("enabled_by_user",enabled).put("connected",connected)
                .put("self_rebind_available",false)
                .put("next_step",connected?"ready":enabled?"Android has not bound the enabled accessibility service. Wait briefly and recheck; if still disconnected, report that Android settings must reconnect it. No public self-rebind API is available.":"Owner must enable accessibility in Android settings; the app cannot grant it itself.");
    }
    public static void supervise(Context context) {
        try {
            JSONObject health=status(context);String state=health.getBoolean("enabled_by_user")+":"+health.getBoolean("connected");
            if(state.equals(lastState))return;lastState=state;
            DetailedDiagnostics.event(context,health.getBoolean("connected")?"info":"warn","accessibility","","","accessibility_health_changed",health.toString(),null);
            if(!health.getBoolean("connected")&&RiskPolicy.automationEnabled(context)) {
                org.json.JSONArray tasks=AgentStore.get(context).tasks(null,true);
                for(int i=0;i<tasks.length();i++)TaskController.get(context).reportRuntimeFault(tasks.getJSONObject(i).getString("id"),"accessibility_connection_lost",health.getString("next_step"));
            }
        }catch(Exception error) {
            DetailedDiagnostics.event(context,"error","accessibility","","","accessibility_health_check_failed","Android accessibility health check failed.",error);
        }
    }
}
