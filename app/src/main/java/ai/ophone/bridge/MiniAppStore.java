package ai.ophone.bridge;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/** AI-written single-file HTML mini apps, stored in app-private files/miniapps/<name>/. */
final class MiniAppStore {
    private MiniAppStore(){}
    static final int MAX_HTML=512*1024,MAX_STORAGE=256*1024;
    /** Tools a mini app may never call, whatever it declares. */
    static final List<String> FORBIDDEN=Arrays.asList("install_apk","uninstall_app","runtime_recover","task_finish","task_verify","task_set_criteria","task_checkpoint","task_wait",
            "ask_user","miniapp_save","miniapp_delete","miniapp_open","memory_save","memory_delete","event_subscribe","event_ack","event_unsubscribe","root_status","screen_record_start");

    static String name(String raw){if(raw==null||!raw.matches("[a-z0-9][a-z0-9_-]{0,39}"))throw new IllegalArgumentException("miniapp_name_must_be_lowercase_letters_digits_dash_underscore_max_40");return raw;}
    static File dir(Context c,String name){return new File(new File(c.getFilesDir(),"miniapps"),name(name));}
    private static byte[] read(File f,int max)throws Exception{if(!f.isFile())return new byte[0];if(f.length()>max)throw new IllegalStateException("miniapp_file_too_large");
        try(java.io.FileInputStream in=new java.io.FileInputStream(f)){byte[] b=new byte[(int)f.length()];int off=0,n;while(off<b.length&&(n=in.read(b,off,b.length-off))>0)off+=n;return b;}}
    private static void write(File f,byte[] b)throws Exception{f.getParentFile().mkdirs();File tmp=new File(f.getPath()+".tmp");try(java.io.FileOutputStream out=new java.io.FileOutputStream(tmp)){out.write(b);out.getFD().sync();}if(!tmp.renameTo(f))throw new java.io.IOException("miniapp_write_failed");}

    static JSONObject meta(Context c,String name)throws Exception{File f=new File(dir(c,name),"meta.json");if(!f.isFile())throw new IllegalArgumentException("miniapp_not_found");return new JSONObject(new String(read(f,65536),StandardCharsets.UTF_8));}
    static void saveMeta(Context c,JSONObject m)throws Exception{write(new File(dir(c,m.getString("name")),"meta.json"),m.toString().getBytes(StandardCharsets.UTF_8));}
    static String html(Context c,String name)throws Exception{return new String(read(new File(dir(c,name),"index.html"),MAX_HTML),StandardCharsets.UTF_8);}

    static JSONObject save(Context c,JSONObject a)throws Exception{
        String n=name(a.getString("name")),html=a.getString("html"),title=a.optString("title",n).trim(),desc=a.optString("description","").trim();
        byte[] bytes=html.getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX_HTML)throw new IllegalArgumentException("html_max_512KiB");
        if(title.isEmpty()||title.length()>60||desc.length()>300)throw new IllegalArgumentException("title_max_60_description_max_300");
        JSONArray perms=new JSONArray(),raw=a.optJSONArray("permissions");
        for(int i=0;raw!=null&&i<raw.length();i++){String t=raw.optString(i);if(FORBIDDEN.contains(t))throw new SecurityException("tool_not_available_to_miniapps:"+t);if(!t.matches("[a-z_]{2,40}"))throw new IllegalArgumentException("invalid_permission:"+t);perms.put(t);}
        JSONObject old=null;try{old=meta(c,n);}catch(Exception ignored){}
        // Changing the declared tools requires the user's approval again.
        boolean samePerms=old!=null&&old.optJSONArray("permissions")!=null&&old.getJSONArray("permissions").toString().equals(perms.toString());
        String revision=TaskController.digest(html).substring(0,16);long now=System.currentTimeMillis();
        JSONObject m=new JSONObject().put("name",n).put("title",title).put("description",desc).put("permissions",perms).put("revision",revision)
                .put("created_at",old==null?now:old.optLong("created_at",now)).put("updated_at",now).put("approved",samePerms&&old.optBoolean("approved"));
        write(new File(dir(c,n),"index.html"),bytes);saveMeta(c,m);
        return new JSONObject().put("ok",true).put("name",n).put("revision",revision).put("permissions",perms).put("needs_user_approval",perms.length()>0&&!m.optBoolean("approved"));
    }
    static JSONObject read(Context c,String name)throws Exception{return new JSONObject().put("ok",true).put("meta",meta(c,name)).put("html",html(c,name));}
    static JSONObject list(Context c)throws Exception{JSONArray out=new JSONArray();File[] dirs=new File(c.getFilesDir(),"miniapps").listFiles();
        if(dirs!=null)for(File d:dirs)try{JSONObject m=meta(c,d.getName());out.put(m);}catch(Exception ignored){}
        return new JSONObject().put("ok",true).put("apps",out);}
    static JSONObject delete(Context c,String name)throws Exception{File d=dir(c,name);boolean existed=d.isDirectory();deleteTree(d);return new JSONObject().put("ok",true).put("deleted",existed);}
    private static void deleteTree(File f){File[] k=f.isDirectory()?f.listFiles():null;if(k!=null)for(File x:k)deleteTree(x);f.delete();}

    static String storageGet(Context c,String name,String key)throws Exception{JSONObject s=storage(c,name);return s.has(key)?s.getString(key):null;}
    static void storageSet(Context c,String name,String key,String value)throws Exception{if(key.length()>200)throw new IllegalArgumentException("key_too_long");JSONObject s=storage(c,name);if(value==null)s.remove(key);else s.put(key,value);
        byte[] b=s.toString().getBytes(StandardCharsets.UTF_8);if(b.length>MAX_STORAGE)throw new IllegalStateException("miniapp_storage_full_256KiB");write(new File(dir(c,name),"storage.json"),b);}
    private static JSONObject storage(Context c,String name)throws Exception{File f=new File(dir(c,name),"storage.json");return f.isFile()?new JSONObject(new String(read(f,MAX_STORAGE),StandardCharsets.UTF_8)):new JSONObject();}
}
