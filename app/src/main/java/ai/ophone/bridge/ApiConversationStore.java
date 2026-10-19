package ai.ophone.bridge;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.UUID;

/** Provider-neutral, bounded local API conversation history. Credentials never enter this store. */
public final class ApiConversationStore {
    private static final String PREFS="api_conversations_v2",LEGACY="direct_api_chat_v1";
    private static final int MAX_MESSAGES=80,MAX_BYTES=384*1024,MAX_CONVERSATIONS=100;
    private final SharedPreferences prefs;private final Context context;private final String legacyName;
    ApiConversationStore(Context context){this(context.getApplicationContext(),PREFS,LEGACY);reconcileNativeTasks();}
    ApiConversationStore(Context context,String prefsName,String legacyName){this.context=context;this.legacyName=legacyName;prefs=context.getSharedPreferences(prefsName,Context.MODE_PRIVATE);migrate();}

    synchronized String conversationId(){String id=prefs.getString("active_id","");if(id.isEmpty()||find(id)<0)id=create("deepseek");return id;}
    synchronized JSONArray messages(){return messages(conversationId());}
    synchronized JSONArray messages(String id){try{return new JSONArray(prefs.getString("messages:"+id,"[]"));}catch(Exception e){return new JSONArray();}}
    synchronized JSONArray conversations(){try{return new JSONArray(prefs.getString("index","[]"));}catch(Exception e){return new JSONArray();}}

    synchronized String create(String provider){try{
        String safe=provider==null||!provider.matches("[A-Za-z0-9_-]{1,30}")?"api":provider.toLowerCase();String id="c:api."+safe+"."+UUID.randomUUID().toString().replace("-","");long now=System.currentTimeMillis();
        JSONArray index=conversations(),next=new JSONArray().put(new JSONObject().put("id",id).put("title",context.getString(R.string.conversation_new_title)).put("created_at",now).put("updated_at",now).put("last_provider",safe).put("message_count",0));
        for(int i=0;i<index.length()&&next.length()<MAX_CONVERSATIONS;i++)next.put(index.get(i));
        SharedPreferences.Editor e=prefs.edit().putString("active_id",id).putString("index",next.toString()).putString("messages:"+id,"[]");
        if(index.length()>=MAX_CONVERSATIONS)for(int i=MAX_CONVERSATIONS-1;i<index.length();i++){JSONObject old=index.optJSONObject(i);if(old!=null)e.remove("messages:"+old.optString("id"));}
        if(!e.commit())throw new IllegalStateException("conversation_create_failed");return id;
    }catch(Exception e){throw new IllegalStateException(e);}}

    synchronized void select(String id){if(find(id)<0)throw new IllegalArgumentException("conversation_not_found");if(!prefs.edit().putString("active_id",id).commit())throw new IllegalStateException("conversation_select_failed");}
    synchronized void setProvider(String provider){try{touch(conversationId(),null,provider);}catch(Exception ignored){}}
    synchronized void append(JSONObject message) throws Exception {append(conversationId(),message);}
    synchronized void append(String id,JSONObject message) throws Exception {
        if(find(id)<0)throw new IllegalArgumentException("conversation_not_found");JSONObject copy=new JSONObject(message.toString());JSONArray all=messages(id);all.put(copy);all=trim(all);
        String title=null;if("user".equals(copy.optString("role"))&&!copy.optBoolean("internal")){String value=copy.optString("content").replaceAll("\\s+"," ").trim();if(!value.isEmpty())title=value.substring(0,Math.min(32,value.length()));}
        if(!prefs.edit().putString("messages:"+id,all.toString()).commit())throw new IllegalStateException("conversation_save_failed");touch(id,title,copy.optString("provider",""));
    }
    synchronized void replaceLastAssistant(JSONObject message) throws Exception {replaceLastAssistant(conversationId(),message);}
    synchronized void replaceLastAssistant(String id,JSONObject message) throws Exception {JSONArray all=messages(id);if(all.length()>0&&"assistant".equals(all.optJSONObject(all.length()-1)==null?"":all.optJSONObject(all.length()-1).optString("role"))){JSONArray next=new JSONArray();for(int i=0;i<all.length()-1;i++)next.put(all.get(i));all=next;}all.put(new JSONObject(message.toString()));all=trim(all);prefs.edit().putString("messages:"+id,all.toString()).commit();touch(id,null,message.optString("provider",""));}
    synchronized String delete(String id){
        int position=find(id);if(position<0)throw new IllegalArgumentException("conversation_not_found");JSONArray old=conversations(),next=new JSONArray();for(int i=0;i<old.length();i++){JSONObject item=old.optJSONObject(i);if(item!=null&&!id.equals(item.optString("id")))next.put(item);}SharedPreferences.Editor editor=prefs.edit().remove("messages:"+id).putString("index",next.toString());String active=prefs.getString("active_id","");if(id.equals(active))editor.remove("active_id");if(!editor.commit())throw new IllegalStateException("conversation_delete_failed");if(next.length()==0)return create("deepseek");String selected=id.equals(active)?next.optJSONObject(0).optString("id"):active;if(selected.isEmpty()||find(selected)<0)selected=next.optJSONObject(0).optString("id");select(selected);return selected;
    }
    /** Backward-compatible name: starting fresh preserves every previous conversation. */
    synchronized void clear(){create("deepseek");}

    private int find(String id){JSONArray index=conversations();for(int i=0;i<index.length();i++){JSONObject item=index.optJSONObject(i);if(item!=null&&id.equals(item.optString("id")))return i;}return -1;}
    private void touch(String id,String title,String provider)throws Exception{JSONArray old=conversations(),rest=new JSONArray();JSONObject hit=null;for(int i=0;i<old.length();i++){JSONObject item=old.getJSONObject(i);if(id.equals(item.optString("id")))hit=new JSONObject(item.toString());else rest.put(item);}if(hit==null)return;hit.put("updated_at",System.currentTimeMillis()).put("message_count",messages(id).length());if(title!=null&&defaultTitle(hit.optString("title")))hit.put("title",title);if(provider!=null&&!provider.isEmpty())hit.put("last_provider",provider);JSONArray sorted=new JSONArray().put(hit);for(int i=0;i<rest.length();i++)sorted.put(rest.get(i));prefs.edit().putString("index",sorted.toString()).commit();}
    private boolean defaultTitle(String value){return value==null||value.isEmpty()||value.equals(context.getString(R.string.conversation_new_title))||"新对话".equals(value)||"New conversation".equals(value);}
    private void migrate(){if(prefs.getBoolean("legacy_migrated",false))return;try{
        SharedPreferences legacy=context.getSharedPreferences(legacyName,Context.MODE_PRIVATE);String id=legacy.getString("conversation","");JSONArray all=new JSONArray(legacy.getString("messages","[]"));
        if(id.isEmpty())id="c:api.deepseek."+UUID.randomUUID().toString().replace("-","");String title=context.getString(R.string.conversation_history_title),untitled=title;
        for(int i=0;i<all.length();i++){JSONObject m=all.optJSONObject(i);if(m==null)continue;if("assistant".equals(m.optString("role"))&&DirectModelAgent.looksLikeProtocol(m.optString("content")))m.put("internal",true).put("hidden_reason","legacy_protocol_markup");if("user".equals(m.optString("role"))&&!m.optBoolean("internal")&&untitled.equals(title)){String value=m.optString("content").replaceAll("\\s+"," ").trim();if(!value.isEmpty())title=value.substring(0,Math.min(32,value.length()));}}
        long now=System.currentTimeMillis();JSONArray index=new JSONArray().put(new JSONObject().put("id",id).put("title",title).put("created_at",now).put("updated_at",now).put("last_provider","deepseek").put("message_count",all.length()));
        prefs.edit().putBoolean("legacy_migrated",true).putString("active_id",id).putString("index",index.toString()).putString("messages:"+id,trim(all).toString()).commit();
    }catch(Exception ignored){prefs.edit().putBoolean("legacy_migrated",true).commit();}}
    private synchronized void reconcileNativeTasks(){try{JSONArray index=conversations();for(int i=0;i<index.length();i++){String id=index.getJSONObject(i).optString("id");JSONArray existing=messages(id),tasks=AgentStore.get(context).tasks(id,false);for(int j=Math.min(tasks.length(),10)-1;j>=0;j--){JSONObject task=tasks.getJSONObject(j);String status=task.optString("status");if(!java.util.Arrays.asList("succeeded","waiting_event").contains(status)||containsTask(existing,task.optString("id")))continue;String summary=task.optString("summary").trim(),content=context.getString("succeeded".equals(status)?R.string.native_task_receipt_success:R.string.native_task_waiting_event,task.optString("goal"));if(!summary.isEmpty())content+="\n"+summary;JSONObject update=new JSONObject().put("role","progress").put("content",content).put("provider","native_task").put("created_at",System.currentTimeMillis()).put("task_id",task.optString("id")).put("task_status",status);append(id,update);existing=messages(id);}}}catch(Exception ignored){}}
    private static boolean containsTask(JSONArray messages,String taskId){if(taskId==null||taskId.isEmpty())return false;for(int i=0;i<messages.length();i++){JSONObject message=messages.optJSONObject(i);if(message!=null&&taskId.equals(message.optString("task_id")))return true;}return false;}
    private JSONArray trim(JSONArray all) throws Exception {
        while(all.length()>MAX_MESSAGES||all.toString().getBytes("UTF-8").length>MAX_BYTES){boolean compacted=false;for(int i=0;i<all.length()-1;i++){JSONObject m=all.optJSONObject(i);if(m!=null&&"tool".equals(m.optString("role"))&&m.opt("content")!=null&&String.valueOf(m.opt("content")).length()>512){m.put("content","{\"ok\":true,\"summary\":\"Older tool output was processed and compacted; inspect current state again if needed.\"}");compacted=true;break;}}if(compacted)continue;if(all.length()<=1)throw new IllegalStateException("conversation_message_too_large");int drop=1;for(int i=1;i<all.length();i++){JSONObject m=all.optJSONObject(i);if(m!=null&&"user".equals(m.optString("role"))&&!m.optBoolean("internal")){drop=i;break;}}JSONArray next=new JSONArray();for(int i=drop;i<all.length();i++)next.put(all.get(i));all=next;}return all;
    }
}
