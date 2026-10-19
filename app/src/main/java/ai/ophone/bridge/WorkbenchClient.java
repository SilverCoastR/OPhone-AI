package ai.ophone.bridge;

import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** App-internal Binder, never a network relay or WebView debugging session. */
public final class WorkbenchClient {
    static final String PACKAGE="ai.ophone.bridge";
    static final String SERVICE="ai.ophone.workbench.WorkbenchService",BROWSER="ai.ophone.workbench.BrowserActivity";
    private static WorkbenchClient instance;final Context context;volatile Messenger remote;boolean bound;
    final Map<String,Pending> pending=new ConcurrentHashMap<>();
    static class Pending{final CountDownLatch done=new CountDownLatch(1);JSONObject result;}
    private WorkbenchClient(Context c){context=c.getApplicationContext();}
    public static synchronized WorkbenchClient get(Context c){if(instance==null)instance=new WorkbenchClient(c);return instance;}
    private final Messenger reply=new Messenger(new Handler(Looper.getMainLooper()){@Override public void handleMessage(Message message){try{
        if(message.what==2){JSONObject event=new JSONObject(message.getData().getString("event"));String type=event.getString("type"),id=event.getString("event_id");if(!Arrays.asList("browser.loaded","browser.failed","terminal.completed","terminal.failed","http.completed","http.failed","stream.started","stream.completed","stream.failed","stream.cancelled").contains(type))return;
            android.content.SharedPreferences prefs=context.getSharedPreferences("workbench_events",0);java.util.Set<String> seen=new java.util.HashSet<>(prefs.getStringSet("seen",java.util.Collections.emptySet()));
            if(!seen.contains(id)){JSONObject scope=event.getJSONObject("scope");AgentEvents.get(context).publish(type,"workbench",scope,PACKAGE,type.endsWith("failed")?"error":"info",event.getJSONObject("payload").put("workbench_event_id",id));if(seen.size()>=256)seen.clear();seen.add(id);prefs.edit().putStringSet("seen",seen).commit();}
            if(remote!=null){Message ack=Message.obtain();ack.what=3;Bundle b=new Bundle();b.putString("id",id);ack.setData(b);remote.send(ack);}return;}
        Pending p=pending.get(message.getData().getString("id"));if(p!=null){p.result=new JSONObject(message.getData().getString("result"));p.done.countDown();}
    }catch(Exception error){DetailedDiagnostics.event(context,"error","workbench","","","workbench_feedback_invalid",error.toString(),error);}}});
    private final ServiceConnection connection=new ServiceConnection(){
        public void onServiceConnected(ComponentName name,IBinder binder){remote=new Messenger(binder);}
        public void onServiceDisconnected(ComponentName name){lost();}
        public void onBindingDied(ComponentName name){lost();synchronized(WorkbenchClient.this){if(bound){try{context.unbindService(this);}catch(Exception ignored){}bound=false;}}}
        public void onNullBinding(ComponentName name){lost();}
    };
    private void lost(){remote=null;for(Pending p:pending.values())p.done.countDown();}
    public JSONObject availability()throws Exception{return new JSONObject().put("built_in",true).put("available",true).put("package",PACKAGE).put("process",":workbench").put("connected",remote!=null).put("browser_available",android.os.Build.VERSION.SDK_INT>=28).put("browser_profile_separate",android.os.Build.VERSION.SDK_INT>=28).put("shell","/system/bin/sh").put("native_http",true).put("root_shell",RootAccess.status(context)).put("shell_file_authority","same Android app UID; not an OS security sandbox");}
    private synchronized void bind()throws Exception {
        if(!bound){bound=context.bindService(new Intent().setComponent(new ComponentName(PACKAGE,SERVICE)),connection,Context.BIND_AUTO_CREATE);if(!bound)throw new IllegalStateException("internal_workbench_bind_failed");}
    }
    public JSONObject call(String tool,JSONObject args,JSONObject scope)throws Exception {
        if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("workbench_call_requires_worker_thread");bind();long end=System.currentTimeMillis()+5000;while(remote==null&&System.currentTimeMillis()<end)Thread.sleep(25);if(remote==null)throw new IllegalStateException("workbench_not_connected");
        String id=UUID.randomUUID().toString();Pending p=new Pending();pending.put(id,p);
        try{Message m=Message.obtain();m.replyTo=reply;Bundle data=new Bundle();data.putString("id",id);data.putString("op",tool);data.putString("args",args.toString());data.putString("scope",scope==null?"{}":scope.toString());m.setData(data);remote.send(m);
            p.done.await(25000,TimeUnit.MILLISECONDS);if(p.result==null)return new JSONObject().put("ok",false).put("error","workbench_reply_lost_or_timeout").put("outcome","unknown_do_not_replay_mutation_inspect_state");return p.result;
        }finally{pending.remove(id);}
    }
    public JSONObject cancelTask(String taskId,String conversation)throws Exception{if(Looper.myLooper()==Looper.getMainLooper()){new Thread(()->{try{call("cancel_task",new JSONObject(),new JSONObject().put("task_id",taskId).put("conversation",conversation));}catch(Exception error){DetailedDiagnostics.event(context,"warning","task",taskId,"","workbench_task_cancel_async_failed","Unable to cancel one workbench task asynchronously.",error);}},"OPhoneAI-workbench-cancel").start();return new JSONObject().put("ok",true).put("queued",true);}return call("cancel_task",new JSONObject(),new JSONObject().put("task_id",taskId).put("conversation",conversation));}
    public void showBrowser()throws Exception{if(android.os.Build.VERSION.SDK_INT<28)throw new IllegalStateException("browser_requires_android_9_for_separate_webview_profile");ChatActivity.prepareForExternalAutomation(context);context.startActivity(new Intent().setComponent(new ComponentName(PACKAGE,BROWSER)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
    public synchronized void close(){lost();if(bound){try{context.unbindService(connection);}catch(Exception ignored){}bound=false;}}
}
