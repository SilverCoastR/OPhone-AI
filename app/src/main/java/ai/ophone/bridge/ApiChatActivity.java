package ai.ophone.bridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PictureInPictureParams;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Rational;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Direct-model chat rendered by the bundled WebView UI in assets/chat. Tool-call JSON stays in the transport; the page shows structured steps. */
public final class ApiChatActivity extends Activity {
    static final String ORIGIN="https://app.ophone.local";
    private static final Map<String,String> TYPES=new HashMap<>();
    static{TYPES.put("html","text/html");TYPES.put("js","application/javascript");TYPES.put("css","text/css");TYPES.put("woff2","font/woff2");TYPES.put("svg","image/svg+xml");TYPES.put("png","image/png");TYPES.put("jpg","image/jpeg");TYPES.put("jpeg","image/jpeg");TYPES.put("gif","image/gif");TYPES.put("webp","image/webp");TYPES.put("json","application/json");}
    private static java.lang.ref.WeakReference<ApiChatActivity> current=new java.lang.ref.WeakReference<>(null);
    static boolean inPictureInPicture(){ApiChatActivity a=current.get();return Build.VERSION.SDK_INT>=26&&a!=null&&!a.isDestroyed()&&a.isInPictureInPictureMode();}
    private DirectModelAgent agent;private ApiConversationStore store;private String shownConversation;private WebView web;private volatile boolean pageReady;private ChatUpdate updates;

    @Override protected void onCreate(Bundle state){super.onCreate(state);getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);store=new ApiConversationStore(this);shownConversation=store.resumeLatest();agent=new DirectModelAgent(this);updates=new ChatUpdate(this,()->emit("state"));
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);UiKit.edgeColors(this,UiKit.canvasColor(this));web=new WebView(this);web.setBackgroundColor(UiKit.canvasColor(this));
        WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setAllowFileAccess(false);s.setAllowContentAccess(false);s.setSupportZoom(false);s.setTextZoom(100);s.setMediaPlaybackRequiresUserGesture(true);
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){openExternal(request.getUrl().toString());return true;}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){return serve(ApiChatActivity.this,request.getUrl());}
            @Override public void onPageFinished(WebView view,String url){pageReady=true;}
        });
        web.addJavascriptInterface(new Bridge(),"OPhoneChat");setContentView(web);
        web.loadUrl(ORIGIN+"/chat/index.html?lang="+(Locale.getDefault().getLanguage().startsWith("zh")?"zh":"en")+"&dark="+(UiKit.isDark(this)?1:0));agent.attachUi(callbacks());
    }
    /** Only bundled chat assets and image files from the agent workspace are reachable; every other request is blocked. */
    static WebResourceResponse serve(android.content.Context c,Uri uri){
        try{String path=uri.getPath()==null?"":uri.getPath();if(!ORIGIN.equals(uri.getScheme()+"://"+uri.getHost())||path.contains(".."))return blocked();
            String ext=path.substring(path.lastIndexOf('.')+1).toLowerCase(Locale.ROOT),type=TYPES.get(ext);if(type==null)return blocked();InputStream in;
            if(path.startsWith("/chat/"))in=c.getAssets().open(path.substring(1));
            else if(path.startsWith("/workspace/")&&type.startsWith("image/")){File root=new File(c.getFilesDir(),"workbench_workspace").getCanonicalFile(),f=new File(root,Uri.decode(path.substring("/workspace/".length()))).getCanonicalFile();if(!f.getPath().startsWith(root.getPath()+File.separator)||!f.isFile())return blocked();in=new FileInputStream(f);}
            else return blocked();
            WebResourceResponse r=new WebResourceResponse(type,type.startsWith("text/")||type.endsWith("javascript")?"utf-8":null,in);Map<String,String> h=new HashMap<>();h.put("Cache-Control","no-store");r.setResponseHeaders(h);return r;
        }catch(Exception e){return blocked();}
    }
    private static WebResourceResponse blocked(){return new WebResourceResponse("text/plain","utf-8",404,"Not Found",new HashMap<>(),new ByteArrayInputStream(new byte[0]));}

    private void emit(JSONObject event){ui(()->{if(pageReady)web.evaluateJavascript("window.OPhoneChatApp&&window.OPhoneChatApp.event("+JSONObject.quote(event.toString())+")",null);});}
    private void emit(String type,Object... pairs){try{JSONObject e=new JSONObject().put("type",type);for(int i=0;i+1<pairs.length;i+=2)e.put((String)pairs[i],pairs[i+1]);emit(e);}catch(Exception ignored){}}
    private DirectModelAgent.Ui callbacks(){return new DirectModelAgent.Ui(){
        public void onStatus(String v){emit("status","text",v);}public void onResponseStart(){emit("response_start");}public void onText(String v){emit("text","text",v);}
        public void onTool(String name,String arguments){emit("tool","name",name,"args",arguments);}public void onQuestion(String question,String options){emit("question","text",question,"options",options);}public void onToolResult(String name,boolean ok,String summary){emit("tool_result","name",name,"ok",ok,"summary",summary);}
        public void onProgress(String value,String kind){emit("progress","text",value,"kind",kind);}public void onFinished(){emit("finished");}public void onError(String value){emit("error","text",getString(R.string.api_error,value));}
    };}

    /** Calls from the page arrive on a binder thread; anything touching views or activities hops to the UI thread. */
    private final class Bridge {
        @JavascriptInterface public String state(){try{ModelProvider p=agent.provider();ModelApiStore settings=p.store(ApiChatActivity.this);return new JSONObject().put("conversation",store.conversationId()).put("running",agent.isRunning()).put("has_key",settings.ready()).put("model",settings.config().model).put("provider",p.id).put("provider_label",p.label).put("update",updates.json()).toString();}catch(Exception e){return "{}";}}
        @JavascriptInterface public String history(){return viewModel(agent.history()).toString();}
        @JavascriptInterface public String conversations(){return store.conversations().toString();}
        @JavascriptInterface public String miniapps(){try{return MiniAppStore.list(ApiChatActivity.this).getJSONArray("apps").toString();}catch(Exception e){return "[]";}}
        @JavascriptInterface public void openMiniapp(String name){ui(()->{try{MiniAppStore.meta(ApiChatActivity.this,MiniAppStore.name(name));startActivity(MiniAppActivity.intent(ApiChatActivity.this,name));}catch(Exception e){Toast.makeText(ApiChatActivity.this,String.valueOf(e.getMessage()),Toast.LENGTH_SHORT).show();}});}
        @JavascriptInterface public String send(String text){try{String t=text==null?"":text.trim();if(t.isEmpty())return "empty";agent.attachUi(callbacks());if(agent.isRunning())return agent.guide(t)?"guided":"busy";String consent=handoffConsent();if(consent!=null)return consent;agent.send(t,callbacks());return "sent";}catch(Exception e){emit("error","text",getString(R.string.api_error,String.valueOf(e.getMessage())));return "error";}}
        /** The user agreed that this conversation's history may be sent to the current platform. */
        @JavascriptInterface public String allowHandoff(){try{store.putMeta(store.conversationId(),"handoff_ok:"+agent.provider().id,true);AuditLog.record(ApiChatActivity.this,"platform_handoff_consent",agent.provider().id,true,"conversation_history_shared_by_user_choice");return "ok";}catch(Exception e){return "error";}}
        /** Edit: everything from this message on is removed locally (and from future context), then the new text is sent. */
        @JavascriptInterface public String editMessage(String id,String text){try{String t=text==null?"":text.trim();if(t.isEmpty())return "empty";if(agent.isRunning())return toast(R.string.message_busy);String consent=handoffConsent();if(consent!=null)return consent;
            store.truncateFrom(store.conversationId(),id);agent.attachUi(callbacks());agent.send(t,callbacks());return "sent";}catch(Exception e){emit("error","text",getString(R.string.api_error,String.valueOf(e.getMessage())));return "error";}}
        @JavascriptInterface public void deleteMessage(String id){ui(()->confirmDeleteMessage(id));}
        @JavascriptInterface public void stop(){agent.terminate();}
        @JavascriptInterface public String newChat(){if(agent.isRunning())return toast(R.string.api_stop_first);agent.clear();ui(ApiChatActivity.this::reloadConversation);return "ok";}
        @JavascriptInterface public String select(String id){if(agent.isRunning()&&!id.equals(store.conversationId()))return toast(R.string.api_stop_first);try{store.select(id);}catch(Exception e){return "missing";}ui(ApiChatActivity.this::reloadConversation);return "ok";}
        @JavascriptInterface public void delete(String id){ui(()->confirmDelete(id));}
        @JavascriptInterface public void copy(String text){ui(()->{((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("OPhone AI",text));Toast.makeText(ApiChatActivity.this,R.string.copied,Toast.LENGTH_SHORT).show();});}
        @JavascriptInterface public void open(String target){ui(()->{
            if("settings".equals(target))startActivity(new Intent(ApiChatActivity.this,SettingsActivity.class));
            else if("model".equals(target))startActivity(ModelApiSettingsActivity.intent(ApiChatActivity.this,agent.provider()));
            else if("about".equals(target))startActivity(new Intent(ApiChatActivity.this,AboutActivity.class));
            else if("feedback".equals(target))startActivity(new Intent(ApiChatActivity.this,FeedbackActivity.class));
            else if("platform".equals(target))exitPlatform();
            else if("browser".equals(target))try{WorkbenchClient.get(ApiChatActivity.this).showBrowser();}catch(Exception e){Toast.makeText(ApiChatActivity.this,e.getMessage(),Toast.LENGTH_LONG).show();}
        });}
        @JavascriptInterface public void openLink(String url){ui(()->openExternal(url));}
        @JavascriptInterface public void update(){ui(updates::act);}
        private String toast(int res){ui(()->Toast.makeText(ApiChatActivity.this,res,Toast.LENGTH_SHORT).show());return "busy";}
    }

    /** Flattens stored provider messages into render items: user/answer text, structured tool steps and progress notes. */
    static JSONArray viewModel(JSONArray all){JSONArray out=new JSONArray();Map<String,JSONObject> steps=new HashMap<>();
        try{for(int i=0;i<all.length();i++){JSONObject m=all.optJSONObject(i);if(m==null||m.optBoolean("internal"))continue;String role=m.optString("role"),text=m.optString("content");long at=m.optLong("created_at");
            String id=m.optString("id");ModelProvider by=ModelProvider.of(m.optString("provider"));
            if("user".equals(role)){if(!text.isEmpty())out.put(new JSONObject().put("type","user").put("id",id).put("text",text).put("guidance",m.optBoolean("guidance")).put("at",at));}
            else if("assistant".equals(role)){JSONArray calls=m.optJSONArray("tool_calls");
                if(m.optBoolean("ask_user")){out.put(new JSONObject().put("type","question").put("text",text).put("options",m.optJSONArray("options")==null?new JSONArray():m.getJSONArray("options")).put("at",at));continue;}
                if(calls==null||calls.length()==0){if(!text.isEmpty())out.put(new JSONObject().put("type","answer").put("id",id).put("text",text).put("by",by==null?"":by.label).put("at",at));continue;}
                if(!text.trim().isEmpty())out.put(new JSONObject().put("type","note").put("text",text.trim()).put("at",at));
                for(int c=0;c<calls.length();c++){JSONObject call=calls.optJSONObject(c);if(call==null)continue;JSONObject fn=call.optJSONObject("function");String args=fn==null?"":fn.optString("arguments");if(args.length()>1200)args=args.substring(0,1200)+"…";JSONObject step=new JSONObject().put("type","step").put("name",fn==null?"tool":fn.optString("name")).put("args",args).put("state","pending").put("at",at);steps.put(call.optString("id"),step);out.put(step);}}
            else if("tool".equals(role)){JSONObject step=steps.get(m.optString("tool_call_id"));if(step==null)continue;JSONObject value;try{value=new JSONObject(text);}catch(Exception e){value=new JSONObject().put("ok",true).put("text",text);}step.put("state",value.optBoolean("ok")?"ok":"error").put("summary",DirectModelAgent.resultSummary(value));}
            else if("progress".equals(role)){String kind=m.optString("kind");if(!"tool".equals(kind)&&!"step".equals(kind)&&!text.isEmpty())out.put(new JSONObject().put("type","note").put("text",text).put("progress",true).put("at",at));}
        }}catch(Exception ignored){}
        return out;
    }

    private void reloadConversation(){shownConversation=store.conversationId();agent=new DirectModelAgent(this);agent.attachUi(callbacks());emit("reload");}
    /**
     * Platform handoff consent: when another API platform answered in this conversation, ask once before its history
     * goes to the current platform's company. Returns the request for the page, or null when consent exists.
     */
    private String handoffConsent()throws Exception{String conversation=store.conversationId();ModelProvider current=agent.provider();
        if(store.meta(conversation).optBoolean("handoff_ok:"+current.id))return null;ModelProvider previous=DirectModelAgent.previousProvider(store.messages(conversation),current);if(previous==null)return null;
        return new JSONObject().put("consent",new JSONObject().put("from",previous.label).put("to",current.label).put("company",current.company)).toString();}
    private void confirmDeleteMessage(String id){if(agent.isRunning()){Toast.makeText(this,R.string.message_busy,Toast.LENGTH_SHORT).show();return;}
        new AlertDialog.Builder(this).setTitle(R.string.message_delete_title).setMessage(R.string.message_delete_body).setNegativeButton(R.string.cancel,null)
                .setPositiveButton(R.string.delete_chat,(d,w)->{try{store.deleteMessage(store.conversationId(),id);emit("reload");}catch(Exception e){Toast.makeText(this,DetailedDiagnostics.clean(e.getMessage()),Toast.LENGTH_SHORT).show();}}).show();}
    private void confirmDelete(String id){if(DirectModelAgent.isConversationRunning(id)||PlatformSession.hasActiveConversation(this,id)){Toast.makeText(this,R.string.delete_chat_running,Toast.LENGTH_LONG).show();return;}
        new AlertDialog.Builder(this).setTitle(R.string.delete_chat_title).setMessage(R.string.delete_chat_message).setNegativeButton(R.string.cancel,null).setPositiveButton(R.string.delete_chat,(dialog,which)->{boolean active=id.equals(store.conversationId());store.delete(id);if(active)reloadConversation();else emit("conversations");}).show();}
    private void openExternal(String url){if(url==null||!(url.startsWith("https://")||url.startsWith("http://"))||url.startsWith(ORIGIN))return;try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}catch(Exception e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_SHORT).show();}}
    private void exitPlatform(){agent.exitPlatform();startActivity(new Intent(this,PlatformActivity.class).putExtra(PlatformPreference.EXTRA_SHOW_CHOOSER,true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));finish();}
    private void ui(Runnable r){runOnUiThread(()->{if(!isFinishing()&&!(Build.VERSION.SDK_INT>=17&&isDestroyed()))r.run();});}
    @Override protected void onResume(){super.onResume();current=new java.lang.ref.WeakReference<>(this);UpdateManager.maybeCheck(this);updates.attach();web.onResume();String activeId=store.conversationId();if((!activeId.equals(shownConversation)||agent.provider()!=ModelProvider.current(this))&&!agent.isRunning())reloadConversation();else{agent.attachUi(callbacks());emit("state");}}
    @Override protected void onPause(){updates.detach();web.onPause();super.onPause();}
    @Override protected void onDestroy(){agent.attachUi(null);web.removeJavascriptInterface("OPhoneChat");web.destroy();super.onDestroy();}
    @Override public void onUserLeaveHint(){super.onUserLeaveHint();if(Build.VERSION.SDK_INT>=26&&RiskPolicy.pipEnabled(this))try{enterPictureInPictureMode(new PictureInPictureParams.Builder().setAspectRatio(new Rational(9,16)).build());}catch(Exception ignored){}}
    @Override public void onBackPressed(){if(!pageReady){exitPlatform();return;}web.evaluateJavascript("window.OPhoneChatApp?window.OPhoneChatApp.back():false",value->{if(!"true".equals(value))exitPlatform();});}
}
