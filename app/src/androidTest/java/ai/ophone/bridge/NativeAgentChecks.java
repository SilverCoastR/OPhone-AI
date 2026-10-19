package ai.ophone.bridge;

import android.app.Instrumentation;
import android.os.Bundle;
import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

/** Dependency-free on-device tests of the real SQLite/controller/verifier. No user UI actions. */
public final class NativeAgentChecks extends Instrumentation {
    private int passed;
    private Context context;
    private TaskController controller;
    private AgentStore store;
    private final String suite="native-check-"+System.currentTimeMillis();
    private interface Check { void run() throws Exception; }
    private boolean workbenchOnly;
    @Override public void onCreate(Bundle args) { super.onCreate(args);workbenchOnly=args!=null&&"true".equals(args.getString("workbenchOnly"));start(); }
    private void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
    private JSONObject task(String suffix,String goal) throws Exception { return controller.begin("c:"+suite+"-"+suffix,"human-"+suffix,goal); }
    private JSONObject scope(JSONObject t,String call) throws Exception { return new JSONObject().put("conversation",t.getString("conversation")).put("task_id",t.getString("id")).put("call_id",call); }
    private JSONObject condition(String kind) throws Exception { return new JSONObject().put("kind",kind); }
    private JSONObject verify(JSONObject sc,JSONObject args,boolean finish) throws Exception {
        JSONObject result=controller.verify(sc,args,finish);
        // Exercise the bridge serialization, not only fields on an in-memory object.
        JSONObject wire=new JSONObject(result.toString());
        check(wire.getJSONObject("task").getJSONObject("last_verification").has("evidence"),"verification evidence missing on wire");
        return wire;
    }
    private void criteria(JSONObject t,JSONObject value) throws Exception { controller.criteria(scope(t,"criteria"),new JSONObject().put("criteria",new JSONArray().put(value))); }
    private void expectsFailure(Check fn,String message) throws Exception { boolean failed=false;try{fn.run();}catch(Exception expected){failed=true;}check(failed,message); }
    private void journalRetention(String tag) throws Exception {
        java.io.File fixture=new java.io.File(context.getCacheDir(),suite+"-"+tag+"-journal");
        java.io.File receipts=new java.io.File(fixture,"execution_receipts");receipts.mkdirs();
        Context isolated=new android.content.ContextWrapper(context) {
            @Override public java.io.File getFilesDir(){return fixture;}
        };
        try {
            for(int i=0;i<100;i++)try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(receipts,"old-marker-"+i+".json"))) {
                out.write("{\"state\":\"expired\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            JSONObject first=new JSONObject().put("conversation","c:"+suite+"-"+tag).put("call_id","retention-first");
            JSONObject second=new JSONObject(first.toString()).put("call_id","retention-second");
            JSONObject params=new JSONObject().put("name","device_status").put("arguments",new JSONObject());
            check(ExecutionJournal.begin(isolated,first,params)==null,"new isolated receipt did not start");
            String response="{\"result\":{\"content\":[]}}";
            ExecutionJournal.finish(isolated,first,response);
            check(ExecutionJournal.begin(isolated,second,params)==null,"second isolated receipt did not start");
            ExecutionJournal.finish(isolated,second,response);
            check("done".equals(ExecutionJournal.receipt(isolated,first).optString("state")),"old tombstones evicted the immediately preceding receipt");
        } finally {
            java.io.File[] files=receipts.listFiles();if(files!=null)for(java.io.File file:files)file.delete();
            receipts.delete();fixture.delete();
        }
    }
    private void cleanTasks() {
        try {
            JSONArray tasks=store.tasks(null,true);
            for(int i=0;i<tasks.length();i++) {
                JSONObject t=tasks.getJSONObject(i);
                if(t.getString("conversation").startsWith("c:"+suite))controller.finish(scope(t,"cleanup"),new JSONObject().put("outcome","cancelled").put("reason","Native test cleanup; no Android UI action was performed."));
            }
        } catch(Exception ignored) {}
    }
    @Override public void onStart() {
        Bundle result=new Bundle();context=getTargetContext();controller=TaskController.get(context);store=AgentStore.get(context);
        try {
            for(int round=0;round<(workbenchOnly?0:3);round++)for(int type=0;type<30;type++) {
                String tag="r"+round+"-t"+type;JSONObject t=task(tag,type==3||type==4?"安装指定应用":type==5?"打开指定应用":"查询当前设备状态");
                JSONObject sc=scope(t,"call-"+tag);
                switch(type) {
                    case 0:
                        check(controller.begin(t.getString("conversation"),t.getString("human_key"),t.getString("goal")).getString("id").equals(t.getString("id")),"same origin duplicated task");break;
                    case 1:
                        check(store.task(t.getString("id")).getString("goal").equals(t.getString("goal")),"original goal not persisted");break;
                    case 2:
                        check(!verify(sc,new JSONObject(),true).optBoolean("verified"),"missing criteria accepted");break;
                    case 3:
                        expectsFailure(()->criteria(t,condition("device_property").put("field","model")),"installation completed with unrelated evidence");break;
                    case 4:
                        criteria(t,condition("app_installed").put("package",context.getPackageName()));
                        check(verify(sc,new JSONObject(),true).optBoolean("verified"),"real installed package not verified");break;
                    case 5:
                        expectsFailure(()->criteria(t,condition("app_installed").put("package",context.getPackageName())),"open goal accepted mere installation");break;
                    case 6:
                        criteria(t,condition("app_installed").put("package","com.ophoneai.nonexistent.test.package"));
                        check(!verify(sc,new JSONObject(),true).optBoolean("verified"),"absent package verified");break;
                    case 7:
                        criteria(t,condition("device_property").put("field","model").put("equals",android.os.Build.MODEL));
                        check(verify(sc,new JSONObject(),true).optBoolean("verified"),"fresh device fact failed");break;
                    case 8:
                        criteria(t,condition("device_property").put("field","model").put("equals","definitely-not-this-phone"));
                        check(!verify(sc,new JSONObject(),true).optBoolean("verified"),"wrong fact accepted");break;
                    case 9:
                        expectsFailure(()->controller.status(new JSONObject(sc.toString()).put("conversation","c:other"),new JSONObject()),"cross-conversation task access accepted");break;
                    case 10:
                        JSONObject next=controller.begin(t.getString("conversation"),"new-human","另一个新任务");
                        check("cancelled".equals(store.task(t.getString("id")).getString("status")),"new user did not supersede old task");
                        controller.finish(scope(next,"end"),new JSONObject().put("outcome","cancelled").put("reason","Native test cleanup of superseding request."));break;
                    case 11:
                        JSONObject continued=controller.begin(t.getString("conversation"),"resume-human","继续");
                        check(continued.getString("id").equals(t.getString("id"))&&continued.getString("goal").equals(t.getString("goal")),"continue lost original goal");break;
                    case 12:
                        expectsFailure(()->criteria(t,condition("arbitrary_shell")),"unsupported verifier accepted");break;
                    case 13:
                        expectsFailure(()->controller.finish(sc,new JSONObject().put("outcome","blocked").put("reason","no")),"empty blocker accepted");break;
                    case 14:
                        DetailedDiagnostics.event(context,"error","test",t.getString("id"),"error-call","sample_error","token=do-not-leak",new IllegalStateException("password=do-not-leak"));
                        JSONObject query=store.logs(new JSONObject().put("task_id",t.getString("id")).put("level","error"));
                        check(query.getJSONArray("events").length()==1,"task-scoped detailed log missing");
                        JSONObject entry=store.log(query.getJSONArray("events").getJSONObject(0).getLong("id")).getJSONObject("entry");
                        check(entry.getString("stack").contains("NativeAgentChecks")&&!entry.toString().contains("do-not-leak"),"stack missing or secret leaked");
                        String sanitized=DetailedDiagnostics.clean("Authorization: Bearer do-not-leak-auth\nCookie: first=do-not-leak-cookie; second=do-not-leak-second\n{\"password\":\"do not leak multiple words\"}\nBearer do-not-leak-bearer");
                        check(!sanitized.contains("do-not-leak")&&!sanitized.contains("multiple words"),"multi-part credential redaction failed");break;
                    case 15:
                        long id=store.addLog("info","test",t.getString("id"),"","cursor_event","cursor check","");
                        check(store.logs(new JSONObject().put("task_id",t.getString("id")).put("after_id",id-1)).getJSONArray("events").getJSONObject(0).getLong("id")==id,"diagnostic cursor failed");break;
                    case 16:
                        controller.beforeTool(sc,"device_status",new JSONObject());
                        controller.afterTool(sc,"device_status",new JSONObject().put("ok",true),null);
                        check(!store.operation(t.getString("id"),sc.getString("call_id")).optBoolean("feedback_delivered"),"feedback prematurely acknowledged");
                        controller.bridge("feedback",new JSONObject().put("conversation",t.getString("conversation")).put("task_id",t.getString("id")).put("call_id",sc.getString("call_id")));
                        check(store.task(t.getString("id")).isNull("pending_call"),"native outbox not acknowledged");break;
                    case 17:
                        JSONObject args=new JSONObject().put("package","com.example.target");
                        controller.beforeTool(sc,"launch_app",args);
                        JSONObject op=store.operation(t.getString("id"),sc.getString("call_id"));op.put("status","unknown");store.putOperation(op);
                        expectsFailure(()->controller.beforeTool(scope(t,"different-id"),"launch_app",args),"different call id bypassed unknown-effect guard");break;
                    case 18:
                        criteria(t,condition("download_saved").put("file_id","../../outside"));
                        check(!verify(sc,new JSONObject(),true).optBoolean("verified"),"out-of-scope file verified");break;
                    case 19:
                        controller.finish(sc,new JSONObject().put("outcome","failed").put("reason","Explicit native test failure retained for diagnostics."));
                        check("failed".equals(store.task(t.getString("id")).getString("status")),"failure not durable");
                        expectsFailure(()->controller.beforeTool(scope(t,"after-finish"),"launch_app",new JSONObject().put("package",context.getPackageName())),"terminal task executed new action");break;
                    case 20:
                        criteria(t,condition("device_property").put("field","model"));
                        controller.bridge("note_transport_recovery",new JSONObject().put("conversation",t.getString("conversation")).put("task_id",t.getString("id")));
                        check(!verify(sc,new JSONObject(),true).optBoolean("verified"),"uninspected recovery completed task");
                        for(String tool:new String[]{"runtime_status","runtime_logs"}) {
                            JSONObject diagnosticScope=scope(t,"diag-"+tool);controller.beforeTool(diagnosticScope,tool,new JSONObject());
                            controller.afterTool(diagnosticScope,tool,new JSONObject().put("ok",true),null);
                        }
                        check(verify(sc,new JSONObject(),true).optBoolean("verified"),"fresh diagnostic calls did not release completion");break;
                    case 21:
                        criteria(t,condition("device_property").put("field","model"));
                        controller.reportRuntimeFault(t.getString("id"),"sample_runtime_fault","Test diagnostic obligation survives page loss.");
                        check(store.task(t.getString("id")).getJSONArray("diagnostic_checks").length()==2,"diagnostic obligations not durable");
                        check(!verify(sc,new JSONObject(),true).optBoolean("verified"),"runtime error silently bypassed diagnostics");break;
                    case 22:
                        journalRetention(tag);break;
                    case 23:
                        JSONObject limited=store.task(t.getString("id"));limited.put("action_count",48);store.putTask(limited);JSONObject budgetRead=scope(t,"budget-read");controller.beforeTool(budgetRead,"task_status",new JSONObject());controller.afterTool(budgetRead,"task_status",new JSONObject().put("ok",true),null);
                        expectsFailure(()->controller.beforeTool(sc,"launch_app",new JSONObject().put("package",context.getPackageName())),"page reload bypassed native task budget");
                        check("failed".equals(store.task(t.getString("id")).getString("status")),"exhausted task lacked durable failure report");break;
                    case 24:
                        criteria(t,condition("device_property").put("field","model"));
                        controller.finish(sc,new JSONObject().put("outcome","blocked").put("reason","Temporary Android connection blocker observed by native test."));
                        JSONObject resumed=controller.bridge("observe_request",new JSONObject().put("conversation",t.getString("conversation")).put("human_key","resume-blocked").put("goal","继续")).getJSONObject("task");
                        check(resumed.getString("goal").equals(t.getString("goal"))&&resumed.getString("resumed_from").equals(t.getString("id")),"blocked continuation lost original goal");
                        check(resumed.getJSONArray("criteria").length()==1&&resumed.getInt("tool_count")==0&&resumed.getInt("action_count")==0,"resumed episode lost conditions or budget");
                        expectsFailure(()->controller.beforeTool(scope(resumed,"unobserved-action"),"launch_app",new JSONObject().put("package",context.getPackageName())),"resumed mutation skipped actual observation");
                        JSONObject readScope=scope(resumed,"fresh-observation");controller.beforeTool(readScope,"find_apps",new JSONObject());
                        controller.afterTool(readScope,"find_apps",new JSONObject().put("ok",true),null);
                        controller.beforeTool(scope(resumed,"observed-action"),"launch_app",new JSONObject().put("package",context.getPackageName()));break;
                    case 25:
                        controller.finish(sc,new JSONObject().put("outcome","failed").put("reason","Native test prior attempt reached its bounded deadline."));
                        JSONObject retry=controller.begin(t.getString("conversation"),"resume-failed","continue");
                        check(retry.getString("goal").equals(t.getString("goal"))&&!retry.getString("id").equals(t.getString("id")),"failed continuation lost origin or reused terminal identity");
                        check(retry.getLong("deadline_at")>retry.getLong("created_at")&&retry.getJSONArray("diagnostic_checks").length()==2,"retry lost bounded diagnostics/deadline");break;
                    case 26:
                        JSONObject origin=new JSONObject().put("conversation",t.getString("conversation")).put("human_key","first-turn-human").put("goal","打开应用商店");
                        JSONObject observed=controller.bridge("observe_request",origin).getJSONObject("task");
                        check(store.operations(observed.getString("id")).length()==0,"request observation executed Android tool");
                        observed.put("model_wait_at",System.currentTimeMillis()-50000).put("last_seen_at",System.currentTimeMillis());store.putTask(observed);
                        controller.supervise();
                        JSONObject waiting=store.task(observed.getString("id"));
                        check(waiting.getJSONArray("issues").length()>0&&waiting.getJSONArray("diagnostic_checks").length()==2,"no-first-response task was not independently supervised");break;
                    case 27:
                        JSONObject ordinary=controller.bridge("observe_request",new JSONObject().put("conversation",t.getString("conversation")).put("human_key","ordinary-human").put("goal","你好，聊聊今天的想法"));
                        check(!ordinary.optBoolean("managed")&&store.byHuman(t.getString("conversation"),"ordinary-human")==null,"ordinary chat became a phone task");break;
                    case 28:
                        JSONObject thinking=store.task(t.getString("id"));thinking.put("model_wait_at",System.currentTimeMillis()-50000).put("last_seen_at",System.currentTimeMillis());store.putTask(thinking);
                        RuntimeDiagnostics.observe(new JSONObject().put("conversation",t.getString("conversation")).put("model_generating",true));controller.supervise();
                        check(store.task(t.getString("id")).optInt("wake_count")==0,"native supervision interrupted a generating model");
                        RuntimeDiagnostics.observe(new JSONObject().put("conversation",t.getString("conversation")).put("model_generating",true).put("model_transport_error","connection_interrupted"));controller.supervise();
                        check(store.task(t.getString("id")).optInt("wake_count")==1,"explicit transport failure was hidden by a stale generation button");
                        RuntimeDiagnostics.observe(new JSONObject());break;
                    case 29:
                        JSONObject silent=store.task(t.getString("id"));silent.put("model_wait_at",System.currentTimeMillis()-50000).put("last_seen_at",System.currentTimeMillis()).put("wake_count",3).put("provider_reload_count",2);store.putTask(silent);
                        controller.supervise();check("failed".equals(store.task(t.getString("id")).getString("status")),"provider recovery exceeded bounded budget");break;
                }
                passed++; cleanTasks();store.removeNativeTestFixtures("c:"+suite);
            }
            if(!workbenchOnly){JSONArray eventPrograms=NativeEventProgramChecks.run(context,suite);passed+=eventPrograms.length();result.putString("event_program_scenarios",eventPrograms.toString());}
            JSONArray workbench=NativeWorkbenchChecks.run(context,suite);passed+=workbench.length();result.putString("workbench_scenarios",workbench.toString());
            JSONArray rootChecks=NativeRootChecks.run(context,suite);passed+=rootChecks.length();result.putString("root_scenarios",rootChecks.toString());
            JSONArray apiChecks=NativeApiChecks.run(context,suite);passed+=apiChecks.length();result.putString("api_scenarios",apiChecks.toString());
            result.putInt("passed",passed);result.putString("result","Native task/verifier/diagnostic/event/program/workbench checks passed; not real model task count.");finish(android.app.Activity.RESULT_OK,result);
        } catch(Throwable error) {
            cleanTasks();store.removeNativeTestFixtures("c:"+suite);result.putInt("passed",passed);result.putString("failure",DetailedDiagnostics.stack(error));finish(android.app.Activity.RESULT_CANCELED,result);
        }
    }
}
