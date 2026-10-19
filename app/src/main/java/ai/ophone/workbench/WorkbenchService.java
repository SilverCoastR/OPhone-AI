package ai.ophone.workbench;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.*;
import org.json.JSONObject;
import java.util.concurrent.*;

/** Built-in worker process; the component is private to this Android application. */
public final class WorkbenchService extends Service {
    static final String MAIN="ai.ophone.bridge";final ExecutorService pool=Executors.newFixedThreadPool(4);Workspace workspace;TerminalJobs jobs;StreamRecorder streams;BrowserManager browser;volatile Messenger client;java.io.File outbox;
    @Override public void onCreate(){super.onCreate();outbox=new java.io.File(getFilesDir(),"workbench_event_outbox");outbox.mkdirs();workspace=new Workspace(this);browser=BrowserManager.get(this);browser.events=this::event;jobs=new TerminalJobs(this,workspace,getFilesDir(),this::event);streams=new StreamRecorder(this,workspace,browser,this::event);}
    private boolean trusted(int uid){return uid==android.os.Process.myUid();}
    private final Messenger incoming=new Messenger(new Handler(Looper.getMainLooper()){@Override public void handleMessage(Message m){if(!trusted(m.sendingUid))return;Bundle data=m.getData();String id=data.getString("id","");if(m.what==3){if(id.matches("[0-9a-f-]{36}"))new java.io.File(outbox,id+".json").delete();return;}Messenger reply=m.replyTo;if(reply==null)return;client=reply;String op=data.getString("op",""),raw=data.getString("args","{}"),scopeRaw=data.getString("scope","{}");pool.execute(()->{replay();JSONObject result;try{if(raw.length()>400000||scopeRaw.length()>2000)throw new IllegalArgumentException("workbench_request_too_large");result=call(op,new JSONObject(raw),new JSONObject(scopeRaw));}catch(Exception e){result=new JSONObject();try{result.put("ok",false).put("error",e.getClass().getSimpleName()+": "+e.getMessage());}catch(Exception ignored){}}send(reply,id,result);});}});
    @Override public IBinder onBind(Intent intent){return incoming.getBinder();}
    private void send(Messenger reply,String id,JSONObject result){try{Message m=Message.obtain();Bundle b=new Bundle();b.putString("id",id);b.putString("result",result.toString());m.setData(b);reply.send(m);}catch(Exception ignored){}}
    private synchronized void event(String type,JSONObject scope,JSONObject payload){if(scope.optString("task_id").isEmpty())return;try{String id=java.util.UUID.randomUUID().toString();JSONObject brief=new JSONObject(payload.toString());brief.remove("stdout");brief.remove("stderr");JSONObject event=new JSONObject().put("event_id",id).put("type",type).put("scope",scope).put("payload",brief);Workspace.write(new java.io.File(outbox,id+".json"),event.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));replay();}catch(Exception ignored){}}
    private synchronized void replay(){Messenger target=client;if(target==null)return;java.io.File[] pending=outbox.listFiles();if(pending==null)return;for(java.io.File f:pending)try{if(!f.getName().endsWith(".json"))continue;Message m=Message.obtain();m.what=2;Bundle b=new Bundle();b.putString("event",new String(Workspace.read(f,40000),java.nio.charset.StandardCharsets.UTF_8));m.setData(b);target.send(m);}catch(Exception ignored){}}
    private JSONObject call(String op,JSONObject a,JSONObject scope)throws Exception {
        if("workbench_status".equals(op))return new JSONObject().put("ok",true).put("version","1.0.0").put("uid",android.os.Process.myUid()).put("worker_pid",android.os.Process.myPid()).put("built_in",true).put("shell_security","same app UID; app-private data is technically accessible to shell").put("browser_profile","agent_browser; separate cookies from ChatGPT").put("browser",android.os.Build.VERSION.SDK_INT>=28).put("browser_zoom",new JSONObject().put("pinch",true).put("visible_controls",true).put("fit_to_width",true).put("overview_default",true).put("normalized_percent",true)).put("http",true).put("direct_stream_recording",true).put("scheduled_live_watch",true).put("shell","/system/bin/sh").put("setsid",new java.io.File("/system/bin/setsid").canExecute()).put("workspace",workspace.root.getAbsolutePath()).put("limits",new JSONObject().put("tabs",6).put("running_jobs",4).put("terminal_timeout_ms",120000).put("http_response_bytes",8388608)).put("python_node_git","not bundled; discover executables with terminal_start").put("root",ai.ophone.bridge.RootAccess.status(this));
        if("cancel_task".equals(op)){String owner=scope.optString("task_id");if(owner.isEmpty())throw new IllegalArgumentException("task_id_required");return new JSONObject().put("ok",true).put("terminal_jobs_cancelled",jobs.cancelOwner(owner)).put("stream_recordings_cancelled",streams.cancelOwner(owner));}
        if("workbench_file".equals(op))return workspace.call(a);
        if("browser_tabs".equals(op))return browser.tabs(a);
        if("browser_open".equals(op))return browser.open(a,scope);
        if("browser_snapshot".equals(op))return browser.snapshot(a);
        if("browser_action".equals(op))return browser.action(a,scope);
        if("browser_eval".equals(op))return browser.evaluate(a,scope);
        if("browser_screenshot".equals(op))return browser.screenshot(a);
        if("browser_media".equals(op))return browser.media(a);
        if("stream_record_start".equals(op))return streams.start(a,scope);
        if("stream_record_watch_start".equals(op))return streams.watch(a,scope);
        if("stream_record_status".equals(op))return streams.status(a,scope);
        if("stream_record_stop".equals(op))return streams.stop(a,scope);
        if("terminal_start".equals(op))return jobs.start(a,scope);
        if("terminal_status".equals(op))return jobs.status(a,scope);
        if("terminal_input".equals(op))return jobs.input(a,scope);
        if("terminal_stop".equals(op))return jobs.stop(a,scope);
        if("http_request".equals(op)){JSONObject result=HttpTools.request(workspace,a);event(result.optBoolean("ok")?"http.completed":"http.failed",scope,new JSONObject().put("url",result.optString("url")).put("status",result.optInt("status")).put("bytes",result.optInt("bytes")).put("error",result.optString("error")));return result;}
        throw new IllegalArgumentException("unknown_workbench_tool");
    }
    @Override public void onDestroy(){jobs.close();streams.close();browser.events=null;pool.shutdownNow();super.onDestroy();}
}
