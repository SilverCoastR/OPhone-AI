package ai.ophone.bridge;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Iterator;
import java.util.UUID;

/**
 * Provider-neutral local API conversation history. Every message is kept on the device (SQLite) so the chat page
 * can always show the whole conversation; only the window sent to a model is bounded (see contextMessages).
 * Credentials never enter this store.
 */
public final class ApiConversationStore {
    private static final String PREFS="api_conversations_v2",LEGACY="direct_api_chat_v1";
    // Model context window, not storage limits: older turns stay visible locally but are not resent.
    private static final int CONTEXT_MESSAGES=80,CONTEXT_BYTES=384*1024,MAX_CONVERSATIONS=500;
    private final SharedPreferences prefs;private final Context context;private final String legacyName;private final Db db;

    ApiConversationStore(Context context){this(context.getApplicationContext(),PREFS,LEGACY);reconcileNativeTasks();}
    ApiConversationStore(Context context,String prefsName,String legacyName){this.context=context;this.legacyName=legacyName;prefs=context.getSharedPreferences(prefsName,Context.MODE_PRIVATE);db=Db.get(context,prefsName+".db");migrate();importPrefs();}

    private static final class Db extends SQLiteOpenHelper {
        private static final java.util.Map<String,Db> OPEN=new java.util.HashMap<>();
        static synchronized Db get(Context c,String name){Db d=OPEN.get(name);if(d==null){d=new Db(c.getApplicationContext(),name);OPEN.put(name,d);}return d;}
        private Db(Context c,String name){super(c,name,null,1);}
        @Override public void onCreate(SQLiteDatabase d){
            d.execSQL("CREATE TABLE conversations(id TEXT PRIMARY KEY,title TEXT,created_at INTEGER,updated_at INTEGER,last_provider TEXT,message_count INTEGER,meta TEXT)");
            d.execSQL("CREATE TABLE messages(seq INTEGER PRIMARY KEY AUTOINCREMENT,conversation TEXT NOT NULL,id TEXT NOT NULL,body TEXT NOT NULL)");
            d.execSQL("CREATE INDEX messages_by_conversation ON messages(conversation,seq)");
            d.execSQL("CREATE TABLE kv(key TEXT PRIMARY KEY,value TEXT)");
        }
        @Override public void onUpgrade(SQLiteDatabase d,int from,int to){}
    }

    synchronized String conversationId(){String id=kv("active_id");if(id.isEmpty()||find(id)<0)id=create("deepseek");return id;}
    synchronized JSONArray messages(){return messages(conversationId());}
    /** The whole conversation in order; every message carries a stable "id". */
    synchronized JSONArray messages(String id){JSONArray out=new JSONArray();
        try(Cursor c=db.getReadableDatabase().rawQuery("SELECT id,body FROM messages WHERE conversation=? ORDER BY seq",new String[]{id})){
            while(c.moveToNext())try{out.put(new JSONObject(c.getString(1)).put("id",c.getString(0)));}catch(Exception ignored){}}
        return out;}
    /** The list the user sees: every conversation with content, plus the open one even while it is still empty. */
    synchronized JSONArray conversations(){JSONArray out=new JSONArray();
        try(Cursor c=db.getReadableDatabase().rawQuery("SELECT id,title,created_at,updated_at,last_provider,message_count FROM conversations WHERE message_count>0 OR id=? ORDER BY updated_at DESC",new String[]{kv("active_id")})){
            while(c.moveToNext())try{out.put(new JSONObject().put("id",c.getString(0)).put("title",c.getString(1)).put("created_at",c.getLong(2)).put("updated_at",c.getLong(3)).put("last_provider",c.getString(4)).put("message_count",c.getInt(5)));}catch(Exception ignored){}}
        return out;}

    /**
     * What a model receives: progress lines removed, older tool output compacted, and the oldest whole turns
     * dropped until the window fits. The stored conversation is never changed by this.
     */
    synchronized JSONArray contextMessages(String id)throws Exception{JSONArray all=messages(id),kept=new JSONArray();
        for(int i=0;i<all.length();i++){JSONObject m=all.getJSONObject(i);if(!"progress".equals(m.optString("role")))kept.put(m);}
        return window(kept);}

    /**
     * A new chat. An existing empty conversation is reused instead of adding another one, and other empty ones are
     * removed, so the list never fills with blank "New chat" entries that look like lost history.
     */
    synchronized String create(String provider){try{
        String safe=provider==null||!provider.matches("[A-Za-z0-9_-]{1,30}")?"api":provider.toLowerCase();long now=System.currentTimeMillis();
        String reuse="";try(Cursor c=db.getReadableDatabase().rawQuery("SELECT id FROM conversations WHERE message_count=0 AND NOT EXISTS(SELECT 1 FROM messages WHERE conversation=conversations.id) ORDER BY updated_at DESC",null)){
            java.util.List<String> empty=new java.util.ArrayList<>();while(c.moveToNext())empty.add(c.getString(0));
            if(!empty.isEmpty()){reuse=empty.get(0);SQLiteDatabase d=db.getWritableDatabase();d.beginTransaction();
                try{for(int i=1;i<empty.size();i++)remove(d,empty.get(i));ContentValues v=new ContentValues();v.put("updated_at",now);v.put("last_provider",safe);v.put("title",context.getString(R.string.conversation_new_title));
                    d.update("conversations",v,"id=?",new String[]{reuse});setKv(d,"active_id",reuse);d.setTransactionSuccessful();}finally{d.endTransaction();}}}
        if(!reuse.isEmpty()){logActive(reuse,"new_chat_reused_empty");return reuse;}
        String id="c:api."+safe+"."+UUID.randomUUID().toString().replace("-","");
        ContentValues v=new ContentValues();v.put("id",id);v.put("title",context.getString(R.string.conversation_new_title));v.put("created_at",now);v.put("updated_at",now);v.put("last_provider",safe);v.put("message_count",0);v.put("meta","{}");
        SQLiteDatabase d=db.getWritableDatabase();d.beginTransaction();
        try{d.insertOrThrow("conversations",null,v);setKv(d,"active_id",id);
            // Bound the number of conversations; the oldest ones go first.
            try(Cursor c=d.rawQuery("SELECT id FROM conversations ORDER BY updated_at DESC LIMIT -1 OFFSET "+MAX_CONVERSATIONS,null)){while(c.moveToNext())remove(d,c.getString(0));}
            d.setTransactionSuccessful();}finally{d.endTransaction();}
        logActive(id,"new_chat");
        return id;
    }catch(Exception e){throw new IllegalStateException(e);}}

    synchronized void select(String id){if(find(id)<0)throw new IllegalArgumentException("conversation_not_found");if(!id.equals(kv("active_id")))logActive(id,"select");setKv(db.getWritableDatabase(),"active_id",id);}

    /**
     * Entering an API platform: when the open conversation is empty but another one has newer content, that one is
     * opened instead, so returning to the platform shows the latest history rather than a blank chat.
     */
    synchronized String resumeLatest(){String active=kv("active_id");
        try(Cursor c=db.getReadableDatabase().rawQuery("SELECT message_count,updated_at FROM conversations WHERE id=?",new String[]{active})){
            if(c.moveToFirst()&&c.getInt(0)>0)return active;long since=c.moveToFirst()?c.getLong(1):0;
            try(Cursor n=db.getReadableDatabase().rawQuery("SELECT id FROM conversations WHERE message_count>0 AND updated_at>=? ORDER BY updated_at DESC LIMIT 1",new String[]{String.valueOf(since)})){
                if(n.moveToFirst()){String latest=n.getString(0);logActive(latest,"resume_latest_instead_of_empty");setKv(db.getWritableDatabase(),"active_id",latest);return latest;}}
        }catch(Exception ignored){}
        return conversationId();}

    /** Which conversation became the open one and why (with the caller), for feedback logs. Never logs content. */
    private void logActive(String id,String reason){StackTraceElement[] at=new Throwable().getStackTrace();String caller="";
        for(StackTraceElement e:at)if(!e.getClassName().endsWith("ApiConversationStore")){caller=e.getClassName().replaceFirst("^ai\\.ophone\\.bridge\\.","")+"."+e.getMethodName();break;}
        DetailedDiagnostics.event(context,"info","conversation","","","api_conversation_opened",reason+": "+id.substring(0,Math.min(id.length(),28))+" by "+caller,null);}
    synchronized void setProvider(String provider){try{touch(conversationId(),null,provider);}catch(Exception ignored){}}
    synchronized void append(JSONObject message) throws Exception {append(conversationId(),message);}
    synchronized void append(String id,JSONObject message) throws Exception {
        if(find(id)<0)throw new IllegalArgumentException("conversation_not_found");JSONObject copy=new JSONObject(message.toString());
        String messageId=copy.optString("id");if(messageId.isEmpty())messageId="m-"+UUID.randomUUID().toString().replace("-","");copy.remove("id");
        ContentValues v=new ContentValues();v.put("conversation",id);v.put("id",messageId);v.put("body",copy.toString());db.getWritableDatabase().insertOrThrow("messages",null,v);
        String title=null;if("user".equals(copy.optString("role"))&&!copy.optBoolean("internal")&&!copy.optBoolean("guidance")){String value=copy.optString("content").replaceAll("\\s+"," ").trim();if(!value.isEmpty())title=value.substring(0,Math.min(32,value.length()));}
        touch(id,title,copy.optString("provider",""));
    }
    synchronized void replaceLastAssistant(JSONObject message) throws Exception {replaceLastAssistant(conversationId(),message);}
    synchronized void replaceLastAssistant(String id,JSONObject message) throws Exception {
        try(Cursor c=db.getReadableDatabase().rawQuery("SELECT seq,body FROM messages WHERE conversation=? ORDER BY seq DESC LIMIT 1",new String[]{id})){
            if(c.moveToFirst()&&"assistant".equals(new JSONObject(c.getString(1)).optString("role")))db.getWritableDatabase().delete("messages","seq=?",new String[]{String.valueOf(c.getLong(0))});}
        append(id,message);
    }

    /**
     * Deletes a message from the local record and from every future model context. A human turn takes its whole
     * reply with it (answers, tool calls and results up to the next human turn), so no orphaned tool exchange remains.
     */
    synchronized int deleteMessage(String conversation,String messageId)throws Exception{JSONArray all=messages(conversation);int at=indexOf(all,messageId);if(at<0)throw new IllegalArgumentException("message_not_found");
        JSONObject m=all.getJSONObject(at);java.util.List<String> ids=new java.util.ArrayList<>();ids.add(messageId);
        if(humanTurn(m)){for(int i=at+1;i<all.length()&&!humanTurn(all.getJSONObject(i));i++)ids.add(all.getJSONObject(i).getString("id"));}
        else if(!"assistant".equals(m.optString("role"))||(m.optJSONArray("tool_calls")!=null&&m.optJSONArray("tool_calls").length()>0))throw new IllegalArgumentException("only_messages_and_answers_can_be_deleted");
        deleteIds(conversation,ids);return ids.size();}
    /** Edit = drop this human message and everything after it; the caller then sends the new text. */
    synchronized int truncateFrom(String conversation,String messageId)throws Exception{JSONArray all=messages(conversation);int at=indexOf(all,messageId);if(at<0)throw new IllegalArgumentException("message_not_found");
        if(!humanTurn(all.getJSONObject(at)))throw new IllegalArgumentException("only_your_messages_can_be_edited");
        java.util.List<String> ids=new java.util.ArrayList<>();for(int i=at;i<all.length();i++)ids.add(all.getJSONObject(i).getString("id"));deleteIds(conversation,ids);return ids.size();}

    /** Per-conversation settings that are not messages, e.g. which providers the user allowed to receive this history. */
    synchronized JSONObject meta(String conversation){try(Cursor c=db.getReadableDatabase().rawQuery("SELECT meta FROM conversations WHERE id=?",new String[]{conversation})){return c.moveToFirst()?new JSONObject(c.getString(0)==null?"{}":c.getString(0)):new JSONObject();}catch(Exception e){return new JSONObject();}}
    synchronized void putMeta(String conversation,String key,Object value)throws Exception{JSONObject meta=meta(conversation).put(key,value);ContentValues v=new ContentValues();v.put("meta",meta.toString());db.getWritableDatabase().update("conversations",v,"id=?",new String[]{conversation});}

    synchronized String delete(String id){
        if(find(id)<0)throw new IllegalArgumentException("conversation_not_found");String active=kv("active_id");SQLiteDatabase d=db.getWritableDatabase();
        d.beginTransaction();try{remove(d,id);if(id.equals(active))d.delete("kv","key=?",new String[]{"active_id"});d.setTransactionSuccessful();}finally{d.endTransaction();}
        JSONArray next=conversations();if(next.length()==0)return create("deepseek");String selected=id.equals(active)?next.optJSONObject(0).optString("id"):active;if(selected.isEmpty()||find(selected)<0)selected=next.optJSONObject(0).optString("id");select(selected);return selected;
    }
    /** Backward-compatible name: starting fresh preserves every previous conversation. */
    synchronized void clear(){create("deepseek");}

    static boolean humanTurn(JSONObject m){return "user".equals(m.optString("role"))&&!m.optBoolean("internal")&&!m.optBoolean("guidance");}
    private static int indexOf(JSONArray all,String id){for(int i=0;i<all.length();i++)if(id.equals(all.optJSONObject(i)==null?"":all.optJSONObject(i).optString("id")))return i;return -1;}
    private void deleteIds(String conversation,java.util.List<String> ids)throws Exception{SQLiteDatabase d=db.getWritableDatabase();d.beginTransaction();try{for(String id:ids)d.delete("messages","conversation=? AND id=?",new String[]{conversation,id});d.setTransactionSuccessful();}finally{d.endTransaction();}touch(conversation,null,null);}
    private static void remove(SQLiteDatabase d,String id){d.delete("messages","conversation=?",new String[]{id});d.delete("conversations","id=?",new String[]{id});}
    private int find(String id){try(Cursor c=db.getReadableDatabase().rawQuery("SELECT 1 FROM conversations WHERE id=?",new String[]{id})){return c.moveToFirst()?0:-1;}}
    private String kv(String key){try(Cursor c=db.getReadableDatabase().rawQuery("SELECT value FROM kv WHERE key=?",new String[]{key})){return c.moveToFirst()&&c.getString(0)!=null?c.getString(0):"";}}
    private static void setKv(SQLiteDatabase d,String key,String value){ContentValues v=new ContentValues();v.put("key",key);v.put("value",value);d.insertWithOnConflict("kv",null,v,SQLiteDatabase.CONFLICT_REPLACE);}
    private void touch(String id,String title,String provider)throws Exception{SQLiteDatabase d=db.getWritableDatabase();ContentValues v=new ContentValues();v.put("updated_at",System.currentTimeMillis());
        try(Cursor c=d.rawQuery("SELECT COUNT(*) FROM messages WHERE conversation=?",new String[]{id})){if(c.moveToFirst())v.put("message_count",c.getInt(0));}
        if(title!=null){try(Cursor c=d.rawQuery("SELECT title FROM conversations WHERE id=?",new String[]{id})){if(c.moveToFirst()&&defaultTitle(c.getString(0)))v.put("title",title);}}
        if(provider!=null&&!provider.isEmpty()&&!"native_task".equals(provider))v.put("last_provider",provider);
        d.update("conversations",v,"id=?",new String[]{id});}
    private boolean defaultTitle(String value){return value==null||value.isEmpty()||value.equals(context.getString(R.string.conversation_new_title))||"新对话".equals(value)||"New conversation".equals(value);}

    /** One-time move of the oldest single-chat format into the v2 preferences format, which importPrefs then loads. */
    private void migrate(){if(prefs.getBoolean("legacy_migrated",false))return;try{
        SharedPreferences legacy=context.getSharedPreferences(legacyName,Context.MODE_PRIVATE);String id=legacy.getString("conversation","");JSONArray all=new JSONArray(legacy.getString("messages","[]"));
        if(id.isEmpty())id="c:api.deepseek."+UUID.randomUUID().toString().replace("-","");String title=context.getString(R.string.conversation_history_title),untitled=title;
        for(int i=0;i<all.length();i++){JSONObject m=all.optJSONObject(i);if(m==null)continue;if("assistant".equals(m.optString("role"))&&DirectModelAgent.looksLikeProtocol(m.optString("content")))m.put("internal",true).put("hidden_reason","legacy_protocol_markup");if("user".equals(m.optString("role"))&&!m.optBoolean("internal")&&untitled.equals(title)){String value=m.optString("content").replaceAll("\\s+"," ").trim();if(!value.isEmpty())title=value.substring(0,Math.min(32,value.length()));}}
        long now=System.currentTimeMillis();JSONArray index=new JSONArray().put(new JSONObject().put("id",id).put("title",title).put("created_at",now).put("updated_at",now).put("last_provider","deepseek").put("message_count",all.length()));
        prefs.edit().putBoolean("legacy_migrated",true).putString("active_id",id).putString("index",index.toString()).putString("messages:"+id,all.toString()).commit();
    }catch(Exception ignored){prefs.edit().putBoolean("legacy_migrated",true).commit();}}

    /** One-time import of the v2 preferences store (index + per-conversation JSON) into SQLite. */
    private synchronized void importPrefs(){SQLiteDatabase d=db.getWritableDatabase();if(!kvIn(d,"prefs_imported").isEmpty())return;d.beginTransaction();try{
        JSONArray index=new JSONArray(prefs.getString("index","[]"));
        for(int i=index.length()-1;i>=0;i--){JSONObject item=index.optJSONObject(i);if(item==null)continue;String id=item.optString("id");if(id.isEmpty())continue;
            ContentValues v=new ContentValues();v.put("id",id);v.put("title",item.optString("title"));v.put("created_at",item.optLong("created_at"));v.put("updated_at",item.optLong("updated_at"));v.put("last_provider",item.optString("last_provider","deepseek"));v.put("meta","{}");
            JSONArray all=new JSONArray(prefs.getString("messages:"+id,"[]"));v.put("message_count",all.length());d.insertWithOnConflict("conversations",null,v,SQLiteDatabase.CONFLICT_IGNORE);
            for(int j=0;j<all.length();j++){JSONObject m=all.optJSONObject(j);if(m==null)continue;ContentValues mv=new ContentValues();mv.put("conversation",id);mv.put("id","m-"+UUID.randomUUID().toString().replace("-",""));mv.put("body",m.toString());d.insert("messages",null,mv);}}
        String active=prefs.getString("active_id","");if(!active.isEmpty())setKv(d,"active_id",active);
        setKv(d,"prefs_imported","1");d.setTransactionSuccessful();
    }catch(Exception e){DetailedDiagnostics.event(context,"error","conversation","","","conversation_import_failed","Unable to import stored API conversations.",e);}finally{d.endTransaction();}}
    private static String kvIn(SQLiteDatabase d,String key){try(Cursor c=d.rawQuery("SELECT value FROM kv WHERE key=?",new String[]{key})){return c.moveToFirst()&&c.getString(0)!=null?c.getString(0):"";}}

    private synchronized void reconcileNativeTasks(){try{JSONArray index=conversations();for(int i=0;i<index.length()&&i<30;i++){String id=index.getJSONObject(i).optString("id");JSONArray existing=messages(id),tasks=AgentStore.get(context).tasks(id,false);for(int j=Math.min(tasks.length(),10)-1;j>=0;j--){JSONObject task=tasks.getJSONObject(j);String status=task.optString("status");if(!java.util.Arrays.asList("succeeded","waiting_event").contains(status)||containsTask(existing,task.optString("id")))continue;String summary=task.optString("summary").trim(),content=context.getString("succeeded".equals(status)?R.string.native_task_receipt_success:R.string.native_task_waiting_event,task.optString("goal"));if(!summary.isEmpty())content+="\n"+summary;JSONObject update=new JSONObject().put("role","progress").put("content",content).put("provider","native_task").put("created_at",System.currentTimeMillis()).put("task_id",task.optString("id")).put("task_status",status);append(id,update);existing=messages(id);}}}catch(Exception ignored){}}
    private static boolean containsTask(JSONArray messages,String taskId){if(taskId==null||taskId.isEmpty())return false;for(int i=0;i<messages.length();i++){JSONObject message=messages.optJSONObject(i);if(message!=null&&taskId.equals(message.optString("task_id")))return true;}return false;}

    /** Bounds a model context: compacts older tool output first, then drops the oldest whole human turns. */
    static JSONArray window(JSONArray source) throws Exception {
        JSONArray all=new JSONArray();for(int i=0;i<source.length();i++)all.put(new JSONObject(source.getJSONObject(i).toString()));
        while(all.length()>CONTEXT_MESSAGES||all.toString().getBytes("UTF-8").length>CONTEXT_BYTES){boolean compacted=false;for(int i=0;i<all.length()-1;i++){JSONObject m=all.optJSONObject(i);if(m!=null&&"tool".equals(m.optString("role"))&&m.opt("content")!=null&&String.valueOf(m.opt("content")).length()>512){m.put("content","{\"ok\":true,\"summary\":\"Older tool output was processed and compacted; inspect current state again if needed.\"}");compacted=true;break;}}if(compacted)continue;if(all.length()<=1)break;int drop=1;for(int i=1;i<all.length();i++){JSONObject m=all.optJSONObject(i);if(m!=null&&humanTurn(m)){drop=i;break;}}JSONArray next=new JSONArray();for(int i=drop;i<all.length();i++)next.put(all.get(i));all=next;}
        return all;
    }
}
