package ai.ophone.bridge;

import android.app.Activity;
import org.json.JSONObject;

/** The chat drawer's update button: one tap downloads (if needed) and then opens the system installer. */
final class ChatUpdate {
    private final Activity activity;private final Runnable onChange;private final Runnable listener;private volatile boolean installWhenReady;

    ChatUpdate(Activity activity,Runnable onChange){this.activity=activity;this.onChange=onChange;
        listener=()->{if(installWhenReady&&"downloaded".equals(UpdateManager.status(activity).optString("state"))){installWhenReady=false;UpdateManager.install(activity);}onChange.run();};}

    void attach(){UpdateManager.addListener(listener);listener.run();}
    void detach(){UpdateManager.removeListener(listener);}

    /** {available, state, progress}: available while a newer release can be downloaded, is downloading or is ready. */
    JSONObject json(){JSONObject s=UpdateManager.status(activity),out=new JSONObject();String st=s.optString("state");
        try{out.put("state",st).put("available",available(s));long d=s.optLong("downloaded"),t=s.optLong("total");out.put("progress",t>0?(int)(d*100/t):-1);}catch(Exception ignored){}
        return out;}

    void act(){JSONObject s=UpdateManager.status(activity);String st=s.optString("state");
        if("downloaded".equals(st)){UpdateManager.install(activity);return;}
        if("downloading".equals(st))return;
        if(available(s)){installWhenReady=true;UpdateManager.download(activity);}}

    private static boolean available(JSONObject s){String st=s.optString("state");JSONObject latest=s.optJSONObject("latest");
        if("available".equals(st)||"downloading".equals(st)||"downloaded".equals(st))return true;
        return "failed".equals(st)&&latest!=null&&UpdateManager.newer(latest.optString("tag"),BuildConfig.VERSION_NAME);}
}
