package ai.ophone.bridge;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Native task authority. Models may plan; only verified Android evidence completes tasks. */
public final class TaskController {
    private static TaskController instance;
    private final Context context;
    private final AgentStore store;
    private TaskController(Context c) { context=c.getApplicationContext();store=AgentStore.get(c); }
    public static synchronized TaskController get(Context c) { if(instance==null) instance=new TaskController(c);return instance; }
    public static boolean terminal(String status) { return java.util.Arrays.asList("succeeded","blocked","failed","cancelled").contains(status); }
    static String digest(String text) throws Exception {
        byte[] bytes=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)); StringBuilder b=new StringBuilder();
        for(byte value:bytes) b.append(String.format(Locale.ROOT,"%02x",value&255));return b.toString();
    }
    private void save(JSONObject t) throws Exception { t.put("updated_at",System.currentTimeMillis());store.putTask(t);if(terminal(t.optString("status")))AgentEvents.get(context).closeTask(t.getString("id")); }
    private static boolean isContinuation(String goal) { return goal.trim().matches("(?i)(继续|接着|继续执行|continue|resume)[。.!！\\s]*"); }
    private static boolean isReplacement(String goal){return goal.matches("(?is).*(改为|改成|换成|换为|之前.{0,20}先不管|旧任务.{0,20}不管|重新开始|change\\s+(?:the\\s+)?target|switch\\s+(?:the\\s+)?target|ignore\\s+(?:the\\s+)?previous|start\\s+over).*");}
    public synchronized JSONObject begin(String conversation,String human,String goal) throws Exception {
        if(!conversation.matches("c:[A-Za-z0-9:._-]{1,180}")||human.isEmpty()||human.length()>300||goal.trim().isEmpty()||goal.length()>8192) throw new IllegalArgumentException("invalid_task_origin");
        JSONObject old=store.byHuman(conversation,human); if(old!=null)return old;
        JSONArray previous=store.tasks(conversation,true);
        boolean continuation=isContinuation(goal);
        if(continuation&&previous.length()>0) {
            JSONObject resumed=previous.getJSONObject(0);
            resumed.put("resume_human_key",human).put("status","running").put("model_wait_at",System.currentTimeMillis()).put("last_seen_at",System.currentTimeMillis());save(resumed);return resumed;
        }
        JSONObject predecessor=null;
        if(continuation&&previous.length()==0) {
            JSONArray history=store.tasks(conversation,false);
            if(history.length()>0&&java.util.Arrays.asList("blocked","failed").contains(history.getJSONObject(0).optString("status"))) {
                predecessor=history.getJSONObject(0);goal=predecessor.getString("goal");
            }
        }
        for(int i=0;i<previous.length();i++) {JSONObject t=previous.getJSONObject(i);t.put("status","cancelled").put("end_reason","superseded_by_new_user_request");save(t);}
        long now=System.currentTimeMillis();
        JSONObject t=new JSONObject().put("id","task-"+digest(conversation+"\n"+human).substring(0,32)).put("conversation",conversation)
                .put("human_key",human).put("goal",goal).put("intent",TaskVerifier.intent(goal)).put("status","running")
                .put("created_at",now).put("updated_at",now).put("progress_at",now).put("model_wait_at",now).put("last_seen_at",now)
                .put("deadline_at",now+30*60*1000).put("criteria",new JSONArray()).put("issues",new JSONArray())
                .put("pending_call",JSONObject.NULL).put("no_progress_count",0).put("wake_count",0).put("last_tool","");
        t.put("tool_count",0).put("action_count",0);
        if(predecessor!=null) {
            t.put("resumed_from",predecessor.getString("id")).put("previous_end_reason",predecessor.optString("end_reason"))
                    .put("resume_observation_required",true)
                    .put("criteria",new JSONArray(predecessor.optJSONArray("criteria")==null?"[]":predecessor.getJSONArray("criteria").toString()));
            requireDiagnostics(t,new String[]{"task_status","diagnostic_query"});
        }
        store.putTask(t); log(t,"task_created","Original user goal persisted on the phone.",null);return t;
    }
    private JSONObject require(String id) throws Exception { JSONObject t=store.task(id);if(t==null)throw new IllegalArgumentException("task_not_found");return t; }
    public synchronized JSONObject scoped(JSONObject scope,JSONObject args) throws Exception {
        String id=scope==null?args.optString("task_id"):scope.optString("task_id",args.optString("task_id"));
        if(id.isEmpty())throw new IllegalArgumentException("native_task_required");
        JSONObject t=require(id);
        if(scope!=null&&!scope.optString("conversation").equals(t.getString("conversation")))throw new SecurityException("task_conversation_mismatch");
        return t;
    }
    public synchronized JSONObject status(JSONObject scope,JSONObject args) throws Exception {
        JSONObject t=scoped(scope,args);
        return new JSONObject().put("ok",true).put("task",t).put("operations",store.operations(t.getString("id")))
                .put("event_deliveries",eventChecks(t)).put("completion_rule","Fresh Android evidence must satisfy the original goal; a final chat reply is not proof.");
    }
    public synchronized JSONObject checkpoint(JSONObject scope,JSONObject args)throws Exception{
        JSONObject t=scoped(scope,args);if(terminal(t.optString("status")))throw new IllegalStateException("task_already_terminal");
        String summary=DetailedDiagnostics.clean(args.optString("summary")).trim(),next=DetailedDiagnostics.clean(args.optString("next_action")).trim();
        if(summary.isEmpty()||summary.length()>1200||next.isEmpty()||next.length()>600)throw new IllegalArgumentException("checkpoint_summary_and_next_action_required");
        JSONObject checkpoint=new JSONObject().put("summary",summary).put("next_action",next)
                .put("completed_steps",cleanPlanItems(args.optJSONArray("completed_steps"),20))
                .put("remaining_steps",cleanPlanItems(args.optJSONArray("remaining_steps"),20))
                .put("blockers",cleanPlanItems(args.optJSONArray("blockers"),10))
                .put("updated_at",System.currentTimeMillis());
        t.put("checkpoint",checkpoint).put("progress_at",System.currentTimeMillis()).put("last_seen_at",System.currentTimeMillis());save(t);
        log(t,"task_checkpoint_updated","A concise operational checkpoint was persisted for recovery.",null);
        return new JSONObject().put("ok",true).put("task_id",t.getString("id")).put("checkpoint",checkpoint);
    }
    private static JSONArray cleanPlanItems(JSONArray input,int max)throws Exception{
        JSONArray out=new JSONArray();for(int i=0;input!=null&&i<input.length()&&i<max;i++){String item=DetailedDiagnostics.clean(String.valueOf(input.opt(i))).trim();if(item.isEmpty())continue;if(item.length()>400)item=item.substring(0,400);out.put(item);}return out;
    }
    public synchronized JSONObject waitForEvent(JSONObject scope,JSONObject args)throws Exception{
        JSONObject t=scoped(scope,args);if(terminal(t.optString("status")))throw new IllegalStateException("task_already_terminal");
        JSONObject eventState=AgentEvents.get(context).query(t.getString("id"),new JSONObject().put("limit",1));
        JSONArray subscriptions=eventState.optJSONArray("subscriptions"),pending=eventState.optJSONArray("pending_deliveries");
        if(pending!=null&&pending.length()>0)return new JSONObject().put("ok",false).put("error","event_already_pending").put("pending_deliveries",pending).put("next_step","Process the pending event evidence now instead of entering a wait.");
        if(subscriptions==null||subscriptions.length()==0)throw new IllegalStateException("active_event_subscription_required");
        long now=System.currentTimeMillis(),until=args.optLong("until_epoch_ms",Math.min(t.optLong("deadline_at",now+30*60*1000L),now+30*60*1000L));
        if(until<now+1000||until>now+24*60*60*1000L)throw new IllegalArgumentException("event_wait_deadline_out_of_range");
        String reason=DetailedDiagnostics.clean(args.optString("reason")).trim();if(reason.length()<3||reason.length()>600)throw new IllegalArgumentException("event_wait_reason_required");
        t.put("status","waiting_event").put("wait_mode","subscription").put("wait_reason",reason).put("wait_until",until)
                .put("model_wait_at",0).put("last_seen_at",now).put("deadline_at",Math.max(t.optLong("deadline_at",0),until+5*60*1000L));save(t);
        log(t,"task_waiting_for_event","The task entered a durable event wait and will resume on delivery or deadline.",null);
        return new JSONObject().put("ok",true).put("task_id",t.getString("id")).put("state","waiting_event").put("until_epoch_ms",until).put("subscriptions",subscriptions);
    }
    public synchronized JSONObject criteria(JSONObject scope,JSONObject args) throws Exception {
        JSONObject t=scoped(scope,args);if(terminal(t.getString("status")))throw new IllegalStateException("task_already_terminal");
        JSONArray requested=args.getJSONArray("criteria"),criteria=normalizeRecordingCriteria(requested,t.getString("goal")); TaskVerifier.validate(criteria,t.getString("goal"));
        t.put("criteria",criteria).put("status","running").put("progress_at",System.currentTimeMillis());
        save(t);log(t,"criteria_updated","Completion conditions registered against the original task intent.",null);
        return new JSONObject().put("ok",true).put("task",t).put("criteria_normalized",criteria.length()!=requested.length());
    }
    public synchronized JSONObject verify(JSONObject scope,JSONObject args,boolean finish) throws Exception {
        JSONObject t=scoped(scope,args);if(terminal(t.getString("status")))return new JSONObject().put("ok","succeeded".equals(t.getString("status"))).put("task",t).put("verified","succeeded".equals(t.getString("status")));
        JSONArray notices=eventChecks(t);
        if(notices.length()>0){t.put("status","waiting_events");save(t);return new JSONObject().put("ok",true).put("verified",false).put("waiting_for_events",true).put("error","event_notifications_pending").put("event_deliveries",notices).put("task",t).put("next_step","Read event_query, perform only the notice's required checks, then event_ack(delivery_id,through_event_id) before completing the original goal. This is waiting for event processing, not a runtime fault.");}
        JSONArray checks=t.optJSONArray("diagnostic_checks");
        JSONObject check=checks!=null&&checks.length()>0
                ?new JSONObject().put("ok",true).put("verified",false).put("error","diagnostic_checks_pending").put("required_checks",checks).put("evidence",new JSONArray())
                :TaskVerifier.verify(context,t);
        t.put("last_verification",check).put("progress_at",System.currentTimeMillis());
        if(check.optBoolean("verified")) {
            t.put("status",finish?"succeeded":"verified");
            if(finish)t.put("summary",args.optString("summary","")).put("finished_at",System.currentTimeMillis());
            log(t,finish?"task_succeeded":"goal_verified","Fresh Android completion verification passed.",null);
        } else if(recordingProgress(t).optBoolean("pending")) {
            t.put("status","waiting_event").put("model_wait_at",0);
            check.put("waiting_for_recordings",true).put("next_step","The native recording jobs own the wait. Stop model polling; the task supervisor will verify receipts and close the task when events complete.");
        } else {
            t.put("status","diagnosing");
            issue(t,check.optString("error","goal_not_verified"),"The original goal is not verified. Inspect task_status and diagnostics, register correct criteria if missing, then continue the task.");
        }
        save(t);
        // The task already owns check as last_verification. Never insert task back into that same object.
        return new JSONObject(check.toString()).put("ok",!finish||check.optBoolean("verified")).put("task",t)
                .put("next_step",check.optBoolean("verified")?"Report the verified result.":"Inspect the failed evidence and continue the original task; do not declare success.");
    }
    public synchronized JSONObject finish(JSONObject scope,JSONObject args) throws Exception {
        String outcome=args.optString("outcome","success");
        if("success".equals(outcome))return verify(scope,args,true);
        if(!java.util.Arrays.asList("blocked","failed","cancelled").contains(outcome))throw new IllegalArgumentException("invalid_task_outcome");
        String reason=args.optString("reason").trim();if(reason.length()<8||reason.length()>1500)throw new IllegalArgumentException("concrete_blocker_reason_required");
        JSONObject t=scoped(scope,args);if(terminal(t.getString("status")))throw new IllegalStateException("task_already_terminal");
        t.put("status",outcome).put("end_reason",DetailedDiagnostics.clean(reason)).put("finished_at",System.currentTimeMillis());
        save(t);log(t,"task_"+outcome,reason,null);return new JSONObject().put("ok",true).put("task",t).put("verified",false);
    }
    public synchronized JSONObject bridge(String action,JSONObject args) throws Exception {
        String conversation=args.optString("conversation","");
        if(!conversation.matches("c:[A-Za-z0-9:._-]{1,180}"))throw new IllegalArgumentException("invalid_task_conversation");
        if("observe_request".equals(action)) {
            String goal=args.optString("goal");
            if(args.optBoolean("guidance_if_active")){JSONArray active=store.tasks(conversation,true);if(active.length()>0){JSONObject existing=active.getJSONObject(0);if(isReplacement(goal)){PlatformSession.cancelTask(context,existing,"The user replaced the active target with a new task.");JSONObject replacement=begin(conversation,args.optString("human_key"),goal);log(replacement,"task_target_replaced","The old task and its native jobs were cancelled before starting the new target.",null);return new JSONObject().put("ok",true).put("managed",true).put("replaced",true).put("task",replacement);}long now=System.currentTimeMillis();existing.put("last_guidance",DetailedDiagnostics.clean(goal)).put("guidance_count",existing.optInt("guidance_count")+1).put("last_seen_at",now).put("model_wait_at",now);if("waiting_provider".equals(existing.optString("status"))||"awaiting_model".equals(existing.optString("status")))existing.put("status","running");save(existing);log(existing,"user_guidance","The user added guidance to the running task; the original task identity was preserved.",null);return new JSONObject().put("ok",true).put("managed",true).put("continued",true).put("task",existing);}}
            if("general".equals(TaskVerifier.intent(goal))) {
                if(!isContinuation(goal))return new JSONObject().put("ok",true).put("managed",false);
                JSONArray candidates=store.tasks(conversation,true);
                if(candidates.length()==0)candidates=store.tasks(conversation,false);
                if(candidates.length()==0||("succeeded".equals(candidates.getJSONObject(0).optString("status"))||"cancelled".equals(candidates.getJSONObject(0).optString("status"))))
                    return new JSONObject().put("ok",true).put("managed",false);
            }
            return new JSONObject().put("ok",true).put("managed",true).put("task",begin(conversation,args.optString("human_key"),goal));
        }
        if("start".equals(action))return new JSONObject().put("ok",true).put("task",begin(conversation,args.optString("human_key"),args.optString("goal")));
        if("poll".equals(action)) {
            JSONArray active=store.tasks(conversation,true);
            if(active.length()==0)active=store.tasks(conversation,false);
            JSONObject t=active.length()==0?null:active.getJSONObject(0);
            if(t!=null&&!terminal(t.getString("status"))) {
                if(!t.has("tool_count"))t.put("tool_count",store.operations(t.getString("id")).length());
                t.put("last_seen_at",System.currentTimeMillis());
                if("waiting_provider".equals(t.optString("status")))t.put("status","awaiting_model").put("model_wait_at",System.currentTimeMillis());
                save(t);
            }
            JSONArray notices=t==null||terminal(t.optString("status"))?new JSONArray():eventChecks(t);
            return new JSONObject().put("ok",true).put("task",t==null?JSONObject.NULL:t).put("event_deliveries",notices);
        }
        JSONObject t=require(args.getString("task_id"));
        if(!conversation.equals(t.getString("conversation")))throw new SecurityException("task_conversation_mismatch");
        if("heartbeat".equals(action)) {
            if(!terminal(t.getString("status"))){long now=System.currentTimeMillis();t.put("last_seen_at",now);if("waiting_provider".equals(t.optString("status")))t.put("status","awaiting_model");clearIssue(t,"model_provider_unavailable");save(t);}
            return new JSONObject().put("ok",true).put("task",t);
        }
        if("note_transport_recovery".equals(action)) {
            if(!terminal(t.getString("status"))) {
                String message="A lost execution callback was recovered from the native receipt without repeating the Android action. Read fresh runtime_status and runtime_logs before verifying completion.";
                issue(t,"transport_receipt_recovered",message);
                requireDiagnostics(t,new String[]{"runtime_status","runtime_logs"});save(t);
            }
            return new JSONObject().put("ok",true).put("task",t);
        }
        if("feedback".equals(action)) {
            JSONObject op=store.operation(t.getString("id"),args.optString("call_id"));
            if(op!=null){op.put("feedback_delivered",true).put("updated_at",System.currentTimeMillis());store.putOperation(op);}
            JSONObject pending=t.optJSONObject("pending_call");
            if(pending!=null&&pending.optString("call_id").equals(args.optString("call_id")))t.put("pending_call",JSONObject.NULL);
            if(!terminal(t.getString("status"))){boolean deferred=recordingProgress(t).optBoolean("pending");t.put("status",deferred?"waiting_event":"awaiting_model").put("model_wait_at",deferred?0:System.currentTimeMillis()).put("last_seen_at",System.currentTimeMillis());}
            save(t);return new JSONObject().put("ok",true).put("task",t);
        }
        if("ack_issue".equals(action)) {
            JSONArray issues=t.optJSONArray("issues");
            for(int i=0;issues!=null&&i<issues.length();i++)if(issues.getJSONObject(i).optString("id").equals(args.optString("issue_id")))issues.getJSONObject(i).put("delivered",true);
            t.put("model_wait_at",System.currentTimeMillis());save(t);return new JSONObject().put("ok",true);
        }
        if("propose_end".equals(action))return verify(new JSONObject().put("conversation",conversation).put("task_id",t.getString("id")),args,true);
        throw new IllegalArgumentException("unknown_task_action");
    }
    public synchronized void beforeTool(JSONObject scope,String tool,JSONObject args) throws Exception {
        if(scope==null||scope.optString("task_id").isEmpty())return;
        JSONObject t=scoped(scope,args);
        boolean diagnostic=tool.startsWith("task_")||tool.startsWith("diagnostic_")||tool.startsWith("runtime_")||tool.startsWith("event_")||"capabilities".equals(tool)||"root_status".equals(tool)||"execution_status".equals(tool);
        if(terminal(t.getString("status"))&&!diagnostic)throw new IllegalStateException("task_finished_new_user_request_required");
        if(t.optBoolean("resume_observation_required")&&isMutation(tool))throw new IllegalStateException("resumed_task_requires_fresh_android_observation_before_actions");
        int count=t.optInt("tool_count",store.operations(t.getString("id")).length()),actions=t.optInt("action_count",0);
        if(!terminal(t.getString("status"))&&isMutation(tool)&&actions>=48) {
            t.put("status","failed").put("end_reason","Native task action budget exceeded (48 state-changing calls); original goal and diagnostics retained.");
            save(t);log(t,"native_task_budget_exceeded",t.getString("end_reason"),null);
            throw new IllegalStateException("native_task_tool_budget_exceeded");
        }
        t.put("tool_count",count+1);if(isMutation(tool))t.put("action_count",actions+1);
        String call=scope.optString("call_id"),fingerprint=digest(tool+"\n"+args.toString());
        if(!diagnostic&&isMutation(tool)) {
            JSONArray ops=store.operations(t.getString("id"));
            for(int i=0;i<ops.length();i++) {
                JSONObject op=ops.getJSONObject(i);
                if(fingerprint.equals(op.optString("fingerprint"))&&"unknown".equals(op.optString("status"))&&!op.optBoolean("observed_after"))throw new IllegalStateException("previous_action_outcome_unknown_inspect_actual_state_before_retry");
            }
        }
        JSONObject op=new JSONObject().put("task_id",t.getString("id")).put("call_id",call).put("tool",tool).put("fingerprint",fingerprint)
                .put("status","started").put("started_at",System.currentTimeMillis()).put("updated_at",System.currentTimeMillis()).put("feedback_delivered",false);
        store.putOperation(op);
        t.put("pending_call",new JSONObject().put("call_id",call).put("tool",tool).put("started_at",op.getLong("started_at")))
                .put("last_tool",tool).put("model_wait_at",0).put("wake_count",0);
        if(!terminal(t.getString("status")))t.put("status","executing"); save(t);
    }
    public synchronized void afterTool(JSONObject scope,String tool,JSONObject value,Throwable error) {
        if(scope==null||scope.optString("task_id").isEmpty())return;
        try {
            JSONObject t=require(scope.getString("task_id")),op=store.operation(t.getString("id"),scope.optString("call_id"));
            boolean ok=error==null&&(!value.has("ok")||value.optBoolean("ok"));
            if(op!=null) {
                op.put("status",error==null?"done":"error").put("ok",ok).put("updated_at",System.currentTimeMillis());
                if(error!=null)op.put("error",DetailedDiagnostics.clean(error.getClass().getSimpleName()+": "+error.getMessage()));
                else if(!ok)op.put("error",value.optString("error","tool_reported_failure"));
                store.putOperation(op);
            }
            if(!terminal(t.getString("status")))t.put("status",ok&&recordingProgress(t).optBoolean("pending")?"waiting_event":ok?"awaiting_feedback":"diagnosing");
            if(!ok&&!terminal(t.getString("status"))) {
                requireDiagnostics(t,new String[]{"task_status","diagnostic_query"});
                issue(t,"tool_failure_requires_diagnosis","A tool failed. Inspect the task and actual detailed error, choose a corrective plan, then verify the original goal.");
            }
            if(ok&&t.optJSONArray("diagnostic_checks")!=null) {
                JSONArray remaining=new JSONArray(),checks=t.getJSONArray("diagnostic_checks");
                for(int i=0;i<checks.length();i++)if(!tool.equals(checks.getString(i)))remaining.put(checks.get(i));
                t.put("diagnostic_checks",remaining);
            }
            if("get_ui".equals(tool)&&ok) {
                String hash=digest(value.toString());boolean changed=!hash.equals(t.optString("last_ui_hash"));
                t.put("last_ui_hash",hash).put("last_ui_package",value.optString("package"));
                int repeats=changed?0:t.optInt("no_progress_count",0)+1;t.put("no_progress_count",repeats);
                if(repeats>=3)issue(t,"repeated_observation_without_progress","The observed UI is unchanged. Check the original goal and actual download/operation progress; choose a different plan if stuck.");
            }
            if(ok&&java.util.Arrays.asList("get_ui","screenshot","find_apps","browser_snapshot","browser_tabs","terminal_status","workbench_status","workbench_file").contains(tool)) {
                t.put("resume_observation_required",false);
                JSONArray past=store.operations(t.getString("id"));
                for(int i=0;i<past.length();i++) {
                    JSONObject previous=past.getJSONObject(i);
                    if("unknown".equals(previous.optString("status"))) {
                        previous.put("observed_after",true).put("observation_call_id",scope.optString("call_id")).put("updated_at",System.currentTimeMillis());store.putOperation(previous);
                    }
                }
            }
            t.put("progress_at",System.currentTimeMillis());save(t);
            if(!ok)DetailedDiagnostics.event(context,"error","tool",t.getString("id"),scope.optString("call_id"),"tool_failed",
                    error==null?value.optString("error","tool_reported_failure"):error.getClass().getName()+": "+error.getMessage(),error);
        } catch(Exception failed) { DetailedDiagnostics.event(context,"error","task","", "","task_record_error","Unable to record tool outcome.",failed); }
    }
    public synchronized void issue(JSONObject t,String code,String message) throws Exception {
        JSONArray issues=t.optJSONArray("issues");if(issues==null)issues=new JSONArray();
        for(int i=0;i<issues.length();i++) {JSONObject old=issues.getJSONObject(i);if(code.equals(old.optString("code"))&&!old.optBoolean("delivered"))return;}
        JSONArray bounded=new JSONArray();for(int i=Math.max(0,issues.length()-9);i<issues.length();i++)bounded.put(issues.get(i));
        bounded.put(new JSONObject().put("id","issue-"+System.currentTimeMillis()+"-"+code).put("code",code).put("message",message).put("delivered",false));
        t.put("issues",bounded);log(t,code,message,null);
    }
    public synchronized void reportRuntimeFault(String taskId,String code,String message) {
        try {
            JSONObject t=require(taskId);if(terminal(t.getString("status")))return;
            issue(t,code,"Executor error observed: "+DetailedDiagnostics.clean(message)+". Inspect task-scoped diagnostic_query and diagnostic_read, then choose a corrective plan.");
            requireDiagnostics(t,new String[]{"task_status","diagnostic_query"});
            t.put("status","diagnosing");save(t);
        } catch(Exception ignored) {}
    }
    private void requireDiagnostics(JSONObject task,String[] tools) throws Exception {
        java.util.LinkedHashSet<String> required=new java.util.LinkedHashSet<>();
        JSONArray old=task.optJSONArray("diagnostic_checks");
        for(int i=0;old!=null&&i<old.length();i++)required.add(old.getString(i));
        for(String tool:tools)required.add(tool);
        task.put("diagnostic_checks",new JSONArray(required));
    }
    private void clearIssue(JSONObject task,String code)throws Exception{JSONArray old=task.optJSONArray("issues"),next=new JSONArray();for(int i=0;old!=null&&i<old.length();i++)if(!code.equals(old.getJSONObject(i).optString("code")))next.put(old.getJSONObject(i));task.put("issues",next);}
    public synchronized JSONObject claimDirectResume(String conversation)throws Exception{
        if(conversation==null||!conversation.startsWith("c:api.deepseek."))return null;
        JSONArray tasks=store.tasks(conversation,true);if(tasks.length()==0)return null;JSONObject t=tasks.getJSONObject(0);
        if(terminal(t.optString("status"))||"waiting_event".equals(t.optString("status"))||t.optJSONObject("pending_call")!=null)return null;
        long now=System.currentTimeMillis(),last=t.optLong("model_wait_at",t.optLong("updated_at",now));if(now-last<8000)return null;
        int count=t.optInt("direct_resume_count",0);if(count>=3)return null;
        t.put("direct_resume_count",count+1).put("direct_resume_at",now).put("last_seen_at",now).put("status","running").put("model_wait_at",now);
        clearIssue(t,"model_provider_unavailable");save(t);log(t,"direct_model_auto_resume","DeepSeek direct task resumed from its persisted conversation and native receipts.",null);return new JSONObject(t.toString());
    }
    private JSONArray eventChecks(JSONObject task)throws Exception{
        JSONArray notices=AgentEvents.get(context).pending(task.getString("id"));JSONObject seen=task.optJSONObject("event_versions");if(seen==null)seen=new JSONObject();boolean changed=false;
        for(int i=0;i<notices.length();i++){JSONObject n=notices.getJSONObject(i);String id=n.getString("id"),version=String.valueOf(n.getLong("latest_event_id"));
            if(n.optBoolean("requires_diagnosis")&&!version.equals(seen.optString(id))){JSONArray checks=n.getJSONArray("required_checks");String[] names=new String[checks.length()];for(int j=0;j<names.length;j++)names[j]=checks.getString(j);requireDiagnostics(task,names);seen.put(id,version);changed=true;}
        }
        if(changed){task.put("event_versions",seen);save(task);}return notices;
    }
    public synchronized boolean providerReloaded(String taskId) {
        try {
            JSONObject t=require(taskId);if(terminal(t.getString("status"))||t.optJSONObject("pending_call")!=null)return false;
            issue(t,"provider_session_reloaded","The unresponsive web model session was reloaded in the SAME conversation. Original goal and receipts are retained. Inspect task state/diagnostics and continue; no Android action was replayed.");
            requireDiagnostics(t,new String[]{"task_status","diagnostic_query"});save(t);
            log(t,"provider_session_reloaded","Native transport supervision reloaded the same web conversation.",null);return true;
        } catch(Exception error){DetailedDiagnostics.event(context,"error","provider",taskId,"","provider_reconnect_failed","Unable to record provider reconnection.",error);return false;}
    }
    synchronized boolean wakeEventWait(JSONObject t,long now)throws Exception{
        if(!"waiting_event".equals(t.optString("status"))||!"subscription".equals(t.optString("wait_mode")))return false;
        JSONArray notices=eventChecks(t);boolean deadlineReached=now>=t.optLong("wait_until",Long.MAX_VALUE);
        if(notices.length()==0&&!deadlineReached)return false;
        t.put("status","awaiting_model").put("model_wait_at",now-9000).put("last_seen_at",now)
                .put("wait_trigger",notices.length()>0?"event_delivery":"wait_deadline");t.remove("wait_mode");save(t);return true;
    }
    public synchronized void supervise() {
        try {
            reconcileCompletedRecordings();
            JSONArray tasks=store.tasks(null,true);long now=System.currentTimeMillis();
            for(int i=0;i<tasks.length();i++) {
                JSONObject t=tasks.getJSONObject(i);
                if(now>t.optLong("deadline_at",now+1)) {
                    t.put("status","failed").put("end_reason","Task deadline exceeded; diagnostic report retained on the phone.");
                    log(t,"task_deadline_exceeded",t.getString("end_reason"),null);save(t);continue;
                }
                if("waiting_event".equals(t.optString("status"))){
                    if(wakeEventWait(t,now))continue;
                    t.put("last_seen_at",now);save(t);continue;
                }
                if(now-t.optLong("last_seen_at",now)>20000) {
                    t.put("status","waiting_provider");
                    issue(t,"model_provider_unavailable","The native goal is retained, but the model web session is unavailable. Reattach this task's conversation; do not ask the user to repeat the goal.");
                    save(t);continue;
                }
                JSONObject pending=t.optJSONObject("pending_call");
                if(pending!=null&&now-pending.optLong("started_at",now)>15000) {
                    JSONObject scope=new JSONObject().put("conversation",t.getString("conversation")).put("call_id",pending.getString("call_id"));
                    JSONObject receipt=ExecutionJournal.receipt(context,scope);
                    if("unknown".equals(receipt.optString("state"))||"missing".equals(receipt.optString("state"))) {
                        JSONObject op=store.operation(t.getString("id"),pending.getString("call_id"));
                        if(op!=null){op.put("status","unknown").put("updated_at",now);store.putOperation(op);}
                        issue(t,"native_action_outcome_unknown","An interrupted operation has no confirmed receipt. Inspect actual Android state; never blindly repeat the action.");
                    }
                    if(now-pending.optLong("started_at",now)>130000)issue(t,"native_feedback_pending","An operation receipt needs recovery. Inspect task_status and execution_status.");
                } else if(pending==null&&t.optLong("model_wait_at",0)>0&&now-t.getLong("model_wait_at")>45000) {
                    if(RuntimeDiagnostics.modelGenerating(t.getString("conversation")))continue;
                    if(t.getString("conversation").startsWith("c:api.deepseek.")){
                        if(t.optInt("direct_resume_count",0)>=3){t.put("status","failed").put("end_reason","DeepSeek direct task did not finish after three bounded automatic resumes; diagnostics and original goal retained.");log(t,"direct_model_resume_exhausted",t.getString("end_reason"),null);}
                        else issue(t,"native_task_needs_continuation","The DeepSeek direct task is still open. Its persisted conversation will resume automatically from current Android evidence.");
                        save(t);continue;
                    }
                    int wakes=t.optInt("wake_count",0);
                    if(wakes>=1&&t.optInt("provider_reload_count",0)<2&&now-t.optLong("provider_reload_at",0)>45000
                            &&ChatActivity.reconnectProvider(t.getString("conversation"),t.getString("id"))) {
                        t.put("provider_reload_count",t.optInt("provider_reload_count",0)+1).put("provider_reload_at",now).put("model_wait_at",now);
                        save(t);continue;
                    }
                    if(wakes>=3) {
                        t.put("status","failed").put("end_reason","Model did not continue after bounded native wake-ups; diagnostics retained.");
                        log(t,"model_resume_exhausted",t.getString("end_reason"),null);
                    } else {
                        issue(t,"native_task_needs_continuation","The original task is still open. Inspect native task state and errors, then resume planning and verify the goal.");
                        requireDiagnostics(t,new String[]{"task_status","diagnostic_query"});
                        t.put("wake_count",wakes+1).put("model_wait_at",now);
                    }
                }
                save(t);
            }
        } catch(Exception error) { DetailedDiagnostics.event(context,"error","task","","","task_supervisor_error","Native task supervision failed.",error); }
    }
    public static boolean isMutation(String tool) { return WorkbenchTools.mutates(tool)||java.util.Arrays.asList("tap","swipe","click_text","input_text","press_global","launch_app","open_url","download_file","install_apk","uninstall_app","screen_record_start").contains(tool); }

    /** Register the durable artifact receipt immediately; later UI changes cannot invalidate a recording. */
    public synchronized void recordingStarted(JSONObject scope,JSONObject result,JSONObject args,String kind)throws Exception{
        if(scope==null||scope.optString("task_id").isEmpty()||!result.optBoolean("ok"))return;JSONObject t=scoped(scope,args);String id="stream_recording".equals(kind)?result.optString("stream_id"):result.optString("recording_id");if(id.isEmpty())return;
        JSONObject c=new JSONObject().put("kind",kind).put("min_duration_seconds",Math.max(1,args.optInt("duration_seconds",60))).put("min_bytes",1024);if("stream_recording".equals(kind))c.put("stream_id",id);else{c.put("recording_id",id);if(args.has("expected_package"))c.put("package",args.getString("expected_package"));if(args.has("expected_text"))c.put("target_text",args.getString("expected_text"));}
        JSONArray previous=t.optJSONArray("criteria"),next=new JSONArray();for(int i=0;previous!=null&&i<previous.length();i++){JSONObject old=previous.optJSONObject(i);if(old!=null&&!kind.equals(old.optString("kind")))next.put(old);}next.put(c);
        t.put("criteria",next).put("progress_at",System.currentTimeMillis());long duration=Math.max(1,args.optInt("duration_seconds",60))*1000L;
        long end="stream_recording".equals(kind)?result.optLong("deadline_epoch_ms",System.currentTimeMillis()+duration):result.optLong("start_at_epoch_ms",System.currentTimeMillis())+duration;
        if(end>System.currentTimeMillis())t.put("deadline_at",Math.max(t.optLong("deadline_at"),end+5*60*1000L));
        boolean pending=recordingProgress(t).optBoolean("pending");t.put("status",pending?"waiting_event":"running");if(pending)t.put("wait_mode","durable_receipt");save(t);log(t,"recording_criterion_registered","Durable artifact receipt registered as a completion condition.",null);
    }
    static JSONArray normalizeRecordingCriteria(JSONArray source,String goal)throws Exception{boolean durable=false;for(int i=0;i<source.length();i++){String kind=source.getJSONObject(i).optString("kind");if("screen_recording".equals(kind)||"stream_recording".equals(kind))durable=true;}if(!durable||!goal.matches("(?is).*(录制|录屏|直播|record|stream).*"))return source;JSONArray out=new JSONArray();for(int i=0;i<source.length();i++){JSONObject c=source.getJSONObject(i);if(!java.util.Arrays.asList("foreground_app","ui_visible").contains(c.optString("kind")))out.put(c);}return out;}
    private JSONObject recordingProgress(JSONObject task)throws Exception{
        JSONArray criteria=task.optJSONArray("criteria");if(criteria==null)return new JSONObject().put("durable",false).put("pending",false);
        boolean durable=false,pending=false,failed=false;JSONArray states=new JSONArray();
        try{TaskVerifier.validate(criteria,task.optString("goal"));}catch(Exception incomplete){return new JSONObject().put("durable",false).put("pending",false).put("contract_error",incomplete.getMessage());}
        for(int i=0;i<criteria.length();i++){
            JSONObject c=criteria.getJSONObject(i);String kind=c.optString("kind"),state="";
            if("screen_recording".equals(kind)){durable=true;JSONObject observed=ScreenRecordingStore.status(context,c.optString("recording_id"));state=observed.optString("state");}
            else if("stream_recording".equals(kind)){durable=true;try{JSONObject observed=WorkbenchClient.get(context).call("stream_record_status",new JSONObject().put("stream_id",c.optString("stream_id")),new JSONObject().put("task_id",task.getString("id")));state=observed.optString("state");}catch(Exception unavailable){state="temporarily_unavailable";}}
            else continue;
            states.put(new JSONObject().put("kind",kind).put("state",state));
            if(java.util.Arrays.asList("awaiting_user_consent","starting","scheduled","waiting_for_live","recording","temporarily_unavailable").contains(state))pending=true;
            else if(!"completed".equals(state))failed=true;
        }
        return new JSONObject().put("durable",durable).put("pending",durable&&pending&&!failed).put("failed",failed).put("states",states);
    }
    private void reconcileCompletedRecordings()throws Exception{
        JSONArray active=store.tasks(null,true);for(int i=0;i<active.length();i++){
            JSONObject t=active.getJSONObject(i),progress=recordingProgress(t);if(!progress.optBoolean("durable"))continue;
            JSONObject verified=TaskVerifier.verify(context,t);t.put("last_verification",verified);
            if(verified.optBoolean("verified")){t.put("status","succeeded").put("summary",context.getString(R.string.task_recording_reconciled_summary)).put("finished_at",System.currentTimeMillis()).put("issues",new JSONArray());t.remove("end_reason");t.remove("wait_mode");save(t);log(t,"completed_recording_reconciled","Durable artifact receipts completed and the task was closed without model polling.",null);}
            else if(progress.optBoolean("pending")){t.put("status","waiting_event").put("model_wait_at",0).put("last_seen_at",System.currentTimeMillis());save(t);}
            else if(progress.optBoolean("failed")){t.put("status","diagnosing").put("model_wait_at",System.currentTimeMillis());issue(t,"recording_event_failed","A scheduled recording ended in a failure state. Resume from the saved receipts, diagnose the concrete error and repair the original task.");save(t);}
        }
        JSONArray recent=store.tasks(null,false);for(int i=0;i<recent.length();i++){JSONObject t=recent.getJSONObject(i);if("succeeded".equals(t.optString("status"))&&t.optBoolean("reconciled_from_failure")&&t.optJSONArray("issues")!=null&&t.getJSONArray("issues").length()>0){t.put("issues",new JSONArray());save(t);continue;}if(!"failed".equals(t.optString("status"))||!t.optString("end_reason").contains("tool budget exceeded"))continue;JSONArray old=t.optJSONArray("criteria"),durable=new JSONArray();for(int j=0;old!=null&&j<old.length();j++){JSONObject c=old.getJSONObject(j);if(java.util.Arrays.asList("screen_recording","stream_recording").contains(c.optString("kind")))durable.put(c);}if(durable.length()==0)continue;JSONObject probe=new JSONObject(t.toString()).put("criteria",durable);JSONObject verified=TaskVerifier.verify(context,probe);if(!verified.optBoolean("verified"))continue;t.put("criteria",durable).put("status","succeeded").put("summary",context.getString(R.string.task_recording_recovered_summary)).put("finished_at",System.currentTimeMillis()).put("last_verification",verified).put("reconciled_from_failure",true).put("issues",new JSONArray()).remove("end_reason");save(t);log(t,"completed_recording_reconciled","A completed durable recording was recovered from a false tool-budget failure.",null);}
    }
    public synchronized String resumeUrl() {
        try {
            JSONArray active=store.tasks(null,true);
            if(active.length()==0) {
                JSONArray recent=store.tasks(null,false);
                if(recent.length()>0&&java.util.Arrays.asList("blocked","failed").contains(recent.getJSONObject(0).optString("status")))active=recent;
            }
            if(active.length()>0) {
                String conversation=active.getJSONObject(0).optString("conversation");
                if(conversation.startsWith("c:")&&!conversation.startsWith("c:WEB:"))return "https://chatgpt.com/c/"+conversation.substring(2);
            }
        } catch(Exception ignored) {}
        return "https://chatgpt.com/";
    }
    public synchronized String notificationText() {
        try {
            JSONArray active=store.tasks(null,true);
            if(active.length()>0)return context.getString("waiting_provider".equals(active.getJSONObject(0).optString("status"))?R.string.agent_waiting_provider:R.string.agent_task_running);
            JSONArray recent=store.tasks(null,false);
            if(recent.length()>0&&java.util.Arrays.asList("failed","blocked").contains(recent.getJSONObject(0).optString("status")))return context.getString(R.string.agent_task_attention);
        } catch(Exception ignored) {}
        return context.getString(R.string.notification_text);
    }
    private void log(JSONObject t,String code,String message,Throwable error) { DetailedDiagnostics.event(context,error==null?"info":"error","task",t.optString("id"),"",code,message,error); }
}
