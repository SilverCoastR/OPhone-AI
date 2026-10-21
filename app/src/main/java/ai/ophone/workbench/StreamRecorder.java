package ai.ophone.workbench;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.webkit.CookieManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Records a browser-discovered live media stream without capturing the screen. */
final class StreamRecorder {
    interface Events {void emit(String type,JSONObject scope,JSONObject payload);}
    static final long MAX_BYTES=1024L*1024L*1024L;
    final Context context;final Workspace workspace;final BrowserManager browser;final Events events;final File receipts;
    final ExecutorService worker=Executors.newCachedThreadPool();final Map<String,Job> jobs=new ConcurrentHashMap<>();
    final class Job {String id,owner,name,kind,state="starting",error="",safeSource,mediaUri="",tabId="",requestedName="",pageUrl="";JSONObject scope;File file;long requested,started,finished,duration,bytes,startNotBefore,deadline,lastReload;boolean publish=true,watch,browserShown;volatile boolean stop;}
    StreamRecorder(Context c,Workspace w,BrowserManager b,Events e){context=c.getApplicationContext();workspace=w;browser=b;events=e;receipts=new File(c.getFilesDir(),"workbench_stream_receipts");receipts.mkdirs();worker.execute(this::restoreWatchers);}

    JSONObject start(JSONObject a,JSONObject scope)throws Exception{
        if(scope==null||scope.optString("task_id").isEmpty())throw new IllegalArgumentException("native_task_required");int seconds=a.optInt("duration_seconds",60);if(seconds<5||seconds>180)throw new IllegalArgumentException("stream_duration_must_be_5_to_180_seconds");
        BrowserManager.MediaCandidate media=browser.resolveMedia(a.optString("tab_id"),a.getString("media_id"));String id="stream-"+UUID.randomUUID();Job j=new Job();j.id=id;j.owner=scope.getString("task_id");j.scope=new JSONObject(scope.toString());j.kind=media.kind;j.duration=seconds*1000L;j.requested=System.currentTimeMillis();j.publish=!a.optBoolean("_test_no_publish",false);j.safeSource=safeUrl(media.url);String ext="hls".equals(media.kind)?".ts":"mp4".equals(media.kind)?".mp4":".flv";String requested=a.optString("output_name","");j.name=safeName(requested.isEmpty()?"OPhoneAI-live-"+j.requested+ext:requested,ext);File dir=workspace.resolve("recordings");dir.mkdirs();j.file=new File(dir,id+"-"+j.name);jobs.put(id,j);save(j);worker.execute(()->run(j,media));return snapshot(j).put("ok",true).put("next_step","Poll stream_record_status for this stream_id. The signed media URL and browser cookies remain inside the workbench process.");
    }
    JSONObject watch(JSONObject a,JSONObject scope)throws Exception{
        if(scope==null||scope.optString("task_id").isEmpty())throw new IllegalArgumentException("native_task_required");
        int seconds=a.optInt("duration_seconds",60);if(seconds<5||seconds>180)throw new IllegalArgumentException("stream_duration_must_be_5_to_180_seconds");
        String tabId=a.getString("tab_id");String source=browser.main(()->browser.state(browser.tab(tabId)).optString("url"));if(source.isEmpty())throw new IllegalStateException("browser_tab_has_no_page");
        long now=System.currentTimeMillis(),start=Math.max(now,a.optLong("start_at_epoch_ms",now)),deadline=a.optLong("deadline_epoch_ms",start+3*60*60*1000L);
        if(start>now+24*60*60*1000L||deadline<=start||deadline>start+24*60*60*1000L)throw new IllegalArgumentException("live_watch_time_out_of_range");
        String id="stream-"+UUID.randomUUID();Job j=new Job();j.id=id;j.owner=scope.getString("task_id");j.scope=new JSONObject(scope.toString());j.kind="pending";j.duration=seconds*1000L;j.requested=now;j.publish=!a.optBoolean("_test_no_publish",false);j.pageUrl=source;j.safeSource=safeUrl(source);j.tabId=tabId;j.requestedName=a.optString("output_name","");j.startNotBefore=start;j.deadline=deadline;j.lastReload=now;j.watch=true;j.state=start>now?"scheduled":"waiting_for_live";
        File dir=workspace.resolve("recordings");dir.mkdirs();j.name=safeName(j.requestedName.isEmpty()?"OPhoneAI-live-"+j.requested+".ts":j.requestedName,".ts");j.file=new File(dir,id+"-"+j.name);
        browser.main(()->{browser.tab(tabId).media.clear();return null;});jobs.put(id,j);save(j);worker.execute(()->watch(j));
        return snapshot(j).put("ok",true).put("watching_for_live",true).put("next_step","The workbench will reload this tab after start_at_epoch_ms until live media appears, then record it once. Poll this same stream_id; do not start another watcher.");
    }
    JSONObject status(JSONObject a,JSONObject scope)throws Exception{String id=a.getString("stream_id");if(!id.matches("stream-[0-9a-f-]{36}"))throw new IllegalArgumentException("invalid_stream_id");Job j=jobs.get(id);JSONObject out=j==null?read(id):snapshot(j);if(!scope.optString("task_id").equals(out.optString("task_id")))throw new SecurityException("stream_task_mismatch");return out.put("ok",true);}
    JSONObject stop(JSONObject a,JSONObject scope)throws Exception{String id=a.getString("stream_id");Job j=jobs.get(id);if(j==null)return status(a,scope);if(!j.owner.equals(scope.optString("task_id")))throw new SecurityException("stream_task_mismatch");j.stop=true;return snapshot(j).put("ok",true);}
    int cancelOwner(String owner){int count=0;for(Job j:jobs.values())if(owner.equals(j.owner)&&j.finished==0){j.stop=true;count++;}return count;}
    void close(){for(Job j:jobs.values())if(j.finished==0)j.stop=true;worker.shutdownNow();}

    private void restoreWatchers(){
        File[] files=receipts.listFiles((dir,name)->name.matches("stream-[0-9a-f-]{36}\\.json"));if(files==null)return;
        for(File file:files)try{
            JSONObject saved=new JSONObject(new String(Workspace.read(file,65536),"UTF-8"));String state=saved.optString("state");long deadline=saved.optLong("deadline_epoch_ms");
            if(!saved.optBoolean("watching_for_live")||deadline<=System.currentTimeMillis()||!java.util.Arrays.asList("scheduled","waiting_for_live","recording").contains(state))continue;
            Job j=new Job();j.id=saved.getString("stream_id");j.owner=saved.getString("task_id");j.scope=new JSONObject().put("task_id",j.owner);j.name=saved.optString("output_name");j.kind="pending";j.state="waiting_for_live";j.error="";j.safeSource=saved.optString("source");j.pageUrl=saved.optString("_page_url");j.requestedName=saved.optString("_requested_name");j.requested=saved.optLong("requested_at");j.startNotBefore=saved.optLong("start_at_epoch_ms");j.deadline=deadline;j.duration=saved.optLong("duration_ms",60000);j.publish=saved.optBoolean("_publish",true);j.watch=true;j.file=new File(saved.getString("artifact_path"));
            if(j.pageUrl.isEmpty()){j.state="failed";j.error="live_watch_page_not_restorable";j.finished=System.currentTimeMillis();save(j);continue;}
            JSONObject opened=browser.open(new JSONObject().put("url",j.pageUrl).put("new_tab",true).put("wait_ms",8000),j.scope);j.tabId=opened.getString("tab_id");j.lastReload=System.currentTimeMillis();jobs.put(j.id,j);save(j);worker.execute(()->watch(j));
        }catch(Exception ignored){}
    }

    private boolean screenUsable(){
        try{android.os.PowerManager pm=(android.os.PowerManager)context.getSystemService(android.content.Context.POWER_SERVICE);
            android.app.KeyguardManager km=(android.app.KeyguardManager)context.getSystemService(android.content.Context.KEYGUARD_SERVICE);
            return (pm==null||pm.isInteractive())&&(km==null||!km.isKeyguardLocked());}catch(Exception e){return false;}
    }
    private void watch(Job j){
        try{
            while(!j.stop&&System.currentTimeMillis()<j.deadline){
                long now=System.currentTimeMillis();if(now<j.startNotBefore){Thread.sleep(Math.min(5000,j.startNotBefore-now));continue;}
                j.state="waiting_for_live";save(j);
                // The live page plays in the browser: brought to the front once per job, and only on an unlocked, lit screen.
                if(!j.browserShown&&screenUsable()){j.browserShown=true;try{context.startActivity(new android.content.Intent(context,BrowserActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));}catch(Exception ignored){}}
                try{
                    JSONObject observed=browser.media(new JSONObject().put("tab_id",j.tabId));JSONArray found=observed.optJSONArray("media");JSONObject selected=null;
                    for(int i=0;found!=null&&i<found.length();i++){JSONObject item=found.getJSONObject(i);if(item.optLong("seen_at")>=j.startNotBefore-60000&&(selected==null||item.optLong("seen_at")>selected.optLong("seen_at")))selected=item;}
                    if(selected!=null){BrowserManager.MediaCandidate media=browser.resolveMedia(j.tabId,selected.getString("media_id"));String ext="hls".equals(media.kind)?".ts":"mp4".equals(media.kind)?".mp4":".flv";j.kind=media.kind;j.safeSource=safeUrl(media.url);j.name=safeName(j.requestedName.isEmpty()?"OPhoneAI-live-"+j.requested+ext:j.requestedName,ext);j.file=new File(workspace.resolve("recordings"),j.id+"-"+j.name);j.error="";run(j,media);return;}
                    if(now-j.lastReload>=20000){BrowserManager.Tab tab=browser.main(()->browser.tab(j.tabId));JSONObject state=browser.main(()->browser.state(tab));browser.action(new JSONObject().put("tab_id",j.tabId).put("page_id",state.getString("page_id")).put("action","reload"),j.scope);j.lastReload=now;}
                }catch(Exception error){j.error=clean(error);save(j);}
                Thread.sleep(5000);
            }
            j.state=j.stop?"cancelled":"failed";j.error=j.stop?"cancelled":"live_media_not_found_before_deadline";j.finished=System.currentTimeMillis();save(j);emit(j,j.stop?"stream.cancelled":"stream.failed");
        }catch(InterruptedException stopped){Thread.currentThread().interrupt();j.state="cancelled";j.error="watch_interrupted";j.finished=System.currentTimeMillis();save(j);emit(j,"stream.cancelled");}
    }

    private void run(Job j,BrowserManager.MediaCandidate media){j.started=System.currentTimeMillis();j.state="recording";save(j);emit(j,"stream.started");try(FileOutputStream out=new FileOutputStream(j.file)){if("hls".equals(media.kind))recordHls(j,media,out);else recordContinuous(j,media,out);out.getFD().sync();if(j.bytes<1024)throw new IOException("stream_output_missing_or_empty");j.state="completed";j.finished=System.currentTimeMillis();if(j.publish){Uri published=publish(j.file,j.name,mime(j.name));j.mediaUri=published==null?"":published.toString();}save(j);emit(j,"stream.completed");}catch(Exception e){j.state=j.stop&&j.bytes>0?"completed":"failed";j.error=j.stop&&j.bytes>0?"":clean(e);j.finished=System.currentTimeMillis();try{if("completed".equals(j.state)&&j.publish){Uri published=publish(j.file,j.name,mime(j.name));j.mediaUri=published==null?"":published.toString();}}catch(Exception publish){j.error=clean(publish);}save(j);emit(j,"completed".equals(j.state)?"stream.completed":"stream.failed");}}
    private void recordContinuous(Job j,BrowserManager.MediaCandidate media,OutputStream out)throws Exception{HttpURLConnection c=open(media.url,media.headers);try{int code=c.getResponseCode();if(code<200||code>=300)throw new IOException("stream_HTTP_"+code);try(InputStream in=c.getInputStream()){copyUntil(j,in,out);}}finally{c.disconnect();}}
    private void recordHls(Job j,BrowserManager.MediaCandidate media,OutputStream out)throws Exception{URL playlist=new URL(media.url);Set<String> seen=new LinkedHashSet<>();boolean selectedVariant=false;while(active(j)){String text=readText(playlist.toString(),media.headers,2*1024*1024);String[] lines=text.replace("\r","").split("\n");if(!selectedVariant&&text.contains("#EXT-X-STREAM-INF")){URL best=null;long bandwidth=-1;for(int i=0;i<lines.length-1;i++)if(lines[i].startsWith("#EXT-X-STREAM-INF")){long b=parseBandwidth(lines[i]);int k=i+1;while(k<lines.length&&lines[k].trim().isEmpty())k++;if(k<lines.length&&!lines[k].startsWith("#")&&b>=bandwidth){best=new URL(playlist,lines[k].trim());bandwidth=b;}}if(best==null)throw new IOException("hls_variant_missing");playlist=best;selectedVariant=true;continue;}boolean any=false;for(String raw:lines){String line=raw.trim();if(line.isEmpty())continue;if(line.startsWith("#EXT-X-MAP:")){String uri=quoted(line,"URI");if(!uri.isEmpty()){URL u=new URL(playlist,uri);if(seen.add(u.toString())){downloadPart(j,u.toString(),media.headers,out);any=true;}}}else if(!line.startsWith("#")){URL u=new URL(playlist,line);if(seen.add(u.toString())){downloadPart(j,u.toString(),media.headers,out);any=true;}}if(!active(j))break;}if(text.contains("#EXT-X-ENDLIST"))break;if(!any)Thread.sleep(700);else Thread.sleep(250);}if(seen.isEmpty())throw new IOException("hls_segments_not_found");}
    private void downloadPart(Job j,String url,Map<String,String> headers,OutputStream out)throws Exception{HttpURLConnection c=open(url,headers);try{int code=c.getResponseCode();if(code<200||code>=300)throw new IOException("hls_segment_HTTP_"+code);try(InputStream in=c.getInputStream()){copyUntil(j,in,out);}}finally{c.disconnect();}}
    private boolean active(Job j){return !j.stop&&System.currentTimeMillis()-j.started<j.duration&&j.bytes<MAX_BYTES&&!Thread.currentThread().isInterrupted();}
    private void copyUntil(Job j,InputStream in,OutputStream out)throws Exception{byte[] b=new byte[32*1024];while(active(j)){int n;try{n=in.read(b);}catch(SocketTimeoutException timeout){continue;}if(n<0)break;int take=(int)Math.min(n,MAX_BYTES-j.bytes);if(take<=0)break;out.write(b,0,take);j.bytes+=take;if(j.bytes%(1024*1024)<take)save(j);}}
    private HttpURLConnection open(String value,Map<String,String> headers)throws Exception{URL u=HttpTools.checked(value);HttpURLConnection c=(HttpURLConnection)u.openConnection();c.setConnectTimeout(8000);c.setReadTimeout(8000);c.setInstanceFollowRedirects(true);c.setRequestProperty("Accept","*/*");c.setRequestProperty("Connection","keep-alive");if(headers!=null)for(Map.Entry<String,String> h:headers.entrySet())if(h.getKey().matches("(?i)User-Agent|Referer|Origin|Accept|Accept-Language"))c.setRequestProperty(h.getKey(),h.getValue());String cookie=CookieManager.getInstance().getCookie(value);if(cookie!=null&&!cookie.isEmpty())c.setRequestProperty("Cookie",cookie);return c;}
    private String readText(String url,Map<String,String> headers,int max)throws Exception{HttpURLConnection c=open(url,headers);try{int code=c.getResponseCode();if(code<200||code>=300)throw new IOException("playlist_HTTP_"+code);try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>max)throw new IOException("playlist_too_large");out.write(b,0,n);}return out.toString("UTF-8");}}finally{c.disconnect();}}
    private static long parseBandwidth(String line){try{java.util.regex.Matcher m=java.util.regex.Pattern.compile("BANDWIDTH=(\\d+)").matcher(line);return m.find()?Long.parseLong(m.group(1)):0;}catch(Exception e){return 0;}}
    private static String quoted(String line,String key){java.util.regex.Matcher m=java.util.regex.Pattern.compile(key+"=\"([^\"]+)\"").matcher(line);return m.find()?m.group(1):"";}
    private JSONObject snapshot(Job j)throws Exception{return new JSONObject().put("stream_id",j.id).put("task_id",j.owner).put("state",j.state).put("kind",j.kind).put("source",j.safeSource).put("output_name",j.name).put("artifact_path",j.file.getAbsolutePath()).put("requested_at",j.requested).put("start_at_epoch_ms",j.startNotBefore).put("deadline_epoch_ms",j.deadline).put("watching_for_live",j.watch).put("started_at",j.started).put("finished_at",j.finished).put("elapsed_ms",j.started==0?0:(j.finished>0?j.finished:System.currentTimeMillis())-j.started).put("duration_ms",j.duration).put("bytes",j.file.isFile()?j.file.length():j.bytes).put("sha256",j.finished>0&&j.file.isFile()?sha256(j.file):"").put("media_uri",j.mediaUri).put("error",j.error);}
    private void save(Job j){try{JSONObject stored=snapshot(j);if(j.watch)stored.put("_page_url",j.pageUrl).put("_requested_name",j.requestedName).put("_publish",j.publish);Workspace.write(new File(receipts,j.id+".json"),stored.toString().getBytes("UTF-8"));}catch(Exception ignored){}}
    private JSONObject read(String id)throws Exception{File f=new File(receipts,id+".json");if(!f.isFile())throw new IllegalArgumentException("stream_receipt_not_found");JSONObject out=new JSONObject(new String(Workspace.read(f,65536),"UTF-8"));out.remove("_page_url");out.remove("_requested_name");out.remove("_publish");return out;}
    private void emit(Job j,String type){try{events.emit(type,j.scope,snapshot(j));}catch(Exception ignored){}}
    private Uri publish(File source,String name,String mime)throws Exception{if(Build.VERSION.SDK_INT<29)return null;ContentResolver r=context.getContentResolver();ContentValues v=new ContentValues();v.put(MediaStore.Video.Media.DISPLAY_NAME,name);v.put(MediaStore.Video.Media.MIME_TYPE,mime);v.put(MediaStore.Video.Media.RELATIVE_PATH,"Movies/OPhoneAI");v.put(MediaStore.Video.Media.IS_PENDING,1);Uri uri=r.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,v);if(uri==null)throw new IOException("media_store_insert_failed");try(InputStream in=new FileInputStream(source);OutputStream out=r.openOutputStream(uri,"w")){if(out==null)throw new IOException("media_store_output_unavailable");byte[] b=new byte[32*1024];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}catch(Exception e){r.delete(uri,null,null);throw e;}ContentValues ready=new ContentValues();ready.put(MediaStore.Video.Media.IS_PENDING,0);r.update(uri,ready,null,null);return uri;}
    private static String safeUrl(String value){try{URL u=new URL(value);return u.getProtocol()+"://"+u.getHost()+u.getPath()+(u.getQuery()==null?"":"?[redacted]");}catch(Exception e){return "[invalid]";}}
    private static String safeName(String value,String ext){String n=value.replaceAll("[^A-Za-z0-9._-]","_").replaceFirst("^[._-]+","");if(n.isEmpty())n="OPhoneAI-live"+ext;if(n.length()>96)n=n.substring(0,96);if(!n.matches("(?i).*\\.(flv|ts|mp4|m4v)$"))n+=ext;return n;}
    private static String mime(String n){String s=n.toLowerCase(Locale.ROOT);return s.endsWith(".flv")?"video/x-flv":s.endsWith(".ts")?"video/mp2t":"video/mp4";}
    private static String clean(Throwable e){String s=e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage());return s.substring(0,Math.min(500,s.length()));}
    private static String sha256(File f)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(f)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)d.update(b,0,n);}StringBuilder s=new StringBuilder();for(byte x:d.digest())s.append(String.format(Locale.US,"%02x",x&255));return s.toString();}
}
