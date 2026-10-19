package ai.ophone.bridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import org.json.JSONObject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** No JS runs in the chat renderer or the bridge process. A failed worker is disposable. */
public final class ProgramRunner {
    private ProgramRunner(){}
    static void checkJsonDepth(String text){int depth=0;boolean quoted=false,escaped=false;for(int i=0;i<text.length();i++){char c=text.charAt(i);if(quoted){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='"')quoted=false;}else if(c=='"')quoted=true;else if(c=='{'||c=='['){if(++depth>32)throw new IllegalArgumentException("program_json_depth_exceeded");}else if(c=='}'||c==']')depth--;}}
    public static synchronized JSONObject run(Context context,String source,String input)throws Exception{
        if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("program_run_requires_worker_thread");
        if(android.os.Build.VERSION.SDK_INT<28)return new JSONObject().put("ok",false).put("error","program_requires_android_9_profile_isolation");
        if(source.length()>32768||input.length()>16384)throw new IllegalArgumentException("program_input_or_source_too_large");
        Context app=context.getApplicationContext();CountDownLatch done=new CountDownLatch(1);AtomicReference<JSONObject> result=new AtomicReference<>();
        Messenger reply=new Messenger(new Handler(Looper.getMainLooper()){
            @Override public void handleMessage(Message message){try{
                String raw=message.getData().getString("result","");if(raw.length()>40000)throw new IllegalArgumentException("program_output_too_large");
                checkJsonDepth(raw);JSONObject out=new JSONObject(raw);if(out.optInt("worker_pid")==android.os.Process.myPid())throw new IllegalStateException("program_worker_not_separate");
                result.compareAndSet(null,out);
            }catch(Exception error){try{result.compareAndSet(null,new JSONObject().put("ok",false).put("error","program_result_invalid"));}catch(Exception ignored){}}finally{done.countDown();}}
        });
        ServiceConnection connection=new ServiceConnection(){
            @Override public void onServiceConnected(ComponentName name,IBinder binder){try{
                Message request=Message.obtain();request.replyTo=reply;Bundle data=new Bundle();data.putString("source",source);data.putString("input",input);request.setData(data);new Messenger(binder).send(request);
            }catch(Exception error){done.countDown();}}
            @Override public void onServiceDisconnected(ComponentName name){done.countDown();}
            @Override public void onBindingDied(ComponentName name){done.countDown();}
            @Override public void onNullBinding(ComponentName name){done.countDown();}
        };
        boolean bound=app.bindService(new Intent(app,ProgramService.class),connection,Context.BIND_AUTO_CREATE);
        if(!bound)return new JSONObject().put("ok",false).put("error","program_worker_unavailable");
        try{done.await(7000,TimeUnit.MILLISECONDS);JSONObject out=result.get();return out!=null?out:new JSONObject().put("ok",false).put("error","program_worker_timeout_or_died");}
        finally{try{app.unbindService(connection);}catch(Exception ignored){}app.stopService(new Intent(app,ProgramService.class));Thread.sleep(250);}
    }
}
