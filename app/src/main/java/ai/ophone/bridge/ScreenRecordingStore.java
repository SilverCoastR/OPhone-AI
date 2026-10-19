package ai.ophone.bridge;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Durable receipts for user-consented MediaProjection recordings. */
public final class ScreenRecordingStore {
    private static final String PREFS="screen_record_v1", PREFIX="record:", ACTIVE="active_id";
    private ScreenRecordingStore(){}

    public static synchronized JSONObject request(Context base,JSONObject args,JSONObject scope)throws Exception{
        Context context=base.getApplicationContext();
        if(scope==null||scope.optString("task_id").isEmpty())throw new IllegalArgumentException("native_task_required");
        String target=args.optString("expected_package").trim();
        if(!target.matches("[A-Za-z0-9_.]{1,200}"))throw new IllegalArgumentException("expected_package_required");
        int seconds=args.optInt("duration_seconds",60);
        if(seconds<5||seconds>180)throw new IllegalArgumentException("recording_duration_must_be_5_to_180_seconds");
        long now=System.currentTimeMillis(),startAt=Math.max(now,args.optLong("start_at_epoch_ms",now));
        if(startAt>now+24*60*60*1000L)throw new IllegalArgumentException("screen_record_start_time_out_of_range");
        SharedPreferences prefs=prefs(context);String active=prefs.getString(ACTIVE,"");
        if(!active.isEmpty()){
            JSONObject existing=read(context,active);
            if(existing!=null&&activeState(existing.optString("state")))return publicStatus(existing)
                    .put("ok",false).put("error","screen_recording_already_active")
                    .put("next_step","Do not start another capture. Poll screen_record_status with this recording_id.");
            prefs.edit().remove(ACTIVE).commit();
        }
        String requested=args.optString("output_name","").trim();
        String name=safeOutputName(requested.isEmpty()?"OPhoneAI-"+System.currentTimeMillis()+".mp4":requested);
        String id="recording-"+UUID.randomUUID().toString();
        File dir=new File(context.getFilesDir(),"workbench_workspace/recordings");
        if(!dir.exists()&&!dir.mkdirs())throw new IllegalStateException("recording_directory_unavailable");
        File output=new File(dir,id+"-"+name);
        JSONObject record=new JSONObject().put("id",id).put("state","awaiting_user_consent")
                .put("requested_at",now).put("updated_at",now).put("duration_ms",seconds*1000L)
                .put("start_at_epoch_ms",startAt)
                .put("target_package",target).put("output_name",name).put("output_path",output.getAbsolutePath())
                .put("task_id",scope.optString("task_id")).put("conversation",scope.optString("conversation"))
                .put("call_id",scope.optString("call_id")).put("audio",false).put("bytes",0);
        if(!args.optString("expected_text","").isEmpty())record.put("target_text",args.getString("expected_text")).put("target_text_observed",args.optBoolean("target_text_observed")).put("target_text_observed_at",args.optLong("target_text_observed_at"));
        save(context,record,true);
        publish(context,record,"recording.consent_required","info",new JSONObject().put("recording_id",id)
                .put("duration_seconds",seconds).put("target_package",target).put("system_consent_required",true));
        try{
            Intent intent=new Intent(context,ScreenCaptureActivity.class).putExtra("recording_id",id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_NO_ANIMATION);
            context.startActivity(intent);
        }catch(Exception error){markFailed(context,id,"consent_activity_failed: "+clean(error));throw error;}
        return publicStatus(record).put("ok",true).put("system_consent_required",true)
                .put("next_step","Android is asking the user to allow screen capture. Do not retry this tool. After consent, call screen_record_status with wait_ms up to 15000 until completed, then verify the screen_recording criterion.");
    }

    public static synchronized JSONObject status(Context context,String id)throws Exception{
        if(id==null||!id.matches("recording-[A-Za-z0-9-]{20,80}"))return new JSONObject().put("ok",false).put("error","invalid_recording_id");
        JSONObject record=read(context,id);if(record==null)return new JSONObject().put("ok",false).put("error","recording_not_found").put("recording_id",id);
        File file=new File(record.optString("output_path"));record.put("bytes",file.isFile()?file.length():0);
        if("recording".equals(record.optString("state"))&&record.optLong("started_at")>0)record.put("elapsed_ms",Math.max(0,System.currentTimeMillis()-record.optLong("started_at")));
        return publicStatus(record).put("ok",true);
    }

    public static JSONObject waitStatus(Context context,String id,long waitMs)throws Exception{
        long bounded=Math.max(0,Math.min(15000,waitMs)),end=System.currentTimeMillis()+bounded;
        JSONObject first=status(context,id);String initial=first.optString("state");
        while(first.optBoolean("ok")&&activeState(initial)&&System.currentTimeMillis()<end){
            try{Thread.sleep(Math.min(250,Math.max(1,end-System.currentTimeMillis())));}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
            JSONObject current=status(context,id);
            if(!initial.equals(current.optString("state"))||!activeState(current.optString("state")))return current;
            first=current;
        }
        if(first.optBoolean("ok")&&activeState(first.optString("state")))first.put("wait_timed_out",bounded>0)
                .put("next_step","Recording is still active or awaiting consent. Keep the target app visible and poll this same recording_id; do not start a second recording.");
        return first;
    }

    static synchronized JSONObject internal(Context context,String id)throws Exception{
        JSONObject record=read(context,id);if(record==null)throw new IllegalArgumentException("recording_not_found");return record;
    }

    static synchronized void markRecording(Context context,String id,int width,int height)throws Exception{
        JSONObject record=internal(context,id);long now=System.currentTimeMillis();record.put("state","recording").put("started_at",now)
                .put("updated_at",now).put("width",width).put("height",height);save(context,record,true);
        publish(context,record,"recording.started","info",new JSONObject().put("recording_id",id).put("width",width).put("height",height).put("audio",false));
    }

    static synchronized void markScheduled(Context context,String id)throws Exception{
        JSONObject record=internal(context,id);long now=System.currentTimeMillis();record.put("state","scheduled").put("updated_at",now);save(context,record,true);
        publish(context,record,"recording.scheduled","info",new JSONObject().put("recording_id",id).put("start_at_epoch_ms",record.optLong("start_at_epoch_ms")));
    }

    static synchronized JSONObject markCompleted(Context context,String id,long elapsed)throws Exception{
        JSONObject record=internal(context,id);File file=new File(record.getString("output_path"));
        if(!file.isFile()||file.length()<1024)throw new IllegalStateException("recording_output_missing_or_empty");
        Uri media=null;String publishError="";try{media=publishVideo(context,file,record.getString("output_name"));}catch(Exception error){publishError=clean(error);}
        long now=System.currentTimeMillis();
        record.put("state","completed").put("finished_at",now).put("updated_at",now).put("elapsed_ms",Math.max(0,elapsed))
                .put("bytes",file.length()).put("sha256",sha256(file));if(media!=null)record.put("media_uri",media.toString());
        if(!publishError.isEmpty())record.put("media_store_warning",publishError);
        save(context,record,false);
        publish(context,record,"recording.completed","info",new JSONObject().put("recording_id",id).put("elapsed_ms",elapsed)
                .put("bytes",file.length()).put("media_uri",record.optString("media_uri")).put("audio",false));
        return publicStatus(record).put("ok",true);
    }

    static synchronized void markFailed(Context context,String id,String reason){
        try{
            JSONObject record=read(context,id);if(record==null)return;long now=System.currentTimeMillis();
            record.put("state","failed").put("finished_at",now).put("updated_at",now).put("error",clean(reason));save(context,record,false);
            publish(context,record,"recording.failed","error",new JSONObject().put("recording_id",id).put("error",clean(reason)));
        }catch(Exception ignored){}
    }

    static synchronized void markCancelled(Context context,String id,String reason){
        try{JSONObject record=read(context,id);if(record==null||!activeState(record.optString("state")))return;long now=System.currentTimeMillis();record.put("state","cancelled").put("finished_at",now).put("updated_at",now).put("error",clean(reason));save(context,record,false);publish(context,record,"recording.cancelled","info",new JSONObject().put("recording_id",id).put("reason",clean(reason)));}catch(Exception ignored){}
    }

    public static synchronized boolean cancelTask(Context base,String taskId,String reason){
        Context context=base.getApplicationContext();String id=prefs(context).getString(ACTIVE,"");if(id.isEmpty())return false;JSONObject record=read(context,id);if(record==null||!taskId.equals(record.optString("task_id"))||!activeState(record.optString("state")))return false;
        if("recording".equals(record.optString("state"))||"starting".equals(record.optString("state"))){Intent stop=new Intent(context,ScreenCaptureService.class).setAction(ScreenCaptureService.ACTION_STOP).putExtra("recording_id",id).putExtra("cancel_reason",reason);try{if(Build.VERSION.SDK_INT>=26)context.startForegroundService(stop);else context.startService(stop);}catch(Exception ignored){markCancelled(context,id,reason);}}
        else markCancelled(context,id,reason);return true;
    }

    static String safeOutputName(String value){
        String name=value==null?"":value.trim().replaceAll("[^A-Za-z0-9._-]","_").replaceFirst("^[._-]+","");
        if(name.isEmpty())name="OPhoneAI-recording.mp4";
        if(name.length()>80)name=name.substring(0,80);if(!name.toLowerCase(Locale.ROOT).endsWith(".mp4"))name=name+".mp4";return name;
    }

    private static SharedPreferences prefs(Context context){return context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
    private static JSONObject read(Context context,String id){String value=prefs(context).getString(PREFIX+id,null);try{return value==null?null:new JSONObject(value);}catch(Exception e){return null;}}
    private static void save(Context context,JSONObject record,boolean active)throws Exception{
        SharedPreferences.Editor editor=prefs(context).edit().putString(PREFIX+record.getString("id"),record.toString());
        String current=prefs(context).getString(ACTIVE,"");if(active)editor.putString(ACTIVE,record.getString("id"));else if(record.getString("id").equals(current))editor.remove(ACTIVE);
        if(!editor.commit())throw new IllegalStateException("recording_receipt_not_saved");
    }
    private static boolean activeState(String state){return "awaiting_user_consent".equals(state)||"scheduled".equals(state)||"starting".equals(state)||"recording".equals(state);}
    private static JSONObject publicStatus(JSONObject source)throws Exception{
        JSONObject out=new JSONObject();for(String key:new String[]{"id","state","requested_at","start_at_epoch_ms","started_at","finished_at","updated_at","duration_ms","elapsed_ms","target_package","target_text","target_text_observed","target_text_observed_at","output_name","width","height","bytes","sha256","media_uri","media_store_warning","audio","task_id","error"})if(source.has(key))out.put(key,source.get(key));
        out.put("recording_id",source.getString("id"));out.put("system_consent_required",true);out.put("artifact_path",source.optString("output_path"));return out;
    }
    private static JSONObject scope(JSONObject record)throws Exception{return new JSONObject().put("task_id",record.optString("task_id")).put("conversation",record.optString("conversation")).put("call_id",record.optString("call_id"));}
    private static void publish(Context context,JSONObject record,String type,String level,JSONObject data){try{AgentEvents.get(context).publish(type,"screen_record",scope(record),record.optString("target_package"),level,data);}catch(Exception ignored){}}
    private static String clean(Object error){String s=String.valueOf(error);return s.substring(0,Math.min(500,s.length()));}
    private static String sha256(File file)throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}StringBuilder out=new StringBuilder();for(byte x:digest.digest())out.append(String.format(Locale.US,"%02x",x&255));return out.toString();}
    private static Uri publishVideo(Context context,File source,String name)throws Exception{
        if(Build.VERSION.SDK_INT<29)return null;
        ContentResolver resolver=context.getContentResolver();ContentValues values=new ContentValues();values.put(MediaStore.Video.Media.DISPLAY_NAME,name);values.put(MediaStore.Video.Media.MIME_TYPE,"video/mp4");values.put(MediaStore.Video.Media.RELATIVE_PATH,"Movies/OPhoneAI");values.put(MediaStore.Video.Media.IS_PENDING,1);
        Uri uri=resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values);if(uri==null)throw new IllegalStateException("media_store_insert_failed");
        try(OutputStream out=resolver.openOutputStream(uri,"w");InputStream in=new FileInputStream(source)){if(out==null)throw new IllegalStateException("media_store_output_unavailable");byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);out.flush();}
        catch(Exception e){resolver.delete(uri,null,null);throw e;}
        ContentValues ready=new ContentValues();ready.put(MediaStore.Video.Media.IS_PENDING,0);resolver.update(uri,ready,null,null);return uri;
    }
}
