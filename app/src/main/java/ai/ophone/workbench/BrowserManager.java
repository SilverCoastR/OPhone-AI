package ai.ophone.workbench;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.*;
import android.os.*;
import android.util.Base64;
import android.net.Uri;
import android.webkit.*;
import org.json.*;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Web content has no native bridge. All privileged calls arrive over signature-checked Binder. */
final class BrowserManager {
    private static BrowserManager instance;
    static synchronized BrowserManager get(Context c){if(instance==null)instance=new BrowserManager(c.getApplicationContext());return instance;}
    final Context context; final Handler ui=new Handler(Looper.getMainLooper());final LinkedHashMap<String,Tab> tabs=new LinkedHashMap<>();String selected="";
    TerminalJobs.Events events; BrowserActivity activity;
    static final class MediaCandidate {String id="media-"+UUID.randomUUID(),url,kind;long seen=System.currentTimeMillis();Map<String,String> headers=new HashMap<>();}
    final class Tab {String id="tab-"+UUID.randomUUID(),page="page-"+UUID.randomUUID(),error="";WebView web;boolean loading,fitting,autoFitted;int progress;float baseScale;long changed;JSONObject scope=new JSONObject();final LinkedHashMap<String,MediaCandidate> media=new LinkedHashMap<>();
        // Set by browser_config: identity and request headers used for this tab's navigations.
        String userAgentPreset="default";boolean desktop;final LinkedHashMap<String,String> headers=new LinkedHashMap<>();}
    BrowserManager(Context c){context=c;}
    interface Work<T>{T run()throws Exception;}
    <T>T main(Work<T> work)throws Exception {if(Looper.myLooper()==Looper.getMainLooper())return work.run();CountDownLatch done=new CountDownLatch(1);AtomicReference<T> value=new AtomicReference<>();AtomicReference<Exception> error=new AtomicReference<>();ui.post(()->{try{value.set(work.run());}catch(Exception e){error.set(e);}finally{done.countDown();}});if(!done.await(5,TimeUnit.SECONDS))throw new IllegalStateException("browser_main_thread_timeout");if(error.get()!=null)throw error.get();return value.get();}
    static String url(String raw)throws Exception {URI u=new URI(raw);if(!("http".equalsIgnoreCase(u.getScheme())||"https".equalsIgnoreCase(u.getScheme()))||u.getHost()==null||u.getUserInfo()!=null)throw new IllegalArgumentException("browser_requires_http_or_https_url_without_embedded_credentials");return raw;}
    @SuppressLint("SetJavaScriptEnabled") Tab create()throws Exception {
        if(android.os.Build.VERSION.SDK_INT<28)throw new IllegalStateException("browser_requires_android_9_for_separate_webview_profile");if(tabs.size()>=6)throw new IllegalStateException("six_browser_tabs_limit");Tab t=new Tab();t.web=new WebView(context);WebSettings s=t.web.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setAllowFileAccess(false);s.setAllowContentAccess(false);s.setAllowFileAccessFromFileURLs(false);s.setAllowUniversalAccessFromFileURLs(false);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);s.setSupportMultipleWindows(false);s.setJavaScriptCanOpenWindowsAutomatically(false);s.setMediaPlaybackRequiresUserGesture(false);s.setSupportZoom(true);s.setBuiltInZoomControls(true);s.setDisplayZoomControls(false);s.setUseWideViewPort(true);s.setLoadWithOverviewMode(true);s.setTextZoom(100);t.web.setInitialScale(0);
        t.web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){try{url(r.getUrl().toString());return false;}catch(Exception ignored){return true;}}
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){capture(t,r.getUrl().toString(),r.getRequestHeaders());return super.shouldInterceptRequest(v,r);}
            @Override public void onPageStarted(WebView v,String u,Bitmap icon){t.page="page-"+UUID.randomUUID();t.loading=true;t.progress=0;t.autoFitted=false;t.error="";t.changed=System.currentTimeMillis();v.setInitialScale(0);refresh();}
            @Override public void onPageFinished(WebView v,String u){t.loading=false;t.changed=System.currentTimeMillis();if(t.baseScale<=0)t.baseScale=Math.max(.01f,v.getScale());fitToWidth(t);refresh();emit(t,t.error.isEmpty()?"browser.loaded":"browser.failed");}
            @Override public void onScaleChanged(WebView v,float oldScale,float newScale){refresh();}
            @Override public void onReceivedError(WebView v,WebResourceRequest r,WebResourceError e){if(r.isForMainFrame()){t.error=String.valueOf(e.getDescription());t.loading=false;t.fitting=false;t.changed=System.currentTimeMillis();emit(t,"browser.failed");refresh();}}
            @Override public void onReceivedHttpError(WebView v,WebResourceRequest r,WebResourceResponse e){if(r.isForMainFrame()){t.error="HTTP "+e.getStatusCode();refresh();}}
            @Override public boolean onRenderProcessGone(WebView v,RenderProcessGoneDetail detail){t.loading=false;t.fitting=false;t.error="browser_renderer_gone";emit(t,"browser.failed");return true;}
        });t.web.setWebChromeClient(new WebChromeClient(){@Override public void onReceivedTitle(WebView v,String title){refresh();}
            @Override public void onProgressChanged(WebView v,int value){t.progress=value;refresh();}
            @Override public boolean onShowFileChooser(WebView v,ValueCallback<Uri[]> callback,FileChooserParams params){return activity!=null&&activity.chooseFile(callback,params);}});
        // Downloads go to the system (browser or download manager); the agent uses http_request for files it needs.
        t.web.setDownloadListener((u,agent,disposition,mime,length)->{try{android.content.Intent i=new android.content.Intent(android.content.Intent.ACTION_VIEW,Uri.parse(u)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);context.startActivity(i);}catch(Exception ignored){}});tabs.put(t.id,t);selected=t.id;return t;
    }
    void refresh(){if(activity!=null)activity.refresh();}
    int zoomPercent(Tab t){if(t==null||t.web==null)return 100;float base=t.baseScale>0?t.baseScale:Math.max(.01f,context.getResources().getDisplayMetrics().density),current=Math.max(.01f,t.web.getScale());return Math.max(10,Math.min(500,Math.round(current/base*100)));}
    void fitToWidth(Tab t){
        if(t==null||t.web==null)return;t.autoFitted=true;t.fitting=true;float current=Math.max(.01f,t.web.getScale());if(t.baseScale<=0)t.baseScale=current;float reset=t.baseScale/current;if(Math.abs(reset-1f)>.01f)t.web.zoomBy(Math.max(.01f,Math.min(100f,reset)));
        String js="(()=>{const d=document.documentElement,b=document.body,sw=Math.max(d?d.scrollWidth:0,b?b.scrollWidth:0),iw=Math.max(1,window.innerWidth);return sw>iw*1.03?iw/sw:1;})()";
        ui.postDelayed(()->t.web.evaluateJavascript(js,value->{try{double factor=Double.parseDouble(String.valueOf(value).replace("\"",""));if(factor>0.01&&factor<0.98)t.web.zoomBy((float)Math.max(0.20,factor));}catch(Exception ignored){}t.fitting=false;refresh();}),100);ui.postDelayed(()->{if(t.fitting){t.fitting=false;refresh();}},1200);
    }
    void emit(Tab t,String type){try{if(events!=null&&!t.scope.optString("task_id").isEmpty())events.emit(type,t.scope,state(t));}catch(Exception ignored){}}
    Tab tab(String id)throws Exception {if(id.isEmpty())id=selected;Tab t=tabs.get(id);if(t==null)throw new IllegalArgumentException("browser_tab_not_found");return t;}
    JSONObject state(Tab t)throws Exception{return new JSONObject().put("ok",t.error.isEmpty()).put("tab_id",t.id).put("page_id",t.page).put("url",t.web.getUrl()==null?"":t.web.getUrl()).put("title",t.web.getTitle()).put("loading",t.loading||t.fitting).put("page_loading",t.loading).put("fitting",t.fitting).put("error",t.error).put("changed_at",t.changed);}
    JSONObject tabs(JSONObject a)throws Exception{return main(()->{String op=a.optString("action","list");if("select".equals(op)){selected=tab(a.getString("tab_id")).id;refresh();}else if("close".equals(op)){Tab t=tab(a.getString("tab_id"));tabs.remove(t.id);if(t.web.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)t.web.getParent()).removeView(t.web);t.web.destroy();if(selected.equals(t.id))selected=tabs.isEmpty()?"":tabs.keySet().iterator().next();refresh();}else if(!"list".equals(op))throw new IllegalArgumentException("unsupported_tab_action");JSONArray list=new JSONArray();for(Tab t:tabs.values())list.put(state(t));return new JSONObject().put("ok",true).put("selected",selected).put("tabs",list);});}
    JSONObject open(JSONObject a,JSONObject scope)throws Exception {
        String target=url(a.getString("url"));Tab t=main(()->{Tab item=a.optBoolean("new_tab",false)||selected.isEmpty()?create():tab(a.optString("tab_id"));selected=item.id;item.scope=new JSONObject(scope.toString());item.loading=true;item.error="";if(item.headers.isEmpty())item.web.loadUrl(target);else item.web.loadUrl(target,new HashMap<>(item.headers));refresh();return item;});
        long end=System.currentTimeMillis()+Math.max(0,Math.min(12000,a.optInt("wait_ms",8000)));while(System.currentTimeMillis()<end&&main(()->t.loading||t.fitting))Thread.sleep(80);JSONObject result=main(()->state(t));return result.put("navigation_completed",!result.optBoolean("loading"));
    }
    /* ---------- browser_config: the AI controls the browser's identity and behaviour per tab ---------- */

    static final String[] UA_PRESETS={"default","android_chrome","desktop_chrome","desktop_edge","mac_safari","iphone_safari","ipad_safari","custom"};
    private static String chromeVersion(String ua){java.util.regex.Matcher m=java.util.regex.Pattern.compile("Chrome/([0-9.]+)").matcher(ua==null?"":ua);return m.find()?m.group(1):"124.0.0.0";}
    static String userAgentFor(String preset,String webviewDefault){
        String chrome=chromeVersion(webviewDefault),major=chrome.split("\\.")[0]+".0.0.0";
        switch(preset){
            case "android_chrome":return webviewDefault.replace("; wv","").replace(" Version/4.0","");
            case "desktop_chrome":return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"+major+" Safari/537.36";
            case "desktop_edge":return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"+major+" Safari/537.36 Edg/"+major;
            case "mac_safari":return "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15";
            case "iphone_safari":return "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1";
            case "ipad_safari":return "Mozilla/5.0 (iPad; CPU OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1";
            default:return webviewDefault;
        }
    }
    private static final java.util.Set<String> BLOCKED_HEADERS=new java.util.HashSet<>(Arrays.asList("host","cookie","authorization","proxy-authorization","content-length","connection","transfer-encoding"));
    private JSONObject configState(Tab t)throws Exception{WebSettings s=t.web.getSettings();CookieManager c=CookieManager.getInstance();
        JSONObject out=new JSONObject().put("ok",true).put("tab_id",t.id).put("user_agent_preset",t.userAgentPreset).put("user_agent",s.getUserAgentString()).put("desktop_mode",t.desktop)
                .put("javascript",s.getJavaScriptEnabled()).put("images",!s.getBlockNetworkImage()).put("text_zoom",s.getTextZoom()).put("dom_storage",s.getDomStorageEnabled())
                .put("popups",s.getJavaScriptCanOpenWindowsAutomatically()).put("media_autoplay",!s.getMediaPlaybackRequiresUserGesture())
                .put("cookies",c.acceptCookie()).put("third_party_cookies",c.acceptThirdPartyCookies(t.web)).put("headers",new JSONObject(t.headers)).put("presets",new JSONArray(Arrays.asList(UA_PRESETS)));
        if(Build.VERSION.SDK_INT>=29)out.put("force_dark",s.getForceDark()==WebSettings.FORCE_DARK_ON?"on":s.getForceDark()==WebSettings.FORCE_DARK_OFF?"off":"auto");return out;}
    JSONObject config(JSONObject a)throws Exception {
        return main(()->{Tab t=selected.isEmpty()&&!a.has("tab_id")?create():tab(a.optString("tab_id"));WebSettings s=t.web.getSettings();CookieManager cookies=CookieManager.getInstance();
            if("get".equals(a.optString("action")))return configState(t);
            if(a.has("user_agent")||a.has("custom_user_agent")){String preset=a.optString("user_agent",a.has("custom_user_agent")?"custom":"default");String def=WebSettings.getDefaultUserAgent(context);
                if("custom".equals(preset)){String custom=a.optString("custom_user_agent").trim();if(custom.isEmpty()||custom.length()>512||custom.matches(".*[\\r\\n].*"))throw new IllegalArgumentException("custom_user_agent_required_single_line_max_512");s.setUserAgentString(custom);}
                else if(Arrays.asList(UA_PRESETS).contains(preset))s.setUserAgentString(userAgentFor(preset,def));else throw new IllegalArgumentException("unknown_user_agent_preset");
                t.userAgentPreset=preset;}
            if(a.has("desktop_mode")){t.desktop=a.getBoolean("desktop_mode");s.setUseWideViewPort(true);s.setLoadWithOverviewMode(true);
                if(t.desktop&&!a.has("user_agent")&&!a.has("custom_user_agent")){s.setUserAgentString(userAgentFor("desktop_chrome",WebSettings.getDefaultUserAgent(context)));t.userAgentPreset="desktop_chrome";}
                if(!t.desktop&&!a.has("user_agent")&&t.userAgentPreset.startsWith("desktop")){s.setUserAgentString(WebSettings.getDefaultUserAgent(context));t.userAgentPreset="default";}}
            if(a.has("javascript"))s.setJavaScriptEnabled(a.getBoolean("javascript"));
            if(a.has("images")){boolean on=a.getBoolean("images");s.setLoadsImagesAutomatically(on);s.setBlockNetworkImage(!on);}
            if(a.has("text_zoom"))s.setTextZoom(Math.max(50,Math.min(300,a.getInt("text_zoom"))));
            if(a.has("dom_storage"))s.setDomStorageEnabled(a.getBoolean("dom_storage"));
            if(a.has("popups")){boolean on=a.getBoolean("popups");s.setJavaScriptCanOpenWindowsAutomatically(on);}
            if(a.has("media_autoplay"))s.setMediaPlaybackRequiresUserGesture(!a.getBoolean("media_autoplay"));
            if(a.has("force_dark")&&Build.VERSION.SDK_INT>=29){String v=a.getString("force_dark");s.setForceDark("on".equals(v)?WebSettings.FORCE_DARK_ON:"off".equals(v)?WebSettings.FORCE_DARK_OFF:WebSettings.FORCE_DARK_AUTO);}
            if(a.has("cookies"))cookies.setAcceptCookie(a.getBoolean("cookies"));
            if(a.has("third_party_cookies"))cookies.setAcceptThirdPartyCookies(t.web,a.getBoolean("third_party_cookies"));
            if(a.optBoolean("clear_cookies"))cookies.removeAllCookies(null);
            if(a.optBoolean("clear_cache"))t.web.clearCache(true);
            if(a.has("accept_language")){String lang=a.getString("accept_language").trim();if(lang.isEmpty())t.headers.remove("Accept-Language");else if(lang.length()>100||!lang.matches("[A-Za-z0-9,;=.\\- ]+"))throw new IllegalArgumentException("invalid_accept_language");else t.headers.put("Accept-Language",lang);}
            JSONObject extra=a.optJSONObject("headers");
            if(extra!=null){for(Iterator<String> k=extra.keys();k.hasNext();){String name=k.next(),value=extra.optString(name);if(!name.matches("[A-Za-z0-9-]{1,64}")||BLOCKED_HEADERS.contains(name.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("header_not_allowed: "+name);
                if(value.isEmpty())t.headers.remove(name);else if(value.length()>512||value.matches(".*[\\r\\n].*"))throw new IllegalArgumentException("invalid_header_value: "+name);else t.headers.put(name,value);}
                if(t.headers.size()>12)throw new IllegalArgumentException("at_most_12_headers");}
            if(a.optBoolean("reload",true)&&t.web.getUrl()!=null){if(t.headers.isEmpty())t.web.reload();else t.web.loadUrl(t.web.getUrl(),new HashMap<>(t.headers));}
            refresh();return configState(t);});
    }

    private Object eval(Tab t,String source)throws Exception {CountDownLatch done=new CountDownLatch(1);AtomicReference<String> result=new AtomicReference<>();ui.post(()->t.web.evaluateJavascript(source,v->{result.set(v);done.countDown();}));if(!done.await(5,TimeUnit.SECONDS))throw new IllegalStateException("browser_js_timeout");String raw=result.get();if(raw==null||raw.length()>200000)throw new IllegalArgumentException("browser_js_result_too_large");return new JSONTokener(raw).nextValue();}
    private JSONObject evalObject(Tab t,String source)throws Exception {Object value=eval(t,source);if(value instanceof String)value=new JSONTokener((String)value).nextValue();if(!(value instanceof JSONObject))throw new IllegalStateException("browser_js_expected_object");return (JSONObject)value;}
    private Tab checked(JSONObject a)throws Exception{return main(()->{Tab t=tab(a.optString("tab_id"));if(a.has("page_id")&&!a.getString("page_id").equals(t.page))throw new IllegalStateException("stale_browser_page_take_fresh_snapshot");return t;});}
    JSONObject snapshot(JSONObject a)throws Exception {
        Tab t=checked(a);String page=main(()->t.page),nonce="refs-"+UUID.randomUUID();
        String js="(()=>{const refs=new Map(),items=[];let n=0;for(const e of document.querySelectorAll('a,button,input,textarea,select,[role=button],[contenteditable=true]')){const r=e.getBoundingClientRect(),s=getComputedStyle(e);if(r.width<=0||r.height<=0||s.visibility==='hidden'||s.display==='none')continue;if(items.length>=160)break;const id=String(++n);refs.set(id,e);items.push({ref:id,tag:e.tagName.toLowerCase(),type:e.type||'',text:(e.innerText||e.getAttribute('aria-label')||e.getAttribute('placeholder')||'').slice(0,200),value:e.type==='password'?'[password]':String(e.value||'').slice(0,200),href:e.href||'',disabled:!!e.disabled});}window.__ophone_aiRefs={nonce:"+JSONObject.quote(nonce)+",refs};return {ok:true,snapshot_id:"+JSONObject.quote(nonce)+",text:(document.body?document.body.innerText:'').slice(0,12000),elements:items,scroll_y:scrollY,height:document.documentElement.scrollHeight};})()";
        JSONObject dom=evalObject(t,js);JSONObject state=main(()->{if(!page.equals(t.page))throw new IllegalStateException("browser_navigated_during_snapshot");return state(t);});return state.put("dom",dom);
    }
    JSONObject action(JSONObject a,JSONObject scope)throws Exception {
        if(!a.has("page_id"))throw new IllegalArgumentException("page_id_required");Tab t=checked(a);String action=a.getString("action");
        if("back".equals(action)||"forward".equals(action)||"reload".equals(action)||"zoom_in".equals(action)||"zoom_out".equals(action)||"fit".equals(action)){main(()->{t.scope=new JSONObject(scope.toString());if("reload".equals(action))t.web.reload();else if("back".equals(action))t.web.goBack();else if("forward".equals(action))t.web.goForward();else if("zoom_in".equals(action))t.web.zoomIn();else if("zoom_out".equals(action))t.web.zoomOut();else fitToWidth(t);return null;});if(action.startsWith("zoom_")||"fit".equals(action))Thread.sleep(250);return main(()->state(t).put("action_requested",action).put("zoom_percent",zoomPercent(t)));}
        if(!Arrays.asList("click","type","select","scroll").contains(action))throw new IllegalArgumentException("unsupported_browser_action");
        main(()->{t.scope=new JSONObject(scope.toString());return null;});
        String js="(()=>{const a="+a.toString()+";if(a.action==='scroll'){scrollBy(Number(a.dx)||0,Number(a.dy)||600);return {ok:true};}const s=window.__ophone_aiRefs;if(!s||s.nonce!==a.snapshot_id)return {ok:false,error:'stale_browser_snapshot'};const e=s.refs.get(String(a.ref));if(!e||!e.isConnected)return {ok:false,error:'stale_browser_element'};if(e.disabled)return {ok:false,error:'element_disabled'};e.scrollIntoView({block:'center'});if(a.action==='click'){e.click();}else if(a.action==='type'){e.focus();if(e.isContentEditable)e.textContent=String(a.text||'');else{const p=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;const d=Object.getOwnPropertyDescriptor(p,'value');if(!d||!('value'in e))return {ok:false,error:'element_not_text_input'};d.set.call(e,String(a.text||''));}e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));}else{e.value=String(a.value||'');e.dispatchEvent(new Event('change',{bubbles:true}));}return {ok:true,action:a.action};})()";
        return evalObject(t,js).put("tab_id",t.id).put("page_id",main(()->t.page));
    }
    JSONObject evaluate(JSONObject a,JSONObject scope)throws Exception {
        if(!a.has("page_id"))throw new IllegalArgumentException("page_id_required");Tab t=checked(a);String code=a.getString("script");if(code.length()>32768)throw new IllegalArgumentException("browser_script_too_large");String key="__sjEval"+UUID.randomUUID().toString().replace("-","");String page=main(()->t.page);main(()->{t.scope=new JSONObject(scope.toString());return null;});
        eval(t,"(()=>{window["+JSONObject.quote(key)+"]={done:false};Promise.resolve().then(()=>{return ("+code+"\n)}).then(value=>{try{const s=JSON.stringify(value===undefined?null:value);window["+JSONObject.quote(key)+"]={done:true,ok:s.length<=32768,value:s.length<=32768?JSON.parse(s):null,error:s.length>32768?'browser_eval_output_too_large':''};}catch(e){window["+JSONObject.quote(key)+"]={done:true,ok:false,error:String(e)};}},e=>window["+JSONObject.quote(key)+"]={done:true,ok:false,error:String(e)});return true;})()");
        for(int i=0;i<45;i++){if(!main(()->page.equals(t.page)))throw new IllegalStateException("browser_navigated_during_eval");JSONObject out=evalObject(t,"window["+JSONObject.quote(key)+"]||{done:false}");if(out.optBoolean("done")){eval(t,"delete window["+JSONObject.quote(key)+"]");return out.put("tab_id",t.id).put("page_id",page);}Thread.sleep(100);}eval(t,"delete window["+JSONObject.quote(key)+"]");throw new IllegalStateException("browser_async_eval_timeout");
    }
    private static String mediaKind(String value){String s=value.toLowerCase(Locale.ROOT);if(s.matches(".*\\.m3u8(?:[?#].*)?$"))return "hls";if(s.matches(".*\\.flv(?:[?#].*)?$"))return "flv";if(s.matches(".*\\.(mp4|m4v)(?:[?#].*)?$"))return "mp4";if(s.contains("liveplay")||s.contains("live_pull")||s.contains("/pull/"))return "live_stream";return "";}
    private void capture(Tab t,String raw,Map<String,String> headers){try{String kind=mediaKind(raw);if(kind.isEmpty())return;for(MediaCandidate old:t.media.values())if(old.url.equals(raw)){old.seen=System.currentTimeMillis();return;}MediaCandidate item=new MediaCandidate();item.url=url(raw);item.kind=kind;if(headers!=null)item.headers.putAll(headers);t.media.put(item.id,item);while(t.media.size()>64)t.media.remove(t.media.keySet().iterator().next());}catch(Exception ignored){}}
    JSONObject media(JSONObject a)throws Exception {Tab t=checked(a);String js="(()=>Array.from(new Set([].concat(Array.from(document.querySelectorAll('video,source')).map(e=>e.currentSrc||e.src||''),performance.getEntriesByType('resource').map(e=>e.name)).filter(u=>/^(https?:).*?(\\.m3u8|\\.flv|\\.mp4|liveplay|live_pull|\\/pull\\/)/i.test(u)))).slice(-64))()";Object value=eval(t,js);if(value instanceof JSONArray)for(int i=0;i<((JSONArray)value).length();i++)capture(t,((JSONArray)value).optString(i),null);JSONArray out=new JSONArray();for(MediaCandidate item:t.media.values()){URL u=new URL(item.url);out.put(new JSONObject().put("media_id",item.id).put("kind",item.kind).put("host",u.getHost()).put("path",u.getPath()).put("seen_at",item.seen).put("query_redacted",u.getQuery()!=null));}return main(()->state(t)).put("ok",true).put("media",out).put("privacy","Signed query strings and browser cookies stay inside the workbench process.");}
    MediaCandidate resolveMedia(String tabId,String mediaId)throws Exception {return main(()->{Tab t=tab(tabId);MediaCandidate item=t.media.get(mediaId);if(item==null)throw new IllegalArgumentException("browser_media_candidate_not_found_refresh_browser_media");return item;});}
    JSONObject screenshot(JSONObject a)throws Exception {Tab t=checked(a);return main(()->{int w=t.web.getWidth(),h=t.web.getHeight();if(w<=0||h<=0||t.web.getParent()==null)throw new IllegalStateException("show_browser_before_screenshot");float scale=Math.min(1f,800f/w);Bitmap b=Bitmap.createBitmap(Math.max(1,(int)(w*scale)),Math.max(1,(int)(h*scale)),Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(b);canvas.scale(scale,scale);t.web.draw(canvas);ByteArrayOutputStream out=new ByteArrayOutputStream();b.compress(Bitmap.CompressFormat.JPEG,75,out);b.recycle();return state(t).put("image_base64",Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP)).put("mime_type","image/jpeg");});}
}
