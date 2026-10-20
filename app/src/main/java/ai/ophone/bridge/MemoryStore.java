package ai.ophone.bridge;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Agent long-term memory: user preferences, per-app playbooks and failure notes, kept on the phone and visible to the user. */
public final class MemoryStore extends SQLiteOpenHelper {
    static final List<String> KINDS=Arrays.asList("preference","fact","playbook","failure");
    private static final int MAX_ITEMS=500;
    private static MemoryStore instance;
    public static synchronized MemoryStore get(Context c){if(instance==null)instance=new MemoryStore(c.getApplicationContext());return instance;}
    private MemoryStore(Context c){super(c,"agent_memory.db",null,1);}
    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE memories(id TEXT PRIMARY KEY,kind TEXT NOT NULL,app TEXT NOT NULL,title TEXT NOT NULL,body TEXT NOT NULL,tags TEXT NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,uses INTEGER NOT NULL,last_used_at INTEGER NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int a,int b){throw new IllegalStateException("unsupported_memory_upgrade");}

    private static JSONObject row(Cursor c)throws Exception{
        return new JSONObject().put("id",c.getString(0)).put("kind",c.getString(1)).put("app",c.getString(2)).put("title",c.getString(3)).put("text",c.getString(4))
                .put("tags",new JSONArray(c.getString(5))).put("created_at",c.getLong(6)).put("updated_at",c.getLong(7)).put("uses",c.getInt(8));
    }
    private static final String COLUMNS="id,kind,app,title,body,tags,created_at,updated_at,uses,last_used_at";

    public synchronized JSONObject save(JSONObject a)throws Exception{
        String kind=a.optString("kind","fact");if(!KINDS.contains(kind))throw new IllegalArgumentException("kind_must_be_preference_fact_playbook_or_failure");
        String title=a.optString("title").trim(),body=a.optString("text").trim(),app=a.optString("app","").trim();
        if(title.isEmpty()||title.length()>120)throw new IllegalArgumentException("title_required_max_120");
        if(body.isEmpty()||body.length()>4000)throw new IllegalArgumentException("text_required_max_4000");
        if(app.length()>200)throw new IllegalArgumentException("app_too_long");
        JSONArray tags=new JSONArray(),raw=a.optJSONArray("tags");for(int i=0;raw!=null&&i<raw.length()&&tags.length()<12;i++){String t=raw.optString(i).trim();if(!t.isEmpty()&&t.length()<=40)tags.put(t);}
        long now=System.currentTimeMillis();String id=a.optString("id","");boolean update=!id.isEmpty();
        if(update){try(Cursor c=getReadableDatabase().rawQuery("SELECT id FROM memories WHERE id=?",new String[]{id})){if(!c.moveToFirst())throw new IllegalArgumentException("memory_not_found");}}
        else id="mem-"+UUID.randomUUID().toString().substring(0,12);
        ContentValues v=new ContentValues();v.put("kind",kind);v.put("app",app);v.put("title",title);v.put("body",body);v.put("tags",tags.toString());v.put("updated_at",now);
        if(update)getWritableDatabase().update("memories",v,"id=?",new String[]{id});
        else{v.put("id",id);v.put("created_at",now);v.put("uses",0);v.put("last_used_at",now);getWritableDatabase().insertOrThrow("memories",null,v);evict();}
        return new JSONObject().put("ok",true).put("id",id).put("updated",update);
    }
    private void evict(){getWritableDatabase().execSQL("DELETE FROM memories WHERE id IN (SELECT id FROM memories ORDER BY uses ASC,last_used_at ASC LIMIT max(0,(SELECT count(*) FROM memories)-"+MAX_ITEMS+"))");}

    /** Terms for matching: whitespace/punctuation words, plus character pairs for CJK text that has no spaces. */
    static LinkedHashSet<String> terms(String q){
        LinkedHashSet<String> out=new LinkedHashSet<>();
        for(String w:q.toLowerCase(Locale.ROOT).split("[\\s\\p{Punct}，。、；：？！“”‘’（）【】《》]+")){
            if(w.isEmpty())continue;
            boolean cjk=w.codePoints().anyMatch(cp->Character.UnicodeScript.of(cp)==Character.UnicodeScript.HAN);
            if(cjk&&w.length()>2){for(int i=0;i+2<=w.length();i++)out.add(w.substring(i,i+2));}else out.add(w);
        }
        return out;
    }
    public synchronized JSONObject search(JSONObject a)throws Exception{
        String query=a.optString("query","").trim(),app=a.optString("app","").trim(),kind=a.optString("kind","").trim();int limit=Math.max(1,Math.min(10,a.optInt("limit",5)));
        if(query.length()>500)throw new IllegalArgumentException("query_max_500");
        LinkedHashSet<String> words=terms(query);ArrayList<JSONObject> scored=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT "+COLUMNS+" FROM memories"+(kind.isEmpty()?"":" WHERE kind=?"),kind.isEmpty()?null:new String[]{kind})){
            while(c.moveToNext()){JSONObject m=row(c);String hay=(m.optString("title")+" "+m.optString("text")+" "+m.optString("app")+" "+m.optJSONArray("tags").join(" ")).toLowerCase(Locale.ROOT);
                double score=0;for(String w:words)if(hay.contains(w))score+=m.optString("title").toLowerCase(Locale.ROOT).contains(w)?2:1;
                if(!app.isEmpty()&&app.equalsIgnoreCase(m.optString("app")))score+=3;
                if("preference".equals(m.optString("kind")))score+=0.5;
                if(words.isEmpty()&&app.isEmpty())score=1;
                if(score>0)scored.add(m.put("score",score));}
        }
        scored.sort((x,y)->Double.compare(y.optDouble("score"),x.optDouble("score")));
        JSONArray out=new JSONArray();long now=System.currentTimeMillis();
        for(int i=0;i<scored.size()&&out.length()<limit;i++){JSONObject m=scored.get(i);out.put(m);getWritableDatabase().execSQL("UPDATE memories SET uses=uses+1,last_used_at=? WHERE id=?",new Object[]{now,m.getString("id")});}
        return new JSONObject().put("ok",true).put("results",out).put("total",count());
    }
    public synchronized JSONObject delete(String id)throws Exception{int n=getWritableDatabase().delete("memories","id=?",new String[]{id});return new JSONObject().put("ok",n>0).put("deleted",n>0);}
    public synchronized void clear(){getWritableDatabase().delete("memories",null,null);}
    public synchronized int count(){try(Cursor c=getReadableDatabase().rawQuery("SELECT count(*) FROM memories",null)){return c.moveToFirst()?c.getInt(0):0;}}
    public synchronized JSONArray all()throws Exception{JSONArray out=new JSONArray();try(Cursor c=getReadableDatabase().rawQuery("SELECT "+COLUMNS+" FROM memories ORDER BY updated_at DESC",null)){while(c.moveToNext())out.put(row(c));}return out;}
}
