package ai.ophone.bridge;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * In-app updates from GitHub Releases. Checks every time the update page opens, otherwise at most once a day in
 * the background; downloads only
 * when the user taps the button, verifies the published SHA-256, package name, signing certificate and
 * version before offering the system installer, which always asks the user to confirm.
 */
public final class UpdateManager {
    public static final String REPO="SilverCoastR/OPhone-AI";
    public static final String REPO_URL="https://github.com/"+REPO,RELEASES_URL=REPO_URL+"/releases";
    private static final String API="https://api.github.com/repos/"+REPO+"/releases/latest",PREFS="app_update",CHANNEL="ophoneai_updates";
    private static final long DAY=24*60*60*1000L,MAX_APK=300L*1024*1024;
    private static final ExecutorService worker=Executors.newSingleThreadExecutor(r->new Thread(r,"OPhoneAI-update"));
    private static final CopyOnWriteArrayList<Runnable> listeners=new CopyOnWriteArrayList<>();
    private static final Handler main=new Handler(Looper.getMainLooper());
    private static volatile long downloaded,total;private static volatile boolean busy;
    private UpdateManager(){}

    private static SharedPreferences prefs(Context c){return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
    public static void addListener(Runnable r){listeners.add(r);}
    public static void removeListener(Runnable r){listeners.remove(r);}
    private static void changed(){for(Runnable r:listeners)main.post(r);}
    public static boolean autoCheck(Context c){return prefs(c).getBoolean("auto_check",true);}
    public static void setAutoCheck(Context c,boolean on){prefs(c).edit().putBoolean("auto_check",on).apply();}

    /** Called on app start and from the bridge service; does nothing if checked within the last 24 hours. */
    public static void maybeCheck(Context context){Context c=context.getApplicationContext();
        if(!autoCheck(c)||busy||System.currentTimeMillis()-prefs(c).getLong("last_check_at",0)<DAY)return;
        worker.execute(()->check(c,false));}
    public static void checkNow(Context context){Context c=context.getApplicationContext();if(busy)return;worker.execute(()->check(c,true));}

    /**
     * After the user installed an update: once the running version is at least the downloaded or published one,
     * the kept APK is deleted and the page says this version is the latest. Returns true when an update was just
     * confirmed as installed. Safe to call often (app start, package replaced, opening the update page).
     */
    public static synchronized boolean reconcileInstalled(Context context){Context c=context.getApplicationContext();
        try{JSONObject s=new JSONObject(prefs(c).getString("status","{}"));JSONObject latest=s.optJSONObject("latest");String st=s.optString("state");
            String target=latest==null?"":latest.optString("version");
            boolean installed=!target.isEmpty()&&!newer(target,BuildConfig.VERSION_NAME);
            File dir=new File(c.getFilesDir(),"updates");File[] left=dir.listFiles();boolean files=left!=null&&left.length>0;
            if(!installed&&!(files&&!"downloaded".equals(st)&&!"downloading".equals(st)))return false;
            if(busy&&"downloading".equals(st))return false;
            if(files)deleteOld(dir);
            boolean justUpdated=installed&&("downloaded".equals(st)||"available".equals(st)||"failed".equals(st))&&!BuildConfig.VERSION_NAME.equals(prefs(c).getString("confirmed_version",""));
            if(installed&&!"latest".equals(st)&&!"checking".equals(st)){s.put("state","latest").remove("file");s.remove("error");if(justUpdated)s.put("updated_to",BuildConfig.VERSION_NAME);save(c,s);}
            else if(!installed&&files&&"downloaded".equals(st)){s.put("state","available").remove("file");save(c,s);}
            if(justUpdated){prefs(c).edit().putString("confirmed_version",BuildConfig.VERSION_NAME).apply();cancelNotification(c);
                DetailedDiagnostics.event(c,"info","update","","","update_installed","Updated to "+BuildConfig.VERSION_NAME+"; the downloaded package was removed.",null);}
            return justUpdated;
        }catch(Exception e){return false;}}
    /** Android tells the app its package was replaced: confirm the update, delete the installer, say so once. */
    public static void packageReplaced(Context c){if(reconcileInstalled(c))notify(c,c.getString(R.string.update_installed_title,BuildConfig.VERSION_NAME),c.getString(R.string.update_installed_body),false);}
    /** The update page clears the "just updated" note once it has been shown. */
    public static void clearUpdatedNote(Context c){try{JSONObject s=new JSONObject(prefs(c).getString("status","{}"));if(s.has("updated_to")){s.remove("updated_to");prefs(c).edit().putString("status",s.toString()).apply();}}catch(Exception ignored){}}

    /** State for the About page: state = idle | checking | latest | available | downloading | downloaded | failed. */
    public static JSONObject status(Context c){try{SharedPreferences p=prefs(c);JSONObject s=new JSONObject(p.getString("status","{}"));
        s.put("current",BuildConfig.VERSION_NAME).put("last_check_at",p.getLong("last_check_at",0)).put("auto_check",autoCheck(c));
        if("downloading".equals(s.optString("state")))s.put("downloaded",downloaded).put("total",total);
        if("downloading".equals(s.optString("state"))&&!busy)s.put("state","failed").put("error","download_interrupted");
        if("downloaded".equals(s.optString("state"))&&!new File(s.optString("file")).isFile())s.put("state","available");
        JSONObject latest=s.optJSONObject("latest");
        if(latest!=null&&("available".equals(s.optString("state"))||"downloaded".equals(s.optString("state")))&&!newer(latest.optString("version"),BuildConfig.VERSION_NAME))s.put("state","latest");
        if(s.optString("state").isEmpty())s.put("state","idle");return s;}catch(Exception e){return new JSONObject();}}
    private static void save(Context c,JSONObject s){prefs(c).edit().putString("status",s.toString()).apply();changed();}

    static int[] version(String v){String[] parts=v.replaceFirst("^[vV]","").split("[^0-9]+");int[] out=new int[3];for(int i=0;i<3&&i<parts.length;i++)try{out[i]=Integer.parseInt(parts[i]);}catch(Exception ignored){}return out;}
    static boolean newer(String a,String b){int[] x=version(a),y=version(b);for(int i=0;i<3;i++)if(x[i]!=y[i])return x[i]>y[i];return false;}

    private static void check(Context c,boolean manual){busy=true;JSONObject s=status(c);
        try{s.put("state","checking");save(c,s);
            HttpURLConnection http=(HttpURLConnection)new URL(API).openConnection();http.setConnectTimeout(15000);http.setReadTimeout(15000);
            http.setRequestProperty("Accept","application/vnd.github+json");http.setRequestProperty("User-Agent","OPhoneAI/"+BuildConfig.VERSION_NAME);
            int code=http.getResponseCode();if(code!=200)throw new IllegalStateException("github_http_"+code);
            JSONObject release;try(InputStream in=http.getInputStream()){release=new JSONObject(new String(readAll(in,2*1024*1024),"UTF-8"));}
            String tag=release.optString("tag_name");JSONObject asset=null;JSONArray assets=release.optJSONArray("assets");
            for(int i=0;assets!=null&&i<assets.length();i++){JSONObject a=assets.getJSONObject(i);String n=a.optString("name").toLowerCase();if(n.endsWith(".apk")&&!n.contains("androidtest")){asset=a;break;}}
            JSONObject latest=new JSONObject().put("tag",tag).put("version",tag.replaceFirst("^[vV]","")).put("name",release.optString("name",tag)).put("notes",clip(release.optString("body",""),4000))
                    .put("published_at",release.optString("published_at")).put("html_url",release.optString("html_url",RELEASES_URL));
            if(asset!=null)latest.put("asset_url",asset.optString("browser_download_url")).put("asset_name",asset.optString("name")).put("size",asset.optLong("size")).put("sha256",asset.optString("digest","").replaceFirst("^sha256:",""));
            boolean available=newer(tag,BuildConfig.VERSION_NAME)&&asset!=null;
            String previousTag=s.optJSONObject("latest")==null?"":s.getJSONObject("latest").optString("tag");boolean keepDownload=available&&"downloaded".equals(s.optString("state"))&&tag.equals(previousTag);
            String updatedTo=s.optString("updated_to");
            s=new JSONObject().put("state",keepDownload?"downloaded":available?"available":"latest").put("latest",latest);if(keepDownload)s.put("file",status(c).optString("file"));
            if(!available&&!updatedTo.isEmpty())s.put("updated_to",updatedTo);
            if(!available)deleteOld(new File(c.getFilesDir(),"updates"));
            prefs(c).edit().putLong("last_check_at",System.currentTimeMillis()).apply();save(c,s);
            if(available&&!manual&&!keepDownload&&!tag.equals(prefs(c).getString("notified_tag","")))notify(c,c.getString(R.string.update_notify_title,latest.optString("version")),c.getString(R.string.update_notify_body),false);
            if(available)prefs(c).edit().putString("notified_tag",tag).apply();
        }catch(Exception e){prefs(c).edit().putLong("last_check_at",System.currentTimeMillis()).apply();try{s.put("state","failed").put("error",DetailedDiagnostics.clean(String.valueOf(e.getMessage())));}catch(Exception ignored){}save(c,s);}
        finally{busy=false;changed();}
    }

    /** Background download after the user taps "Download". */
    public static void download(Context context){Context c=context.getApplicationContext();if(busy)return;JSONObject s=status(c);JSONObject latest=s.optJSONObject("latest");
        if(latest==null||latest.optString("asset_url").isEmpty())return;busy=true;downloaded=0;total=latest.optLong("size");
        try{s.put("state","downloading").remove("error");}catch(Exception ignored){}save(c,s);
        worker.execute(()->{File dir=new File(c.getFilesDir(),"updates"),part=new File(dir,"update.apk.part"),apk=new File(dir,safeName(latest.optString("asset_name")));JSONObject st=status(c);
            try{deleteOld(dir);dir.mkdirs();
                HttpURLConnection http=(HttpURLConnection)new URL(latest.getString("asset_url")).openConnection();http.setConnectTimeout(15000);http.setReadTimeout(30000);http.setInstanceFollowRedirects(true);http.setRequestProperty("User-Agent","OPhoneAI/"+BuildConfig.VERSION_NAME);
                if(http.getResponseCode()!=200)throw new IllegalStateException("download_http_"+http.getResponseCode());if(http.getContentLengthLong()>0)total=http.getContentLengthLong();if(total>MAX_APK)throw new IllegalStateException("update_too_large");
                MessageDigest sha=MessageDigest.getInstance("SHA-256");long lastNotify=0;
                try(InputStream in=http.getInputStream();FileOutputStream out=new FileOutputStream(part)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){out.write(b,0,n);sha.update(b,0,n);downloaded+=n;if(downloaded>MAX_APK)throw new IllegalStateException("update_too_large");
                        long now=System.currentTimeMillis();if(now-lastNotify>700){lastNotify=now;changed();progress(c,downloaded,total);}}out.getFD().sync();}
                String digest=hex(sha.digest()),expected=latest.optString("sha256");if(!expected.isEmpty()&&!expected.equalsIgnoreCase(digest))throw new SecurityException("update_sha256_mismatch");
                verifyApk(c,part,latest.optString("version"));
                if(!part.renameTo(apk))throw new IllegalStateException("update_save_failed");
                st.put("state","downloaded").put("file",apk.getAbsolutePath()).put("sha256_verified",!expected.isEmpty());save(c,st);
                notify(c,c.getString(R.string.update_ready_title,latest.optString("version")),c.getString(R.string.update_ready_body),false);
            }catch(Exception e){part.delete();try{st.put("state","failed").put("error",DetailedDiagnostics.clean(String.valueOf(e.getMessage())));}catch(Exception ignored){}save(c,st);cancelNotification(c);}
            finally{busy=false;changed();}});}

    /** The download must be this app, signed with the same certificate, and actually newer. */
    private static void verifyApk(Context c,File apk,String version)throws Exception{PackageManager pm=c.getPackageManager();
        PackageInfo archive=pm.getPackageArchiveInfo(apk.getAbsolutePath(),PackageManager.GET_SIGNATURES);if(archive==null)throw new IllegalStateException("update_not_a_valid_apk");
        if(!c.getPackageName().equals(archive.packageName))throw new SecurityException("update_package_mismatch");
        PackageInfo self=pm.getPackageInfo(c.getPackageName(),PackageManager.GET_SIGNATURES);
        if(archive.signatures==null||!Arrays.equals(certs(archive.signatures),certs(self.signatures)))throw new SecurityException("update_signature_mismatch");
        long archiveCode=Build.VERSION.SDK_INT>=28?archive.getLongVersionCode():archive.versionCode,selfCode=Build.VERSION.SDK_INT>=28?self.getLongVersionCode():self.versionCode;
        if(archiveCode<=selfCode)throw new IllegalStateException("update_not_newer_version_code");}
    private static String[] certs(Signature[] s){String[] out=new String[s.length];for(int i=0;i<s.length;i++)out[i]=s[i].toCharsString();Arrays.sort(out);return out;}

    /** Hands the verified APK to the system installer; Android shows its own confirmation. */
    public static void install(Activity a){JSONObject s=status(a);File apk=new File(s.optString("file"));if(!apk.isFile())return;
        if(Build.VERSION.SDK_INT>=26&&!a.getPackageManager().canRequestPackageInstalls()){a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+a.getPackageName())));return;}
        Uri uri=new Uri.Builder().scheme("content").authority(LocalFileProvider.authority(a)).appendPath("update").appendPath(apk.getName()).build();
        a.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));}
    static File updateFile(Context c,String name){File f=new File(new File(c.getFilesDir(),"updates"),safeName(name));return f.isFile()&&f.getName().endsWith(".apk")?f:null;}

    private static String safeName(String n){String s=n==null?"":n.replaceAll("[^A-Za-z0-9._-]","_");return s.isEmpty()?"update.apk":s;}
    private static void deleteOld(File dir){File[] k=dir.listFiles();if(k!=null)for(File f:k)f.delete();}
    private static String clip(String s,int max){return s.length()>max?s.substring(0,max)+"…":s;}
    private static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format("%02x",x&255));return s.toString();}
    private static byte[] readAll(InputStream in,int max)throws Exception{java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){out.write(b,0,n);if(out.size()>max)throw new IllegalStateException("response_too_large");}return out.toByteArray();}

    private static NotificationManager channel(Context c){NotificationManager nm=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
        if(Build.VERSION.SDK_INT>=26)nm.createNotificationChannel(new NotificationChannel(CHANNEL,c.getString(R.string.update_channel),NotificationManager.IMPORTANCE_DEFAULT));return nm;}
    private static Notification.Builder builder(Context c){Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(c,CHANNEL):new Notification.Builder(c);
        PendingIntent open=PendingIntent.getActivity(c,7301,new Intent(c,AboutActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),PendingIntent.FLAG_UPDATE_CURRENT|(Build.VERSION.SDK_INT>=23?PendingIntent.FLAG_IMMUTABLE:0));
        return b.setSmallIcon(android.R.drawable.stat_sys_download_done).setContentIntent(open).setAutoCancel(true);}
    private static void notify(Context c,String title,String body,boolean ongoing){try{channel(c).notify(7301,builder(c).setContentTitle(title).setContentText(body).setOngoing(ongoing).build());}catch(Exception ignored){}}
    private static void progress(Context c,long done,long all){try{int pct=all>0?(int)(done*100/all):0;channel(c).notify(7301,builder(c).setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(c.getString(R.string.update_downloading)).setProgress(100,pct,all<=0).setOngoing(true).setOnlyAlertOnce(true).build());}catch(Exception ignored){}}
    private static void cancelNotification(Context c){try{channel(c).cancel(7301);}catch(Exception ignored){}}
}
