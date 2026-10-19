package ai.ophone.bridge;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;

/** Durable task ownership and diagnostic data; independent of WebView DOM/storage. */
public final class AgentStore extends SQLiteOpenHelper {
    private static AgentStore instance;
    private final Context context;
    public static synchronized AgentStore get(Context c) {
        if (instance == null) instance = new AgentStore(c.getApplicationContext());
        return instance;
    }
    private AgentStore(Context c) { super(c, "agent_state.db", null, 1);context=c; }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE tasks(id TEXT PRIMARY KEY, conversation TEXT NOT NULL, human_key TEXT NOT NULL, status TEXT NOT NULL, updated INTEGER NOT NULL, payload TEXT NOT NULL, UNIQUE(conversation,human_key))");
        db.execSQL("CREATE TABLE operations(task_id TEXT NOT NULL, call_id TEXT NOT NULL, tool TEXT NOT NULL, status TEXT NOT NULL, updated INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(task_id,call_id))");
        db.execSQL("CREATE TABLE diagnostics(id INTEGER PRIMARY KEY AUTOINCREMENT,time_ms INTEGER NOT NULL,level TEXT NOT NULL,source TEXT NOT NULL,task_id TEXT NOT NULL,call_id TEXT NOT NULL,code TEXT NOT NULL,message TEXT NOT NULL,stack TEXT NOT NULL)");
        db.execSQL("CREATE INDEX diagnostics_task ON diagnostics(task_id,id)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { throw new IllegalStateException("unsupported_agent_schema_upgrade"); }
    public synchronized void putTask(JSONObject t) throws Exception {
        ContentValues v = new ContentValues();
        v.put("id", t.getString("id")); v.put("conversation", t.getString("conversation"));
        v.put("human_key", t.getString("human_key")); v.put("status", t.getString("status"));
        v.put("updated", t.getLong("updated_at")); v.put("payload", t.toString());
        if(getWritableDatabase().insertWithOnConflict("tasks", null, v, SQLiteDatabase.CONFLICT_REPLACE)<0)
            throw new android.database.SQLException("native_task_persistence_failed");
    }
    public synchronized JSONObject task(String id) throws Exception {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT payload FROM tasks WHERE id=?", new String[]{id})) {
            return c.moveToFirst() ? new JSONObject(c.getString(0)) : null;
        }
    }
    public synchronized JSONObject byHuman(String conversation, String key) throws Exception {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT payload FROM tasks WHERE conversation=? AND human_key=?", new String[]{conversation,key})) {
            return c.moveToFirst() ? new JSONObject(c.getString(0)) : null;
        }
    }
    public synchronized JSONArray tasks(String conversation, boolean activeOnly) throws Exception {
        String where = conversation == null ? "1=1" : "conversation=?";
        if (activeOnly) where += " AND status NOT IN ('succeeded','blocked','failed','cancelled')";
        JSONArray out = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT payload FROM tasks WHERE " + where + " ORDER BY updated DESC LIMIT 30", conversation == null ? null : new String[]{conversation})) {
            while (c.moveToNext()) out.put(new JSONObject(c.getString(0)));
        }
        return out;
    }
    public synchronized void putOperation(JSONObject op) throws Exception {
        ContentValues v = new ContentValues();
        v.put("task_id", op.getString("task_id")); v.put("call_id", op.getString("call_id"));
        v.put("tool", op.getString("tool")); v.put("status", op.getString("status"));
        v.put("updated", op.getLong("updated_at")); v.put("payload", op.toString());
        if(getWritableDatabase().insertWithOnConflict("operations", null, v, SQLiteDatabase.CONFLICT_REPLACE)<0)
            throw new android.database.SQLException("native_operation_persistence_failed");
    }
    public synchronized JSONObject operation(String task, String call) throws Exception {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT payload FROM operations WHERE task_id=? AND call_id=?", new String[]{task,call})) {
            return c.moveToFirst() ? new JSONObject(c.getString(0)) : null;
        }
    }
    public synchronized JSONArray operations(String task) throws Exception {
        JSONArray out = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT payload FROM operations WHERE task_id=? ORDER BY updated DESC LIMIT 80", new String[]{task})) {
            while(c.moveToNext()) out.put(new JSONObject(c.getString(0)));
        }
        return out;
    }
    public synchronized long addLog(String level, String source, String task, String call, String code, String message, String stack) {
        ContentValues v = new ContentValues();
        v.put("time_ms", System.currentTimeMillis()); v.put("level", level); v.put("source", source);
        v.put("task_id", task == null ? "" : task); v.put("call_id", call == null ? "" : call);
        v.put("code", code); v.put("message", message); v.put("stack", stack);
        SQLiteDatabase db = getWritableDatabase();
        long id = db.insertOrThrow("diagnostics", null, v);
        // Keep detailed records bounded; the crash file is separately imported on startup.
        if (id % 50 == 0) db.execSQL("DELETE FROM diagnostics WHERE id < ?", new Object[]{Math.max(0, id - 2000)});
        AgentEvents.get(context).diagnostic(id,level,source,task,code,message);
        return id;
    }
    public synchronized JSONObject logs(JSONObject args) throws Exception {
        StringBuilder where = new StringBuilder("id>?");
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        values.add(String.valueOf(Math.max(0,args.optLong("after_id",0))));
        for (String field : new String[]{"task_id","source","level"}) {
            String value = args.optString(field, "");
            if (!value.isEmpty()) { where.append(" AND ").append(field).append("=?"); values.add(value); }
        }
        String query = args.optString("query", "");
        if (!query.isEmpty()) { where.append(" AND (instr(code,?)>0 OR instr(message,?)>0 OR instr(stack,?)>0)"); values.add(query); values.add(query); values.add(query); }
        int limit = Math.max(1,Math.min(80,args.optInt("limit",20)));
        boolean forward = args.has("after_id");
        JSONArray events = new JSONArray(); long next = args.optLong("after_id",0);
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM diagnostics WHERE " + where + " ORDER BY id " + (forward ? "ASC" : "DESC") + " LIMIT " + limit, values.toArray(new String[0]))) {
            while(c.moveToNext()) {
                JSONObject row = logRow(c, false); events.put(row); next = Math.max(next,row.getLong("id"));
            }
        }
        return new JSONObject().put("ok",true).put("events",events).put("next_after_id",next)
                .put("scope","OPhone AI diagnostics, not Android system logcat");
    }
    public synchronized JSONObject log(long id) throws Exception {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM diagnostics WHERE id=?",new String[]{String.valueOf(id)})) {
            return c.moveToFirst() ? new JSONObject().put("ok",true).put("entry",logRow(c,true))
                    : new JSONObject().put("ok",false).put("error","diagnostic_not_found");
        }
    }
    private JSONObject logRow(Cursor c, boolean detail) throws Exception {
        JSONObject out = new JSONObject();
        for (String column : new String[]{"id","time_ms","level","source","task_id","call_id","code","message"}) {
            int i=c.getColumnIndexOrThrow(column);
            out.put(column, "id".equals(column)||"time_ms".equals(column) ? c.getLong(i) : c.getString(i));
        }
        String stack=c.getString(c.getColumnIndexOrThrow("stack"));
        out.put("has_stack",!stack.isEmpty()); if(detail) out.put("stack",stack);
        return out;
    }
    public synchronized void removeNativeTestFixtures(String conversationPrefix) {
        if(!conversationPrefix.matches("c:native-check-[0-9]+"))throw new SecurityException("invalid_test_fixture_scope");
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            String[] args={conversationPrefix+"-%"};
            db.execSQL("DELETE FROM operations WHERE task_id IN (SELECT id FROM tasks WHERE conversation LIKE ?)",args);
            db.execSQL("DELETE FROM diagnostics WHERE task_id IN (SELECT id FROM tasks WHERE conversation LIKE ?)",args);
            db.delete("tasks","conversation LIKE ?",args);db.setTransactionSuccessful();
        } finally {db.endTransaction();}
    }
}
