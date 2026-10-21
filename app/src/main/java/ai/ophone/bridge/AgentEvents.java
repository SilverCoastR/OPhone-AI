package ai.ophone.bridge;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Durable semantic observations and task-scoped subscriptions. Never calls TaskController under its lock. */
public final class AgentEvents extends SQLiteOpenHelper {
    private static AgentEvents instance;
    private final Context context;
    private final AtomicLong dropped=new AtomicLong();
    private final ThreadPoolExecutor worker;
    private final Map<String,LaunchWatch> launches=new HashMap<>();
    private String lastWindow="";private long lastWindowAt;
    public static synchronized AgentEvents get(Context c){if(instance==null)instance=new AgentEvents(c.getApplicationContext());return instance;}
    private AgentEvents(Context c){
        super(c,"agent_events.db",null,1);context=c;
        worker=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(128),r->new Thread(r,"OPhoneAI-events"),(r,e)->dropped.incrementAndGet());
    }
    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT,time_ms INTEGER NOT NULL,type TEXT NOT NULL,source TEXT NOT NULL,task_id TEXT NOT NULL,package TEXT NOT NULL,level TEXT NOT NULL,payload TEXT NOT NULL)");
        db.execSQL("CREATE INDEX events_task ON events(task_id,id)");
        db.execSQL("CREATE TABLE rules(id TEXT PRIMARY KEY,task_id TEXT NOT NULL,expires INTEGER NOT NULL,payload TEXT NOT NULL)");
        db.execSQL("CREATE TABLE deliveries(id TEXT PRIMARY KEY,task_id TEXT NOT NULL,rule_id TEXT NOT NULL,time_ms INTEGER NOT NULL,acked INTEGER NOT NULL,payload TEXT NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int a,int b){throw new IllegalStateException("unsupported_events_upgrade");}
    public static int rank(String level){if("debug".equals(level))return 0;if("info".equals(level))return 1;if("warn".equals(level)||"warning".equals(level))return 2;if("error".equals(level))return 3;if("fatal".equals(level))return 4;throw new IllegalArgumentException("invalid_event_level");}
    private void enqueue(Runnable r){worker.execute(()->{try{r.run();}catch(Exception ignored){dropped.incrementAndGet();}});}
    public void diagnostic(long id,String level,String source,String task,String code,String message){
        if(!java.util.Arrays.asList("warn","warning","error","fatal").contains(level))return;
        enqueue(()->{try{publish("diagnostic","diagnostic",new JSONObject().put("task_id",task==null?"":task),"",level,
                new JSONObject().put("diagnostic_id",id).put("diagnostic_source",source).put("code",code).put("message",DetailedDiagnostics.clean(message).substring(0,Math.min(500,DetailedDiagnostics.clean(message).length()))));}catch(Exception e){dropped.incrementAndGet();}});
    }
    public static final class LaunchWatch {
        final String token=UUID.randomUUID().toString(),pkg;final JSONObject scope;final CountDownLatch signal=new CountDownLatch(1);volatile JSONObject observation;
        LaunchWatch(String p,JSONObject s){pkg=p;scope=s;}
    }
    public synchronized LaunchWatch expectForeground(String pkg,JSONObject scope){LaunchWatch w=new LaunchWatch(pkg,scope);launches.put(w.token,w);return w;}
    public JSONObject awaitForeground(LaunchWatch w,long timeout) throws InterruptedException{
        try{w.signal.await(Math.max(0,Math.min(6000,timeout)),TimeUnit.MILLISECONDS);return w.observation;}
        finally{synchronized(this){launches.remove(w.token);}}
    }
    public void foreground(String pkg,String className,long androidEventTime){
        if(pkg.isEmpty()||!AppControlPolicy.isAllowed(context,pkg))return;
        enqueue(()->{try{recordForeground(pkg,className,androidEventTime);}catch(Exception e){dropped.incrementAndGet();}});
    }
    private synchronized void recordForeground(String pkg,String className,long androidEventTime) throws Exception{
        ArrayList<LaunchWatch> matches=new ArrayList<>();for(LaunchWatch w:launches.values())if(w.pkg.equals(pkg))matches.add(w);
        long now=System.currentTimeMillis();String key=pkg+"\n"+className;
        if(matches.isEmpty()&&(key.equals(lastWindow)&&now-lastWindowAt<1000||!hasWindowSubscribers()))return;
        lastWindow=key;lastWindowAt=now;
        JSONObject data=new JSONObject().put("package",pkg).put("class",className).put("android_event_time",androidEventTime)
                .put("checked_at",now).put("evidence_kind","accessibility_window_event_and_fresh_window_metadata").put("foreground_matches_target",true);
        if(matches.isEmpty())publish("app.foreground","accessibility",null,pkg,"info",data);
        for(LaunchWatch w:matches){JSONObject event=publish("app.foreground","accessibility",w.scope,pkg,"info",data);w.observation=event;w.signal.countDown();launches.remove(w.token);}
    }
    private boolean hasWindowSubscribers(){try(Cursor c=getReadableDatabase().rawQuery("SELECT id FROM rules WHERE expires>? LIMIT 1",new String[]{String.valueOf(System.currentTimeMillis())})){return c.moveToFirst();}}
    public synchronized JSONObject publish(String type,String source,JSONObject scope,String pkg,String level,JSONObject data) throws Exception{
        if(!pkg.isEmpty()&&!AppControlPolicy.isAllowed(context,pkg))return new JSONObject().put("suppressed",true);
        long now=System.currentTimeMillis();String task=scope==null?"":scope.optString("task_id","");
        JSONObject event=new JSONObject().put("time_ms",now).put("type",type).put("source",source).put("task_id",task)
                .put("call_id",scope==null?"":scope.optString("call_id","")).put("package",pkg).put("level",level).put("data",new JSONObject(data.toString()));
        ContentValues v=new ContentValues();v.put("time_ms",now);v.put("type",type);v.put("source",source);v.put("task_id",task);v.put("package",pkg);v.put("level",level);v.put("payload",event.toString());
        long id=getWritableDatabase().insertOrThrow("events",null,v);event.put("id",id);
        match(event);
        if(id%25==0){getWritableDatabase().execSQL("DELETE FROM events WHERE id < ? OR time_ms < ?",new Object[]{Math.max(0,id-1000),now-24*60*60*1000L});getWritableDatabase().delete("rules","expires<?",new String[]{String.valueOf(now)});getWritableDatabase().execSQL("DELETE FROM deliveries WHERE acked=1 AND time_ms<?",new Object[]{now-24*60*60*1000L});}
        return event;
    }
    public synchronized JSONObject subscribe(JSONObject task,JSONObject args) throws Exception{
        if(TaskController.terminal(task.getString("status")))throw new IllegalStateException("active_task_required_for_subscription");
        JSONArray types=args.getJSONArray("types");if(types.length()<1||types.length()>8)throw new IllegalArgumentException("one_to_eight_event_types_required");
        for(int i=0;i<types.length();i++)if(!java.util.Arrays.asList("app.foreground","diagnostic","operation.completed","operation.failed","package.added","package.removed","program.completed","program.failed","browser.loaded","browser.failed","terminal.completed","terminal.failed","terminal.report","ui.toast","http.completed","http.failed","recording.consent_required","recording.started","recording.completed","recording.failed","stream.started","stream.completed","stream.failed","phone.screen","phone.power","phone.network","phone.audio","phone.config","phone.memory","ophone.lifecycle").contains(types.getString(i)))throw new IllegalArgumentException("unsupported_event_type");
        String pkg=args.optString("package","");if(!pkg.isEmpty()&&(!pkg.matches("[A-Za-z0-9_.]{1,200}")||!AppControlPolicy.isAllowed(context,pkg)))throw new SecurityException("event_package_not_allowed");
        String source=args.optString("source","");if(!source.isEmpty()&&!java.util.Arrays.asList("accessibility","diagnostic","own_logcat","kernel","android_package","program","workbench","screen_record","phone").contains(source))throw new IllegalArgumentException("unsupported_event_source");
        String min=args.optString("min_level","info");rank(min);
        int count=args.optInt("min_count",1),window=args.optInt("window_ms",30000),cooldown=args.optInt("cooldown_ms",10000);
        if(count<1||count>20||window<1000||window>300000||cooldown<1000||cooldown>300000)throw new IllegalArgumentException("invalid_event_threshold_window_or_cooldown");
        String id=args.optString("subscription_id","sub-"+UUID.randomUUID());if(!id.matches("[A-Za-z0-9._-]{1,80}"))throw new IllegalArgumentException("invalid_subscription_id");
        try(Cursor c=getReadableDatabase().rawQuery("SELECT task_id FROM rules WHERE id=?",new String[]{id})){if(c.moveToFirst()&&!task.getString("id").equals(c.getString(0)))throw new SecurityException("subscription_task_mismatch");}
        try(Cursor c=getReadableDatabase().rawQuery("SELECT count(*) FROM rules WHERE task_id=? AND expires>? AND id<>?",new String[]{task.getString("id"),String.valueOf(System.currentTimeMillis()),id})){c.moveToFirst();if(c.getInt(0)>=8)throw new IllegalStateException("eight_subscriptions_per_task_limit");}
        String code=args.optString("code","");if(code.length()>100)throw new IllegalArgumentException("event_code_too_long");
        JSONObject rule=new JSONObject().put("id",id).put("task_id",task.getString("id")).put("types",types).put("package",pkg).put("source",source)
                .put("min_level",min).put("min_count",count).put("window_ms",window).put("cooldown_ms",cooldown).put("code",code)
                .put("expires_at",Math.min(task.getLong("deadline_at"),System.currentTimeMillis()+Math.max(1000,Math.min(1800000,args.optLong("duration_ms",1800000)))))
                .put("matched_times",new JSONArray()).put("last_fired_at",0);
        putRule(rule);return new JSONObject().put("ok",true).put("subscription",rule).put("delivery","Task-scoped push with explicit event_ack; informational events do not require fault diagnosis.");
    }
    private void putRule(JSONObject rule)throws Exception{ContentValues v=new ContentValues();v.put("id",rule.getString("id"));v.put("task_id",rule.getString("task_id"));v.put("expires",rule.getLong("expires_at"));v.put("payload",rule.toString());if(getWritableDatabase().insertWithOnConflict("rules",null,v,SQLiteDatabase.CONFLICT_REPLACE)<0)throw new IllegalStateException("event_subscription_not_saved");}
    private void match(JSONObject event)throws Exception{
        ArrayList<JSONObject> rules=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT payload FROM rules WHERE expires>?",new String[]{String.valueOf(event.getLong("time_ms"))})){while(c.moveToNext())rules.add(new JSONObject(c.getString(0)));}
        for(JSONObject r:rules){
            if(!event.optString("task_id").isEmpty()&&!event.getString("task_id").equals(r.getString("task_id")))continue;
            if(!r.optString("package").isEmpty()&&!r.getString("package").equals(event.optString("package")))continue;
            if(!r.optString("source").isEmpty()&&!r.getString("source").equals(event.getString("source")))continue;
            if(!event.getJSONObject("data").optBoolean("source_unavailable")&&!r.optString("code").isEmpty()&&!r.getString("code").equals(event.getJSONObject("data").optString("code")))continue;
            boolean type=false;for(int i=0;i<r.getJSONArray("types").length();i++)if(event.getString("type").equals(r.getJSONArray("types").getString(i)))type=true;
            if(!type||rank(event.getString("level"))<rank(r.getString("min_level")))continue;
            long now=event.getLong("time_ms");JSONArray times=new JSONArray(),old=r.getJSONArray("matched_times");
            for(int i=Math.max(0,old.length()-19);i<old.length();i++)if(old.getLong(i)>=now-r.getInt("window_ms"))times.put(old.getLong(i));times.put(now);r.put("matched_times",times);
            if((event.getJSONObject("data").optBoolean("source_unavailable")||times.length()>=r.getInt("min_count"))&&now-r.optLong("last_fired_at",0)>=r.getInt("cooldown_ms")){
                JSONObject notice=null;try(Cursor c=getReadableDatabase().rawQuery("SELECT payload FROM deliveries WHERE rule_id=? AND acked=0 LIMIT 1",new String[]{r.getString("id")})){if(c.moveToFirst())notice=new JSONObject(c.getString(0));}
                if(notice==null)notice=new JSONObject().put("id","notice-"+UUID.randomUUID()).put("task_id",r.getString("task_id")).put("subscription_id",r.getString("id")).put("first_event_id",event.getLong("id")).put("count",0);
                notice.put("latest_event_id",event.getLong("id")).put("count",notice.optInt("count")+times.length()).put("event",event)
                        .put("requires_diagnosis",rank(event.getString("level"))>=3).put("required_checks",rank(event.getString("level"))>=3?new JSONArray("own_logcat".equals(event.optString("source"))?"[\"event_query\",\"read_app_logcat\"]":"[\"event_query\",\"diagnostic_query\"]"):new JSONArray());
                ContentValues v=new ContentValues();v.put("id",notice.getString("id"));v.put("task_id",r.getString("task_id"));v.put("rule_id",r.getString("id"));v.put("time_ms",now);v.put("acked",0);v.put("payload",notice.toString());
                if(getWritableDatabase().insertWithOnConflict("deliveries",null,v,SQLiteDatabase.CONFLICT_REPLACE)<0)throw new IllegalStateException("event_delivery_not_saved");
                r.put("last_fired_at",now).put("matched_times",new JSONArray());
            }
            putRule(r);
        }
    }
    public synchronized JSONArray pending(String task)throws Exception{
        JSONArray a=new JSONArray();try(Cursor c=getReadableDatabase().rawQuery("SELECT payload FROM deliveries WHERE task_id=? AND acked=0 ORDER BY time_ms LIMIT 8",new String[]{task})){while(c.moveToNext())a.put(new JSONObject(c.getString(0)));}return a;
    }
    public synchronized JSONObject ack(String task,String id,long through)throws Exception{
        try(Cursor c=getReadableDatabase().rawQuery("SELECT payload FROM deliveries WHERE id=? AND task_id=?",new String[]{id,task})){
            if(c.moveToFirst()&&new JSONObject(c.getString(0)).getLong("latest_event_id")>through)return new JSONObject().put("ok",false).put("error","delivery_advanced_read_events_before_ack");
        }
        ContentValues v=new ContentValues();v.put("acked",1);int changed=getWritableDatabase().update("deliveries",v,"id=? AND task_id=?",new String[]{id,task});
        return new JSONObject().put("ok",changed>0).put("acknowledged",changed>0).put("delivery_id",id);
    }
    public synchronized JSONObject unsubscribe(String task,String id)throws Exception{
        int n=getWritableDatabase().delete("rules","id=? AND task_id=?",new String[]{id,task});return new JSONObject().put("ok",true).put("removed",n>0).put("pending_deliveries",pending(task));
    }
    public synchronized void closeTask(String task){getWritableDatabase().delete("rules","task_id=?",new String[]{task});ContentValues v=new ContentValues();v.put("acked",1);getWritableDatabase().update("deliveries",v,"task_id=?",new String[]{task});}
    public synchronized boolean logcatRequested(){try(Cursor c=getReadableDatabase().rawQuery("SELECT payload FROM rules WHERE expires>?",new String[]{String.valueOf(System.currentTimeMillis())})){while(c.moveToNext())if("own_logcat".equals(new JSONObject(c.getString(0)).optString("source")))return true;}catch(Exception ignored){}return false;}
    public synchronized JSONObject query(String task,JSONObject args)throws Exception{
        long after=Math.max(0,args.optLong("after_id",0));int limit=Math.max(1,Math.min(80,args.optInt("limit",20)));String type=args.optString("type","");
        String where="id>? AND (task_id=? OR task_id='')";ArrayList<String> values=new ArrayList<>();values.add(String.valueOf(after));values.add(task);
        if(!type.isEmpty()){where+=" AND type=?";values.add(type);}
        JSONArray a=new JSONArray();long next=after,min=0;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT min(id) FROM events",null)){if(c.moveToFirst())min=c.getLong(0);}
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,payload,package FROM events WHERE "+where+" ORDER BY id LIMIT "+limit,values.toArray(new String[0]))){while(c.moveToNext()){
            next=c.getLong(0);if(!c.getString(2).isEmpty()&&!AppControlPolicy.isAllowed(context,c.getString(2)))continue;
            a.put(new JSONObject(c.getString(1)).put("id",next));}}
        JSONArray subscriptions=new JSONArray();try(Cursor c=getReadableDatabase().rawQuery("SELECT payload FROM rules WHERE task_id=? AND expires>?",new String[]{task,String.valueOf(System.currentTimeMillis())})){while(c.moveToNext())subscriptions.put(new JSONObject(c.getString(0)));}
        return new JSONObject().put("ok",true).put("events",a).put("next_after_id",next).put("cursor_gap",after>0&&min>after+1).put("dropped_enqueue_count",dropped.get()).put("pending_deliveries",pending(task)).put("subscriptions",subscriptions);
    }
    /** Toast text is published only while some task listens for ui.toast, to keep the event log lean. */
    public void toast(String pkg,JSONObject toast){if(!AppControlPolicy.isAllowed(context,pkg))return;enqueue(()->{try{if(hasWindowSubscribers())publish("ui.toast","accessibility",null,pkg,"info",toast);}catch(Exception e){dropped.incrementAndGet();}});}
    /** Phone and OPhone AI state changes (PhoneEvents); always recorded, they are few and a running task needs them. */
    public void phone(String type,String level,JSONObject data){enqueue(()->{try{publish(type,"phone",null,"",level,data);}catch(Exception e){dropped.incrementAndGet();}});}

    private static final Map<String,Long> phoneCursor=new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String,Long> noticeSeen=new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * Added to every tool result of a running task, on both the API and the ChatGPT web path: phone and app state
     * changes since the previous step (informational, nothing to acknowledge) and new notifications from the task's
     * own subscriptions (acknowledged with event_ack).
     */
    public void attach(JSONObject value,String task){if(value==null||task==null||task.isEmpty())return;try{
        JSONArray phone=new JSONArray();long cursor;
        synchronized(this){Long known=phoneCursor.get(task);
            if(known==null){known=0L;try(Cursor c=getReadableDatabase().rawQuery("SELECT max(id) FROM events WHERE time_ms<?",new String[]{String.valueOf(System.currentTimeMillis()-60000)})){if(c.moveToFirst())known=c.getLong(0);}}
            cursor=known;
            try(Cursor c=getReadableDatabase().rawQuery("SELECT id,payload FROM events WHERE id>? AND source='phone' ORDER BY id LIMIT 12",new String[]{String.valueOf(known)})){
                while(c.moveToNext()){cursor=c.getLong(0);JSONObject e=new JSONObject(c.getString(1));JSONObject d=e.optJSONObject("data");
                    phone.put(new JSONObject().put("type",e.optString("type")).put("level",e.optString("level")).put("at",e.optLong("time_ms")).put("event",d==null?new JSONObject():d));}}
            phoneCursor.put(task,cursor);if(phoneCursor.size()>64)phoneCursor.clear();}
        if(phone.length()>0)value.put("phone_events",phone).put("phone_events_hint","Phone or OPhone AI state changed since your last step. Informational, no event_ack needed; take it into account (for example re-read the screen after a rotation, wait for the user to unlock, or retry after the network returns).");
        JSONArray pending=pending(task),fresh=new JSONArray();
        for(int i=0;i<pending.length()&&fresh.length()<3;i++){JSONObject n=pending.getJSONObject(i);long version=n.optLong("latest_event_id");String key=task+"\n"+n.optString("id");Long seen=noticeSeen.get(key);if(seen!=null&&seen>=version)continue;noticeSeen.put(key,version);
            JSONObject e=n.optJSONObject("event")==null?new JSONObject():n.getJSONObject("event");String data=e.optJSONObject("data")==null?"":e.getJSONObject("data").toString();
            fresh.put(new JSONObject().put("delivery_id",n.optString("id")).put("type",e.optString("type")).put("level",e.optString("level")).put("count",n.optInt("count")).put("latest_event_id",version).put("summary",data.length()>300?data.substring(0,300)+"...":data));}
        if(noticeSeen.size()>256)noticeSeen.clear();
        if(fresh.length()>0)value.put("pending_events",fresh).put("pending_events_hint","New task events arrived; handle them when relevant, read details with event_query and acknowledge with event_ack.");
    }catch(Exception ignored){}}
    public void packageChanged(String pkg,boolean added){if(AppControlPolicy.isAllowed(context,pkg))enqueue(()->{try{publish(added?"package.added":"package.removed","android_package",null,pkg,"info",new JSONObject().put("package",pkg));}catch(Exception e){dropped.incrementAndGet();}});}
}
