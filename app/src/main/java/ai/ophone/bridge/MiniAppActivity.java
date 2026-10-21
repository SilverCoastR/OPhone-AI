package ai.ophone.bridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hosts one AI-written mini app. The page has no network access (CSP header plus request interception);
 * it reaches phone tools only through OPhone.call, limited to the tools it declared and the user approved.
 * Calls run in a native task of their own, so receipts, budgets and the audit log apply as usual.
 */
public final class MiniAppActivity extends Activity {
    @Override protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(base); UiKit.applyTheme(this); }
    static final String EXTRA_NAME="name",ORIGIN="https://miniapp.ophone.local";
    private static final String CSP="default-src 'self'; script-src 'self' 'unsafe-inline' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; media-src 'self' blob:; font-src 'self' data:; connect-src 'none'; frame-src 'none'; object-src 'none'; form-action 'none'; base-uri 'none'";
    private static final Map<String,String> TYPES=new HashMap<>();
    static{TYPES.put("html","text/html");TYPES.put("js","application/javascript");TYPES.put("css","text/css");TYPES.put("woff2","font/woff2");TYPES.put("svg","image/svg+xml");TYPES.put("png","image/png");TYPES.put("jpg","image/jpeg");TYPES.put("jpeg","image/jpeg");TYPES.put("gif","image/gif");TYPES.put("webp","image/webp");TYPES.put("mp4","video/mp4");TYPES.put("webm","video/webm");}
    private String name;private JSONObject meta;private WebView web;private final ExecutorService worker=Executors.newFixedThreadPool(2);private final String session=UUID.randomUUID().toString().substring(0,8);private volatile JSONObject task;

    static Intent intent(Context c,String name){return new Intent(c,MiniAppActivity.class).putExtra(EXTRA_NAME,name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK);}

    @Override protected void onCreate(Bundle state){super.onCreate(state);
        try{name=MiniAppStore.name(getIntent().getStringExtra(EXTRA_NAME));meta=MiniAppStore.meta(this,name);}catch(Exception e){Toast.makeText(this,String.valueOf(e.getMessage()),Toast.LENGTH_LONG).show();finish();return;}
        setTitle(meta.optString("title",name));
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(UiKit.pageColor(this));
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(UiKit.dp(this,16),UiKit.dp(this,4),UiKit.dp(this,4),UiKit.dp(this,4));
        TextView title=new TextView(this);title.setText(meta.optString("title",name));title.setTypeface(Typeface.DEFAULT_BOLD);title.setTextSize(16);title.setTextColor(UiKit.primaryTextColor(this));bar.addView(title,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));
        TextView badge=UiKit.body(this,getString(R.string.miniapp_badge));badge.setTextSize(12);bar.addView(badge);
        Button close=UiKit.plainButton(this,"✕",false);close.setTextColor(UiKit.secondaryTextColor(this));close.setOnClickListener(v->finish());bar.addView(close,new LinearLayout.LayoutParams(UiKit.dp(this,48),UiKit.dp(this,48)));
        root.addView(bar);
        web=new WebView(this);WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setAllowFileAccess(false);s.setAllowContentAccess(false);s.setSupportMultipleWindows(false);s.setJavaScriptCanOpenWindowsAutomatically(false);s.setTextZoom(100);
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){String u=r.getUrl().toString();if(u.startsWith("http://")||u.startsWith("https://")&&!u.startsWith(ORIGIN))try{WebPageActivity.open(MiniAppActivity.this,u);}catch(Exception ignored){}return !u.startsWith(ORIGIN);}
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){return serve(r.getUrl());}
        });
        web.addJavascriptInterface(new Bridge(),"OPhoneNative");root.addView(web,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1));setContentView(root);
        web.loadUrl(ORIGIN+"/app/index.html");
    }

    private WebResourceResponse serve(Uri uri){
        try{String path=uri.getPath()==null?"":uri.getPath();if(!ORIGIN.equals(uri.getScheme()+"://"+uri.getHost())||path.contains(".."))return blocked();
            String ext=path.substring(path.lastIndexOf('.')+1).toLowerCase(Locale.ROOT),type=TYPES.get(ext);if(type==null)return blocked();InputStream in;
            if("/app/index.html".equals(path)){String html=MiniAppStore.html(this,name),tag="<script src=\"/__ophone.js\"></script>";int head=html.toLowerCase(Locale.ROOT).indexOf("<head>");
                html=head>=0?html.substring(0,head+6)+tag+html.substring(head+6):tag+html;in=new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8));}
            else if("/__ophone.js".equals(path))in=getAssets().open("miniapp/ophone.js");
            else if(path.startsWith("/lib/"))in=getAssets().open("chat/vendor/"+path.substring(5));
            else if(path.startsWith("/workspace/")&&(type.startsWith("image/")||type.startsWith("video/"))){File f=LocalFileProvider.workspaceFile(this,Uri.decode(path.substring("/workspace/".length())));if(f==null)return blocked();in=new FileInputStream(f);}
            else return blocked();
            WebResourceResponse r=new WebResourceResponse(type,type.startsWith("text/")||type.endsWith("javascript")?"utf-8":null,in);Map<String,String> h=new HashMap<>();h.put("Content-Security-Policy",CSP);h.put("Cache-Control","no-store");r.setResponseHeaders(h);return r;
        }catch(Exception e){return blocked();}
    }
    private static WebResourceResponse blocked(){return new WebResourceResponse("text/plain","utf-8",404,"Not Found",new HashMap<>(),new ByteArrayInputStream(new byte[0]));}

    /** The user approves the declared tool list once per declaration; changing the list asks again. */
    private boolean approved()throws Exception{
        if(MiniAppStore.meta(this,name).optBoolean("approved"))return true;
        CountDownLatch done=new CountDownLatch(1);AtomicBoolean allow=new AtomicBoolean();JSONArray perms=meta.optJSONArray("permissions");String list=perms==null?"":perms.join(", ").replace("\"","");
        runOnUiThread(()->{if(isFinishing()){done.countDown();return;}
            new AlertDialog.Builder(this).setTitle(getString(R.string.miniapp_permission_title,meta.optString("title",name))).setMessage(getString(R.string.miniapp_permission_body,list))
                    .setNegativeButton(R.string.cancel,(d,w)->done.countDown()).setPositiveButton(R.string.miniapp_allow,(d,w)->{allow.set(true);done.countDown();}).setOnCancelListener(d->done.countDown()).show();});
        done.await();if(!allow.get())return false;JSONObject m=MiniAppStore.meta(this,name);m.put("approved",true);MiniAppStore.saveMeta(this,m);return true;
    }
    private synchronized JSONObject scope()throws Exception{
        if(task==null)task=TaskController.get(this).begin("c:miniapp."+name,"miniapp-"+name+"-"+session,"Mini app session: "+meta.optString("title",name));
        return new JSONObject().put("conversation",task.getString("conversation")).put("task_id",task.getString("id")).put("call_id","miniapp-"+UUID.randomUUID());
    }
    private JSONObject invoke(String tool,JSONObject args)throws Exception{
        JSONArray perms=MiniAppStore.meta(this,name).optJSONArray("permissions");boolean declared=false;for(int i=0;perms!=null&&i<perms.length();i++)if(tool.equals(perms.optString(i)))declared=true;
        if(!declared)return new JSONObject().put("ok",false).put("error","tool_not_declared_in_miniapp_permissions:"+tool);
        if(MiniAppStore.FORBIDDEN.contains(tool)||("terminal_start".equals(tool)&&args.optBoolean("root")))return new JSONObject().put("ok",false).put("error","tool_not_available_to_miniapps");
        if(!approved())return new JSONObject().put("ok",false).put("error","user_denied_miniapp_permissions");
        return new LocalToolKernel(this).call(tool,args,scope());
    }

    private final class Bridge {
        @JavascriptInterface public void call(String id,String tool,String argsJson){worker.execute(()->{JSONObject result;
            try{result=invoke(tool,new JSONObject(argsJson==null||argsJson.isEmpty()?"{}":argsJson));}catch(Throwable e){result=new JSONObject();try{result.put("ok",false).put("error",DetailedDiagnostics.clean(e.getClass().getSimpleName()+": "+e.getMessage()));}catch(Exception ignored){}}
            String js="window.__ophoneResolve("+JSONObject.quote(id)+","+JSONObject.quote(result.toString())+")";runOnUiThread(()->{if(web!=null)web.evaluateJavascript(js,null);});});}
        @JavascriptInterface public String storageGet(String key){try{return new JSONObject().put("v",MiniAppStore.storageGet(MiniAppActivity.this,name,key)==null?JSONObject.NULL:MiniAppStore.storageGet(MiniAppActivity.this,name,key)).toString();}catch(Exception e){return "{\"v\":null}";}}
        @JavascriptInterface public void storageSet(String key,String value){try{MiniAppStore.storageSet(MiniAppActivity.this,name,key,value);}catch(Exception e){toast(String.valueOf(e.getMessage()));}}
        @JavascriptInterface public void toast(String text){runOnUiThread(()->Toast.makeText(MiniAppActivity.this,text,Toast.LENGTH_SHORT).show());}
        @JavascriptInterface public void close(){runOnUiThread(MiniAppActivity.this::finish);}
        @JavascriptInterface public String info(){try{return new JSONObject().put("name",name).put("title",meta.optString("title")).put("dark",UiKit.isDark(MiniAppActivity.this)).put("language",Locale.getDefault().getLanguage()).put("permissions",meta.optJSONArray("permissions")).toString();}catch(Exception e){return "{}";}}
    }

    @Override protected void onDestroy(){worker.shutdownNow();JSONObject t=task;
        if(t!=null)new Thread(()->{try{TaskController.get(getApplicationContext()).finish(new JSONObject().put("conversation",t.getString("conversation")).put("task_id",t.getString("id")).put("call_id","miniapp-close"),new JSONObject().put("outcome","cancelled").put("reason","Mini app closed by the user."));}catch(Exception ignored){}}).start();
        if(web!=null){web.removeJavascriptInterface("OPhoneNative");web.destroy();web=null;}super.onDestroy();}
}
