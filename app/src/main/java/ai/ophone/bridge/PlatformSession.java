package ai.ophone.bridge;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.UUID;

/** Ends native work when the user leaves a model/control platform. */
final class PlatformSession {
    static final String CHATGPT="chatgpt_web",DEEPSEEK="deepseek_api",OPENAI="openai_api",CLAUDE="claude_api";
    private PlatformSession(){}
    /** API platforms share local conversations (c:api.*); the ChatGPT web platform owns every other conversation. */
    static boolean belongs(JSONObject task,String provider){boolean api=task.optString("conversation").startsWith("c:api.");return ModelProvider.isApiPlatform(provider)?api:CHATGPT.equals(provider)&&!api;}
    /**
     * Leaving an API platform: open API tasks are not cancelled but paused with a question, so the next API platform
     * continues them only after the user explicitly answers "continue" there.
     */
    /** Leaving a platform: API work is parked for a possible handoff, ChatGPT web work is ended. */
    static void leave(Context context,String old){if(old==null||old.isEmpty())return;ModelProvider p=ModelProvider.forPlatform(old);if(p!=null){DirectModelAgent.stopProvider(old);parkApiTasks(context,p.label);}else cancelProvider(context,old);}
    /** ChatGPT web and the API platforms never share work; API platforms hand off to each other. */
    /** Chosen in the platform chooser opened from a chat ("Switch platform"); the chat does the switch when it is back. */
    private static volatile String switchRequest;
    static void requestSwitch(String provider){switchRequest=provider;}
    static String takeSwitchRequest(){String p=switchRequest;switchRequest=null;return p;}
    static void switchTo(Context context,String provider){String previous=PlatformPreference.get(context);
        if(ModelProvider.isApiPlatform(provider)){cancelProvider(context,CHATGPT);if(ModelProvider.isApiPlatform(previous)&&!previous.equals(provider))leave(context,previous);}
        else for(ModelProvider p:ModelProvider.ALL)cancelProvider(context,p.platform);
        PlatformPreference.set(context,provider);}
    static int parkApiTasks(Context context,String fromLabel){int parked=0;try{JSONArray active=AgentStore.get(context).tasks(null,true);ApiConversationStore history=new ApiConversationStore(context);
        for(int i=0;i<active.length();i++){JSONObject task=active.getJSONObject(i);if(!belongs(task,DEEPSEEK)||"waiting_user".equals(task.optString("status")))continue;
            try{String question=context.getString(R.string.handoff_question,fromLabel,task.optString("goal"));if(question.length()>1000)question=question.substring(0,1000);
                JSONArray options=new JSONArray().put(context.getString(R.string.handoff_continue)).put(context.getString(R.string.budget_stop));
                TaskController.get(context).awaitUser(new JSONObject().put("conversation",task.getString("conversation")).put("task_id",task.getString("id")),new JSONObject().put("question",question).put("options",options));
                history.append(task.getString("conversation"),new JSONObject().put("role","assistant").put("content",question).put("ask_user",true).put("options",options).put("provider","native_task").put("created_at",System.currentTimeMillis()));parked++;
            }catch(Exception one){DetailedDiagnostics.event(context,"warning","task",task.optString("id"),"","platform_handoff_park_failed","Unable to pause one API task for platform handoff.",one);}}
        AuditLog.record(context,"platform_handoff",fromLabel,true,"paused_api_tasks="+parked);
    }catch(Exception error){DetailedDiagnostics.event(context,"error","task","","","platform_handoff_failed","Unable to enumerate API tasks for platform handoff.",error);}return parked;}
    static void cancelTask(Context context,JSONObject task) throws Exception {cancelTask(context,task,"User terminated this task or left its AI control platform.");}
    static void cancelTask(Context context,JSONObject task,String reason) throws Exception {
        String taskId=task.getString("id"),conversation=task.getString("conversation");JSONObject scope=new JSONObject().put("conversation",conversation).put("task_id",taskId).put("call_id","cancel-"+UUID.randomUUID().toString());
        if(!TaskController.terminal(task.optString("status")))try{TaskController.get(context).finish(scope,new JSONObject().put("outcome","cancelled").put("reason",reason));}catch(IllegalStateException already){if(!String.valueOf(already.getMessage()).contains("task_already_terminal"))throw already;}
        ScreenRecordingStore.cancelTask(context,taskId,reason);try{WorkbenchClient.get(context).cancelTask(taskId,conversation);}catch(Exception error){DetailedDiagnostics.event(context,"warning","task",taskId,"","workbench_task_cancel_failed","Native task ended, but one workbench cancellation call failed.",error);}
    }
    static int cancelConversation(Context context,String conversation,String reason){int ended=0;try{JSONArray active=AgentStore.get(context).tasks(conversation,true);for(int i=0;i<active.length();i++)try{cancelTask(context,active.getJSONObject(i),reason);ended++;}catch(Exception one){DetailedDiagnostics.event(context,"error","task",active.optJSONObject(i)==null?"":active.optJSONObject(i).optString("id"),"","conversation_task_cancel_failed","Unable to cancel one conversation task.",one);}}catch(Exception error){DetailedDiagnostics.event(context,"error","task","","","conversation_cancel_failed","Unable to enumerate conversation tasks.",error);}return ended;}
    static boolean hasActiveConversation(Context context,String conversation){try{return AgentStore.get(context).tasks(conversation,true).length()>0;}catch(Exception ignored){return false;}}
    static int cancelProvider(Context context,String provider){DirectModelAgent.stopProvider(provider);int ended=0;try{JSONArray active=AgentStore.get(context).tasks(null,true);for(int i=0;i<active.length();i++){JSONObject task=active.getJSONObject(i);if(!belongs(task,provider))continue;try{cancelTask(context,task);ended++;}catch(Exception one){DetailedDiagnostics.event(context,"error","task",task.optString("id"),"","platform_task_cancel_failed","Unable to cancel one platform task.",one);}}WorkbenchClient.get(context).close();AuditLog.record(context,"platform_exit",provider,true,"cancelled_active_tasks="+ended+"; workbench binding closed");}catch(Exception error){DetailedDiagnostics.event(context,"error","task","","","platform_exit_cancel_failed","Unable to enumerate active platform tasks.",error);}return ended;}
}
