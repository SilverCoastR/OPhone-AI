package ai.ophone.bridge;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** User-owned root policy, read from disk across processes. Presence of su never means granted root. */
public final class RootAccess {
    private static final java.util.concurrent.atomic.AtomicBoolean probing=new java.util.concurrent.atomic.AtomicBoolean();
    private RootAccess(){}
    public static File directory(Context c){File d=new File(c.getFilesDir(),"root_control");d.mkdirs();return d;}
    public static File policyFile(Context c){return new File(directory(c),"policy.json");}
    private static JSONObject read(File f){try(InputStream in=new AtomicFile(f).openRead();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[1024];int n;while((n=in.read(b))!=-1){if(out.size()+n>8192)throw new IOException("root_metadata_limit");out.write(b,0,n);}return new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));}catch(Exception ignored){return new JSONObject();}}
    private static void write(File f,JSONObject value)throws Exception{AtomicFile atomic=new AtomicFile(f);FileOutputStream out=atomic.startWrite();try{out.write(value.toString().getBytes(StandardCharsets.UTF_8));atomic.finishWrite(out);}catch(Exception e){atomic.failWrite(out);throw e;}}
    public static JSONObject policy(Context c){return read(policyFile(c));}
    public static boolean enabled(Context c){return policy(c).optBoolean("enabled",false);}
    public static synchronized void setEnabled(Context c,boolean enabled)throws Exception{write(policyFile(c),new JSONObject().put("enabled",enabled).put("generation",UUID.randomUUID().toString()).put("changed_at",System.currentTimeMillis()));}
    public static String executable(){LinkedHashSet<String> paths=new LinkedHashSet<>(Arrays.asList("/system/bin/su","/system/xbin/su","/sbin/su","/su/bin/su","/debug_ramdisk/su","/system/bin/.ext/.su"));String path=System.getenv("PATH");if(path!=null)for(String parent:path.split(":"))if(parent.startsWith("/"))paths.add(new File(parent,"su").getPath());for(String candidate:paths){File f=new File(candidate);if(f.isFile()&&f.canExecute())return candidate;}return null;}
    public static JSONObject status(Context c)throws Exception {
        JSONObject policy=policy(c),cached=read(new File(directory(c),"probe.json"));boolean enabled=policy.optBoolean("enabled"),found=executable()!=null;
        boolean fresh=policy.optString("generation").equals(cached.optString("generation"))&&System.currentTimeMillis()-cached.optLong("checked_at")<30000;
        String state=!enabled?"disabled":!found?"su_not_found":fresh?cached.optString("state","not_checked"):"not_checked";
        return new JSONObject().put("ok",true).put("enabled",enabled).put("su_found",found).put("available",enabled&&found&&fresh&&cached.optBoolean("available"))
            .put("state",state).put("checked_at",cached.optLong("checked_at")).put("effective_uid",fresh&&cached.has("effective_uid")?cached.get("effective_uid"):JSONObject.NULL)
            .put("generation",policy.optString("generation")).put("authorization_required",enabled&&found&&(!fresh||!cached.optBoolean("available")))
            .put("scope","Root manager grant to OPhone AI, not an Android runtime permission or automatic rooting").put("tool_usage","root_status(probe:true) checks authorization; terminal_start(command:..., root:true) requests a root job; verify root_verified and effective_uid=0. Only Settings can enable the switch.")
            .put("off_scope","Blocks this app root-mode tools and cancels managed jobs; revoke the app in the root manager for OS-level revocation. Arbitrary same-UID scripts are trusted code.");
    }
    private static Thread drain(InputStream in,ByteArrayOutputStream out){Thread t=new Thread(()->{try{byte[] b=new byte[1024];int n;while((n=in.read(b))!=-1){synchronized(out){int room=4096-out.size();if(room>0)out.write(b,0,Math.min(room,n));}}}catch(Exception ignored){}},"OPhoneAI-root-probe");t.setDaemon(true);t.start();return t;}
    public static JSONObject probe(Context c)throws Exception {
        JSONObject before=policy(c);if(!before.optBoolean("enabled"))return status(c).put("error","root_shell_disabled_by_user");String su=executable();
        if(su==null)return status(c).put("error","su_not_found_device_root_required");
        if(!probing.compareAndSet(false,true))return status(c).put("error","root_authorization_check_already_running");
        Process child=null;JSONObject record=new JSONObject().put("generation",before.getString("generation")).put("available",false);ByteArrayOutputStream stdout=new ByteArrayOutputStream(),stderr=new ByteArrayOutputStream();
        try{child=new ProcessBuilder(su,"-c","/system/bin/id -u").start();child.getOutputStream().close();Thread out=drain(child.getInputStream(),stdout),err=drain(child.getErrorStream(),stderr);boolean exited=child.waitFor(12000,TimeUnit.MILLISECONDS);
            if(!exited){child.destroy();record.put("state","authorization_timed_out");}else{out.join(500);err.join(500);String uid=new String(stdout.toByteArray(),StandardCharsets.UTF_8).trim();boolean granted=child.exitValue()==0&&"0".equals(uid);record.put("state",granted?"granted":"authorization_denied_or_not_root").put("available",granted);if(uid.matches("[0-9]{1,10}"))record.put("effective_uid",Long.parseLong(uid));}
        }catch(Exception error){record.put("state","su_execution_failed").put("error",error.getClass().getSimpleName());}finally{if(child!=null)child.destroy();probing.set(false);}
        record.put("checked_at",System.currentTimeMillis());write(new File(directory(c),"probe.json"),record);JSONObject now=status(c);if(!before.optString("generation").equals(policy(c).optString("generation")))return now.put("error","root_policy_changed_during_authorization");return now;
    }
}
