package ai.ophone.bridge;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.StatFs;
import android.util.DisplayMetrics;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Structured Android system access without compiling code: allowlisted intents (the user still confirms
 * anything sensitive in the target app's own UI), app capability discovery, system state, and a few
 * harmless controls. Target packages always go through the user's app control policy.
 */
final class AndroidSystemTools {
    private AndroidSystemTools(){}

    // Actions that open UI or hand a draft to another app; none of them completes a call, payment or send by itself.
    private static final List<String> ACTIONS=Arrays.asList(Intent.ACTION_VIEW,Intent.ACTION_SEND,Intent.ACTION_SENDTO,Intent.ACTION_DIAL,Intent.ACTION_INSERT,
            Intent.ACTION_WEB_SEARCH,Intent.ACTION_MAIN,"android.intent.action.SET_ALARM","android.intent.action.SET_TIMER","android.intent.action.SHOW_ALARMS",
            "android.media.action.STILL_IMAGE_CAMERA","android.media.action.VIDEO_CAMERA");
    private static final List<String> SCHEMES=Arrays.asList("http","https","geo","tel","mailto","smsto","sms","market","content");
    private static final String[][] PROBES={
            {"open_web_link",Intent.ACTION_VIEW,"https://example.com",null},{"share_text",Intent.ACTION_SEND,null,"text/plain"},{"share_image",Intent.ACTION_SEND,null,"image/*"},
            {"open_map_location",Intent.ACTION_VIEW,"geo:0,0?q=test",null},{"dial",Intent.ACTION_DIAL,"tel:10086",null},{"compose_sms",Intent.ACTION_SENDTO,"smsto:10086",null},
            {"compose_email",Intent.ACTION_SENDTO,"mailto:a@example.com",null},{"insert_calendar_event",Intent.ACTION_INSERT,"content://com.android.calendar/events",null},
            {"set_alarm","android.intent.action.SET_ALARM",null,null},{"web_search",Intent.ACTION_WEB_SEARCH,null,null},{"view_video",Intent.ACTION_VIEW,"https://example.com/a.mp4","video/*"}};

    private static void requireAllowed(Context c,String pkg){if(pkg!=null&&!pkg.isEmpty()&&!AppControlPolicy.isAllowed(c,pkg))throw new SecurityException("target_app_blocked_by_user_policy:"+pkg);}
    private static JSONObject component(ResolveInfo r)throws Exception{ActivityInfo a=r.activityInfo;return new JSONObject().put("package",a.packageName).put("activity",a.name).put("exported",a.exported);}

    /** Builds an Intent from a JSON spec; throws on anything outside the allowlist. */
    static Intent build(Context c,JSONObject a)throws Exception{
        String action=a.optString("action",Intent.ACTION_VIEW);
        if(!ACTIONS.contains(action)&&!action.startsWith("android.settings."))throw new IllegalArgumentException("intent_action_not_allowed:"+action);
        Intent i=new Intent(action);String data=a.optString("data",""),type=a.optString("type",""),pkg=a.optString("package",""),cls=a.optString("activity","");
        if(!data.isEmpty()){Uri u=Uri.parse(data);String scheme=u.getScheme()==null?"":u.getScheme().toLowerCase(Locale.ROOT);
            // Custom app deep links (weixin://, alipays://...) are allowed only toward an explicit, policy-allowed package.
            if(!SCHEMES.contains(scheme)&&pkg.isEmpty())throw new IllegalArgumentException("custom_scheme_requires_explicit_package");
            if("content".equals(scheme)&&!Intent.ACTION_INSERT.equals(action))throw new IllegalArgumentException("content_uri_only_for_insert");
            if(!type.isEmpty())i.setDataAndType(u,type);else i.setData(u);}
        else if(!type.isEmpty())i.setType(type);
        if(Intent.ACTION_MAIN.equals(action)&&(pkg.isEmpty()||cls.isEmpty()))throw new IllegalArgumentException("main_action_requires_package_and_activity");
        if(!pkg.isEmpty()){requireAllowed(c,pkg);if(cls.isEmpty())i.setPackage(pkg);else i.setComponent(new ComponentName(pkg,cls.startsWith(".")?pkg+cls:cls));}
        JSONObject extras=a.optJSONObject("extras");
        if(extras!=null){if(extras.length()>20)throw new IllegalArgumentException("max_20_extras");for(Iterator<String> k=extras.keys();k.hasNext();){String key=k.next();Object v=extras.get(key);
            if(key.length()>200)throw new IllegalArgumentException("extra_key_too_long");
            if(v instanceof String){if(((String)v).length()>8000)throw new IllegalArgumentException("extra_value_too_long");i.putExtra(key,(String)v);}
            else if(v instanceof Boolean)i.putExtra(key,(Boolean)v);else if(v instanceof Integer)i.putExtra(key,(Integer)v);else if(v instanceof Long)i.putExtra(key,(Long)v);else if(v instanceof Number)i.putExtra(key,((Number)v).doubleValue());
            else throw new IllegalArgumentException("extras_must_be_string_number_or_boolean");}}
        String share=a.optString("share_file","");
        if(!share.isEmpty()){if(LocalFileProvider.workspaceFile(c,share)==null)throw new IllegalArgumentException("share_file_not_found_in_workspace");
            Uri uri=LocalFileProvider.workspaceUri(c,share);i.putExtra(Intent.EXTRA_STREAM,uri);i.setClipData(ClipData.newRawUri("file",uri));i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if(type.isEmpty())i.setType(c.getContentResolver().getType(uri));}
        return i;
    }

    static JSONObject startIntent(Context c,JSONObject a,JSONObject scope)throws Exception{
        Intent i=build(c,a);PackageManager pm=c.getPackageManager();List<ResolveInfo> handlers=pm.queryIntentActivities(i,0);
        if(handlers.isEmpty())return new JSONObject().put("ok",false).put("error","no_app_handles_this_intent");
        JSONArray targets=new JSONArray();for(ResolveInfo r:handlers){requireAllowedOrSkip(c,r,targets);}
        if(targets.length()==0)throw new SecurityException("all_handlers_blocked_by_user_policy");
        boolean chooser=a.optBoolean("chooser",Intent.ACTION_SEND.equals(i.getAction())&&i.getPackage()==null&&i.getComponent()==null);
        Intent launch=chooser?Intent.createChooser(i,a.optString("chooser_title","")):i;launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        String expected=targets.length()==1?targets.getJSONObject(0).getString("package"):"";
        AgentEvents.LaunchWatch watch=expected.isEmpty()||scope==null?null:AgentEvents.get(c).expectForeground(expected,scope);
        try{c.startActivity(launch);}catch(ActivityNotFoundException e){return new JSONObject().put("ok",false).put("error","activity_not_found");}
        JSONObject out=new JSONObject().put("ok",true).put("started",true).put("handlers",targets).put("chooser_shown",chooser||targets.length()>1);
        if(watch!=null){JSONObject seen=AgentEvents.get(c).awaitForeground(watch,3000);out.put("foreground_confirmed",seen!=null).put("foreground_package",expected);}
        return out.put("note","The target app shows its own screen; sending, calling, saving or paying still needs a visible confirmation there.");
    }
    private static void requireAllowedOrSkip(Context c,ResolveInfo r,JSONArray targets)throws Exception{if(AppControlPolicy.isAllowed(c,r.activityInfo.packageName)&&targets.length()<12)targets.put(component(r));}

    /** Discovery: which apps handle an intent, or what a given app can be asked to do. */
    static JSONObject resolve(Context c,JSONObject a)throws Exception{
        PackageManager pm=c.getPackageManager();String pkg=a.optString("package","");
        if(a.has("action")||a.has("data")){Intent i=build(c,a);JSONArray out=new JSONArray();for(ResolveInfo r:pm.queryIntentActivities(i,0)){if(!AppControlPolicy.isAllowed(c,r.activityInfo.packageName))continue;out.put(component(r).put("label",String.valueOf(r.loadLabel(pm))));if(out.length()>=20)break;}return new JSONObject().put("ok",true).put("handlers",out);}
        if(pkg.isEmpty())throw new IllegalArgumentException("package_or_intent_required");requireAllowed(c,pkg);
        PackageInfo info=pm.getPackageInfo(pkg,PackageManager.GET_ACTIVITIES);JSONArray exported=new JSONArray();
        if(info.activities!=null)for(ActivityInfo ai:info.activities)if(ai.exported&&exported.length()<60)exported.put(ai.name);
        JSONArray handles=new JSONArray();
        for(String[] p:PROBES){Intent probe=new Intent(p[1]);if(p[2]!=null&&p[3]!=null)probe.setDataAndType(Uri.parse(p[2]),p[3]);else if(p[2]!=null)probe.setData(Uri.parse(p[2]));else if(p[3]!=null)probe.setType(p[3]);probe.setPackage(pkg);
            if(!pm.queryIntentActivities(probe,0).isEmpty())handles.put(p[0]);}
        Intent launcher=pm.getLaunchIntentForPackage(pkg);
        return new JSONObject().put("ok",true).put("package",pkg).put("label",String.valueOf(info.applicationInfo.loadLabel(pm))).put("version",info.versionName)
                .put("launch_activity",launcher==null||launcher.getComponent()==null?JSONObject.NULL:launcher.getComponent().getClassName())
                .put("handles",handles).put("exported_activities",exported)
                .put("hint","Exported activities can be opened with android_intent(action=android.intent.action.MAIN, package, activity); many need extras and may close if opened directly.");
    }

    /** Read-only system state by section. */
    static JSONObject info(Context c,JSONObject a)throws Exception{
        JSONArray req=a.optJSONArray("sections");List<String> all=Arrays.asList("battery","network","storage","display","audio","locale","power");
        JSONObject out=new JSONObject().put("ok",true);
        for(String s:all){boolean want=req==null||req.length()==0;for(int i=0;req!=null&&i<req.length();i++)if(s.equals(req.optString(i)))want=true;if(!want)continue;
            if("battery".equals(s)){Intent b=c.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));BatteryManager bm=(BatteryManager)c.getSystemService(Context.BATTERY_SERVICE);
                int status=b==null?-1:b.getIntExtra(BatteryManager.EXTRA_STATUS,-1);
                out.put("battery",new JSONObject().put("percent",bm==null?-1:bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)).put("charging",status==BatteryManager.BATTERY_STATUS_CHARGING||status==BatteryManager.BATTERY_STATUS_FULL)
                        .put("plugged",b==null?0:b.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)).put("temperature_c",b==null?JSONObject.NULL:b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,0)/10.0));}
            else if("network".equals(s)){ConnectivityManager cm=(ConnectivityManager)c.getSystemService(Context.CONNECTIVITY_SERVICE);NetworkCapabilities nc=cm==null?null:cm.getNetworkCapabilities(cm.getActiveNetwork());JSONObject n=new JSONObject().put("connected",nc!=null);
                if(nc!=null)n.put("wifi",nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)).put("cellular",nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)).put("vpn",nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
                        .put("validated",nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).put("metered",!nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)).put("down_kbps",nc.getLinkDownstreamBandwidthKbps());
                out.put("network",n);}
            else if("storage".equals(s)){StatFs data=new StatFs(android.os.Environment.getDataDirectory().getPath());File ws=new File(c.getFilesDir(),"workbench_workspace");
                out.put("storage",new JSONObject().put("free_bytes",data.getAvailableBytes()).put("total_bytes",data.getTotalBytes()).put("workspace_bytes",size(ws,0)));}
            else if("display".equals(s)){DisplayMetrics m=c.getResources().getDisplayMetrics();out.put("display",new JSONObject().put("width_px",m.widthPixels).put("height_px",m.heightPixels).put("density",m.density).put("dark_mode",UiKit.isDark(c)));}
            else if("audio".equals(s)){AudioManager am=(AudioManager)c.getSystemService(Context.AUDIO_SERVICE);JSONObject v=new JSONObject().put("ringer_mode",am.getRingerMode());
                for(int stream:new int[]{AudioManager.STREAM_MUSIC,AudioManager.STREAM_RING,AudioManager.STREAM_ALARM,AudioManager.STREAM_NOTIFICATION})v.put(streamName(stream),new JSONObject().put("level",am.getStreamVolume(stream)).put("max",am.getStreamMaxVolume(stream)));
                out.put("audio",v);}
            else if("locale".equals(s))out.put("locale",new JSONObject().put("language",Locale.getDefault().toLanguageTag()).put("timezone",TimeZone.getDefault().getID()).put("epoch_ms",System.currentTimeMillis()));
            else if("power".equals(s)){PowerManager pm=(PowerManager)c.getSystemService(Context.POWER_SERVICE);out.put("power",new JSONObject().put("screen_on",pm.isInteractive()).put("power_save",pm.isPowerSaveMode()).put("ignoring_battery_optimizations",pm.isIgnoringBatteryOptimizations(c.getPackageName())));}
        }
        return out;
    }
    private static long size(File f,int depth){if(f.isFile())return f.length();long n=0;File[] k=depth>8?null:f.listFiles();if(k!=null)for(File x:k)n+=size(x,depth+1);return n;}
    private static String streamName(int s){return s==AudioManager.STREAM_MUSIC?"media":s==AudioManager.STREAM_RING?"ring":s==AudioManager.STREAM_ALARM?"alarm":"notification";}
    private static int stream(String s){if("ring".equals(s))return AudioManager.STREAM_RING;if("alarm".equals(s))return AudioManager.STREAM_ALARM;if("notification".equals(s))return AudioManager.STREAM_NOTIFICATION;if("media".equals(s))return AudioManager.STREAM_MUSIC;throw new IllegalArgumentException("stream_must_be_media_ring_alarm_or_notification");}

    /** Small reversible controls that need no special permission. */
    static JSONObject control(Context c,JSONObject a)throws Exception{
        String action=a.getString("action");
        if("set_volume".equals(action)){AudioManager am=(AudioManager)c.getSystemService(Context.AUDIO_SERVICE);int s=stream(a.optString("stream","media")),max=am.getStreamMaxVolume(s);
            int level=a.has("percent")?Math.round(max*Math.max(0,Math.min(100,a.getInt("percent")))/100f):Math.max(0,Math.min(max,a.getInt("level")));
            try{am.setStreamVolume(s,level,0);}catch(SecurityException dnd){return new JSONObject().put("ok",false).put("error","volume_change_blocked_by_do_not_disturb");}
            return new JSONObject().put("ok",true).put("stream",streamName(s)).put("level",am.getStreamVolume(s)).put("max",max);}
        if("clipboard_set".equals(action)){String text=a.getString("text");if(text.length()>20000)throw new IllegalArgumentException("text_too_long");
            ClipboardManager cm=(ClipboardManager)c.getSystemService(Context.CLIPBOARD_SERVICE);android.os.Handler main=new android.os.Handler(android.os.Looper.getMainLooper());
            main.post(()->cm.setPrimaryClip(ClipData.newPlainText("OPhone AI",text)));return new JSONObject().put("ok",true).put("chars",text.length());}
        if("torch".equals(action)){CameraManager cam=(CameraManager)c.getSystemService(Context.CAMERA_SERVICE);for(String id:cam.getCameraIdList()){Boolean flash=cam.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if(Boolean.TRUE.equals(flash)){cam.setTorchMode(id,a.optBoolean("on",true));return new JSONObject().put("ok",true).put("torch",a.optBoolean("on",true));}}
            return new JSONObject().put("ok",false).put("error","no_flash_unit");}
        throw new IllegalArgumentException("action_must_be_set_volume_clipboard_set_or_torch");
    }
}
