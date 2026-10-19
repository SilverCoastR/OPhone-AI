package ai.ophone.bridge;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.UUID;

/** Ends native work when the user leaves a model/control platform. */
final class PlatformSession {
    static final String CHATGPT="chatgpt_web",DEEPSEEK="deepseek_api";
    private PlatformSession(){}
    static boolean belongs(JSONObject task,String provider){boolean deep=task.optString("conversation").startsWith("c:api.deepseek.");return DEEPSEEK.equals(provider)?deep:CHATGPT.equals(provider)&&!deep;}
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
