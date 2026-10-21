package ai.ophone.bridge;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;

/**
 * Phone and app state changes that matter to a running task: the screen turning off or being unlocked, OPhone AI
 * going to the background and back, the network dropping, the battery running low, rotation, memory pressure.
 * They are recorded as events (phone.* and ophone.*), handed to the AI with its next tool result while a task runs,
 * and can be subscribed to or waited for like other events. No location, network names or device identity.
 * Runs in the main process only.
 */
final class PhoneEvents {
    private static boolean installed;
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static int started;private static long backgroundAt;private static String lastScreen="";
    private static String network="";private static int orientation;private static int night;
    private PhoneEvents(){}

    static synchronized void install(Application app){
        if(installed)return;installed=true;
        Configuration config=app.getResources().getConfiguration();orientation=config.orientation;night=config.uiMode&Configuration.UI_MODE_NIGHT_MASK;
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks(){
            public void onActivityStarted(Activity a){lastScreen=a.getClass().getSimpleName();
                if(started++==0&&backgroundAt>0){long away=System.currentTimeMillis()-backgroundAt;backgroundAt=0;
                    publish(app,"ophone.lifecycle","info",data("state","foreground","screen",lastScreen,"background_ms",away),"app_foreground","OPhone AI returned to the foreground after "+away/1000+" s.");}}
            public void onActivityStopped(Activity a){if(started>0&&--started==0&&!a.isChangingConfigurations()){backgroundAt=System.currentTimeMillis();
                publish(app,"ophone.lifecycle","warn",data("state","background","screen",a.getClass().getSimpleName(),"finishing",a.isFinishing()),"app_background","OPhone AI went to the background from "+a.getClass().getSimpleName()+".");}}
            public void onActivityCreated(Activity a,Bundle b){}public void onActivityResumed(Activity a){}public void onActivityPaused(Activity a){}
            public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}});
        app.registerComponentCallbacks(new ComponentCallbacks2(){
            @Override public void onConfigurationChanged(Configuration c){
                if(c.orientation!=orientation&&c.orientation!=Configuration.ORIENTATION_UNDEFINED){orientation=c.orientation;
                    publish(app,"phone.config","info",data("orientation",orientation==Configuration.ORIENTATION_LANDSCAPE?"landscape":"portrait"),"screen_rotated","The screen rotated; coordinates from earlier screenshots no longer apply.");}
                int mode=c.uiMode&Configuration.UI_MODE_NIGHT_MASK;if(mode!=night){night=mode;publish(app,"phone.config","info",data("dark_mode",mode==Configuration.UI_MODE_NIGHT_YES),"dark_mode_changed","The system dark mode changed.");}}
            @Override public void onLowMemory(){publish(app,"phone.memory","warn",data("state","low_memory"),"low_memory","The phone is low on memory; background apps may be closed.");}
            @Override public void onTrimMemory(int level){
                if(level==TRIM_MEMORY_RUNNING_CRITICAL||level==TRIM_MEMORY_COMPLETE)publish(app,"phone.memory","warn",data("state",level==TRIM_MEMORY_COMPLETE?"ophone_may_be_closed":"running_critical","trim_level",level),"memory_pressure","Android is short of memory and may close OPhone AI or the app being operated.");}});
        IntentFilter f=new IntentFilter();
        for(String action:new String[]{Intent.ACTION_SCREEN_ON,Intent.ACTION_SCREEN_OFF,Intent.ACTION_USER_PRESENT,Intent.ACTION_BATTERY_LOW,Intent.ACTION_BATTERY_OKAY,
                Intent.ACTION_POWER_CONNECTED,Intent.ACTION_POWER_DISCONNECTED,Intent.ACTION_SHUTDOWN,Intent.ACTION_AIRPLANE_MODE_CHANGED,AudioManager.RINGER_MODE_CHANGED_ACTION})f.addAction(action);
        try{app.registerReceiver(new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){onBroadcast(app,i);}},f);}
        catch(Exception e){DetailedDiagnostics.event(app,"warning","phone_events","","","phone_events_unavailable","Phone state broadcasts could not be registered.",e);}
        try{ConnectivityManager cm=(ConnectivityManager)app.getSystemService(Context.CONNECTIVITY_SERVICE);network=transport(cm);
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback(){
                @Override public void onAvailable(Network n){MAIN.postDelayed(()->networkChanged(app,cm),400);}
                @Override public void onLost(Network n){MAIN.postDelayed(()->networkChanged(app,cm),400);}
                @Override public void onCapabilitiesChanged(Network n,NetworkCapabilities caps){MAIN.postDelayed(()->networkChanged(app,cm),400);}});}
        catch(Exception e){DetailedDiagnostics.event(app,"warning","phone_events","","","network_events_unavailable","Network changes cannot be observed.",e);}
    }

    private static void onBroadcast(Context c,Intent i){String a=String.valueOf(i.getAction());
        switch(a){
            case Intent.ACTION_SCREEN_OFF:publish(c,"phone.screen","warn",data("state","off"),"screen_off","The screen turned off; other apps cannot be operated until it is on and unlocked.");break;
            case Intent.ACTION_SCREEN_ON:publish(c,"phone.screen","info",data("state","on"),"screen_on","The screen turned on (it may still be locked).");break;
            case Intent.ACTION_USER_PRESENT:publish(c,"phone.screen","info",data("state","unlocked"),"screen_unlocked","The user unlocked the phone.");break;
            case Intent.ACTION_BATTERY_LOW:publish(c,"phone.power","warn",data("state","battery_low","battery_percent",battery(c)),"battery_low","The battery is low.");break;
            case Intent.ACTION_BATTERY_OKAY:publish(c,"phone.power","info",data("state","battery_okay","battery_percent",battery(c)),"battery_okay","The battery is no longer low.");break;
            case Intent.ACTION_POWER_CONNECTED:publish(c,"phone.power","info",data("state","charger_connected","battery_percent",battery(c)),"charger_connected","A charger was connected.");break;
            case Intent.ACTION_POWER_DISCONNECTED:publish(c,"phone.power","info",data("state","charger_disconnected","battery_percent",battery(c)),"charger_disconnected","The charger was disconnected.");break;
            case Intent.ACTION_SHUTDOWN:publish(c,"phone.power","warn",data("state","shutting_down"),"phone_shutdown","The phone is shutting down.");break;
            case Intent.ACTION_AIRPLANE_MODE_CHANGED:boolean on=i.getBooleanExtra("state",false);publish(c,"phone.network",on?"warn":"info",data("airplane_mode",on),"airplane_mode",on?"Airplane mode was turned on.":"Airplane mode was turned off.");break;
            case AudioManager.RINGER_MODE_CHANGED_ACTION:int m=i.getIntExtra(AudioManager.EXTRA_RINGER_MODE,-1);String mode=m==AudioManager.RINGER_MODE_SILENT?"silent":m==AudioManager.RINGER_MODE_VIBRATE?"vibrate":"normal";
                publish(c,"phone.audio","info",data("ringer",mode),"ringer_mode","The ringer mode changed to "+mode+".");break;
            default:break;
        }
    }

    private static void networkChanged(Context c,ConnectivityManager cm){String now=transport(cm);if(now.equals(network))return;String before=network;network=now;
        publish(c,"phone.network","none".equals(now)?"warn":"info",data("transport",now,"connected",!"none".equals(now),"previous",before),"network_changed",
                "none".equals(now)?"The phone lost its network connection.":"The network is now "+now+".");}
    /** Only the kind of connection: never network names, addresses or operators. */
    private static String transport(ConnectivityManager cm){try{Network n=cm.getActiveNetwork();NetworkCapabilities caps=n==null?null:cm.getNetworkCapabilities(n);
        if(caps==null||!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))return "none";
        if(caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))return "vpn";if(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))return "wifi";
        if(caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))return "cellular";if(caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))return "ethernet";return "other";}
        catch(Exception e){return "unknown";}}
    private static int battery(Context c){try{return ((BatteryManager)c.getSystemService(Context.BATTERY_SERVICE)).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);}catch(Exception e){return -1;}}

    private static JSONObject data(Object... pairs){JSONObject o=new JSONObject();try{for(int i=0;i+1<pairs.length;i+=2)o.put(String.valueOf(pairs[i]),pairs[i+1]);}catch(Exception ignored){}return o;}
    /** Event store for the AI plus an info line in the diagnostics log (so feedback logs show it without counting as a fault). */
    private static void publish(Context c,String type,String level,JSONObject data,String code,String text){
        try{data.put("code",code).put("summary",text);}catch(Exception ignored){}
        DetailedDiagnostics.event(c,"info","phone_events","","",code,text,null);
        AgentEvents.get(c).phone(type,level,data);
    }
}
