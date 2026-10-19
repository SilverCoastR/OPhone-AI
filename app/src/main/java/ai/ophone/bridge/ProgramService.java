package ai.ophone.bridge;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.ByteArrayInputStream;

/** A disposable WebView host in :program, with a separate browser profile and no native JS interfaces. */
public final class ProgramService extends Service {
    private WebView view;private Messenger reply;private boolean finished,loaded,received;
    private String source,input,errorStack="";
    private final Handler main=new Handler(android.os.Looper.getMainLooper());
    @Override public void onCreate(){
        super.onCreate();
        if(android.os.Build.VERSION.SDK_INT<28)return;
        WebView.setDataDirectorySuffix("program_worker");
        view=new WebView(this);
        android.webkit.WebSettings s=view.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(false);s.setDatabaseEnabled(false);
        s.setAllowFileAccess(false);s.setAllowContentAccess(false);s.setAllowFileAccessFromFileURLs(false);s.setAllowUniversalAccessFromFileURLs(false);
        s.setBlockNetworkLoads(true);s.setJavaScriptCanOpenWindowsAutomatically(false);s.setSupportMultipleWindows(false);
        for(String name:new String[]{"searchBoxJavaBridge_","accessibility","accessibilityTraversal"})view.removeJavascriptInterface(name);
        view.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return true;}
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));}
            @Override public void onPageFinished(WebView v,String url){loaded=true;execute();}
            @Override public boolean onRenderProcessGone(WebView v,android.webkit.RenderProcessGoneDetail detail){complete(false,null,"program_renderer_terminated");return true;}
        });
        view.loadDataWithBaseURL("https://program.ophoneai.invalid/","<html><head><meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; connect-src 'none'; script-src 'unsafe-inline' 'unsafe-eval'\"></head><body></body></html>","text/html","UTF-8",null);
        main.postDelayed(()->complete(false,null,"program_timeout"),5000);
    }
    @Override public IBinder onBind(Intent intent){
        return new Messenger(new Handler(android.os.Looper.getMainLooper()){
            @Override public void handleMessage(Message message){
                if(received)return;received=true;reply=message.replyTo;
                if(android.os.Build.VERSION.SDK_INT<28){complete(false,null,"program_requires_android_9_profile_isolation");return;}
                source=message.getData().getString("source","");input=message.getData().getString("input","{}");execute();
            }
        }).getBinder();
    }
    private void execute(){
        if(!loaded||!received||source==null||finished)return;
        String code=source;source=null;
        if(code.length()>32768||input.length()>16384){complete(false,null,"program_input_or_source_too_large");return;}
        String script="(function(){var stringify=JSON.stringify;try{var input=JSON.parse("+JSONObject.quote(input)+");var fn=new Function('input',"+JSONObject.quote("'use strict';\n"+code)+");var value=fn(input);"
                +"if(value&&typeof value.then==='function')throw new Error('async_programs_not_supported');var out=stringify({ok:true,value:value===undefined?null:value});"
                +"return out.length>32768?stringify({ok:false,error:'program_output_too_large'}):out;}catch(e){return stringify({ok:false,error:String(e&&e.name||'Error')+': '+String(e&&e.message||e).slice(0,500),error_stack:String(e&&e.stack||'').slice(0,6000)});}})()";
        view.evaluateJavascript(script,raw->{
            try{if(raw.length()>80000)throw new IllegalArgumentException("program_output_too_large");Object text=new JSONTokener(raw).nextValue();if(!(text instanceof String))throw new IllegalStateException("program_no_json_result");ProgramRunner.checkJsonDepth((String)text);JSONObject out=new JSONObject((String)text);
                errorStack=DetailedDiagnostics.clean(out.optString("error_stack",""));complete(out.optBoolean("ok"),out.opt("value"),out.optString("error","program_failed"));
            }catch(Exception error){complete(false,null,"program_result_invalid");}
        });
    }
    private void complete(boolean ok,Object value,String error){
        if(finished)return;finished=true;
        try{JSONObject out=new JSONObject().put("ok",ok).put("worker_pid",android.os.Process.myPid()).put("worker_uid",android.os.Process.myUid())
                    .put("engine","Android WebView, separate process/profile, no native JS bridge").put("value",value==null?JSONObject.NULL:value);
            if(!ok)out.put("error",DetailedDiagnostics.clean(error)).put("error_stack",errorStack);
            Message result=Message.obtain();Bundle data=new Bundle();data.putString("result",out.toString());result.setData(data);if(reply!=null)reply.send(result);
        }catch(Exception ignored){}
        main.postDelayed(()->android.os.Process.killProcess(android.os.Process.myPid()),100);
    }
    @Override public boolean onUnbind(Intent intent){main.postDelayed(()->android.os.Process.killProcess(android.os.Process.myPid()),100);return false;}
}
