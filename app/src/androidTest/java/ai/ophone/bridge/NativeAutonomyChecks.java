package ai.ophone.bridge;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

/** Status reports from scripts and programs, ask_user pause/resume, step budget pause and long-term memory. */
public final class NativeAutonomyChecks {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static JSONObject a(Object... kv)throws Exception{JSONObject o=new JSONObject();for(int i=0;i+1<kv.length;i+=2)o.put((String)kv[i],kv[i+1]);return o;}
    private static JSONObject finished(WorkbenchClient w,JSONObject job,JSONObject sc)throws Exception{for(int i=0;i<300&&job.optLong("finished_at")==0;i++){Thread.sleep(50);job=w.call("terminal_status",a("job_id",job.getString("job_id")),sc);}return job;}

    public static JSONArray run(Context c,String suite)throws Exception{
        JSONArray reports=new JSONArray();TaskController controller=TaskController.get(c);LocalToolKernel kernel=new LocalToolKernel(c);WorkbenchClient w=WorkbenchClient.get(c);
        JSONObject t=controller.begin("c:"+suite+"-autonomy","human-autonomy","验证状态报告、提问和记忆");
        JSONObject sc=a("task_id",t.getString("id"),"conversation",t.getString("conversation"),"call_id","autonomy-test");
        try{
            // Shell status report line is parsed into the job receipt; malformed lines are ignored.
            JSONObject job=finished(w,w.call("terminal_start",a("command","echo '@@ophone:report {\"stage\":\"load\",\"message\":\"half\",\"progress\":0.5}'; echo '@@ophone:report not-json'; echo plain"),sc),sc);
            JSONArray r=job.optJSONArray("reports");
            check(job.optInt("exit_code",-1)==0&&r!=null&&r.length()==1&&"load".equals(r.getJSONObject(0).optString("stage"))&&r.getJSONObject(0).optDouble("progress")==0.5&&job.optInt("report_count")==1,"shell status report: "+job);
            reports.put("shell_status_report");

            JSONObject status=w.call("workbench_status",a(),sc).getJSONObject("python");
            if(status.optBoolean("available")){
                JSONObject py=finished(w,w.call("python_run",a("code","from ophone import report, step\nreport('a','start',progress=0)\ntry:\n  with step('b'):\n    raise ValueError('boom')\nexcept ValueError:\n  pass\nreport('c','done',progress=1)"),sc),sc);
                JSONArray pr=py.optJSONArray("reports");
                check(py.optInt("exit_code",-1)==0&&pr!=null&&pr.length()==4&&"error".equals(pr.getJSONObject(2).optString("level"))&&pr.getJSONObject(2).optString("message").contains("boom"),"python ophone helper reports: "+py);
                reports.put("python_status_report_helper");
            }

            // JS program report() is returned with the result.
            if(android.os.Build.VERSION.SDK_INT>=28){
                kernel.call("program_save",a("name","autonomy_report","source","report('sum','adding',0.5);return input.a+input.b;"),sc);
                JSONObject run=kernel.call("program_run",a("name","autonomy_report","input",a("a",2,"b",3)),sc);
                check(run.optBoolean("ok")&&run.optInt("value")==5&&run.optJSONArray("reports")!=null&&run.getJSONArray("reports").length()==1,"program report: "+run);
                reports.put("program_status_report");
            }

            // ask_user parks the task; the next message answers it and continues the same task instead of superseding it.
            JSONObject asked=kernel.call("ask_user",a("question","Which account?","options",new JSONArray().put("Work").put("Personal")),sc);
            check(asked.optBoolean("waiting_for_user")&&"waiting_user".equals(controller.status(a("conversation",t.getString("conversation"),"task_id",t.getString("id")),a()).getJSONObject("task").optString("status")),"ask_user did not park task: "+asked);
            check(controller.claimDirectResume(t.getString("conversation"))==null,"waiting_user task auto-resumed");
            JSONObject answered=controller.bridge("observe_request",a("conversation",t.getString("conversation"),"human_key","human-answer","goal","Personal","guidance_if_active",true));
            check(answered.optBoolean("answered_question")&&t.getString("id").equals(answered.getJSONObject("task").getString("id"))&&"running".equals(answered.getJSONObject("task").optString("status")),"answer superseded the task: "+answered);
            reports.put("ask_user_pause_and_resume");

            // Long-term memory: save, search (CJK pairs and app bonus), update, delete.
            JSONObject saved=kernel.call("memory_save",a("kind","playbook","title","设置：查看电池电量","text","打开设置 → 电池；读取百分比文字。","app","com.android.settings","tags",new JSONArray().put("battery")),sc);
            check(saved.optBoolean("ok"),"memory save: "+saved);
            JSONObject found=kernel.call("memory_search",a("query","查看电量","app","com.android.settings"),sc);
            check(found.getJSONArray("results").length()>=1&&saved.getString("id").equals(found.getJSONArray("results").getJSONObject(0).getString("id")),"memory search: "+found);
            check(kernel.call("memory_save",a("id",saved.getString("id"),"kind","playbook","title","设置：查看电池电量","text","updated"),sc).optBoolean("updated"),"memory update");
            boolean rejected=false;try{kernel.call("memory_save",a("kind","secret","title","x","text","y"),sc);}catch(Exception expected){rejected=true;}check(rejected,"invalid memory kind accepted");
            check(kernel.call("memory_delete",a("id",saved.getString("id")),sc).optBoolean("deleted"),"memory delete");
            reports.put("memory_save_search_update_delete");

            // System interface: structured state, app manual, and the intent allowlist.
            JSONObject info=kernel.call("android_info",a("sections",new JSONArray().put("battery").put("locale")),sc);
            check(info.optBoolean("ok")&&info.getJSONObject("battery").has("percent")&&!info.has("audio"),"android_info sections: "+info);
            JSONObject manual=kernel.call("android_resolve",a("package",c.getPackageName()),sc);
            check(manual.optBoolean("ok")&&manual.optString("launch_activity").length()>0,"android_resolve manual: "+manual);
            boolean call=false;try{AndroidSystemTools.build(c,a("action","android.intent.action.CALL","data","tel:10086"));}catch(IllegalArgumentException expected){call=true;}
            boolean scheme=false;try{AndroidSystemTools.build(c,a("data","weixin://dl/scan"));}catch(IllegalArgumentException expected){scheme=true;}
            check(call&&scheme,"intent allowlist accepted CALL or an unscoped custom scheme");
            check(AndroidSystemTools.build(c,a("action","android.intent.action.SEND","type","text/plain","extras",a("android.intent.extra.TEXT","hi"))).getStringExtra("android.intent.extra.TEXT").equals("hi"),"intent extras");
            reports.put("android_system_interface");

            // Mini apps: save, forbidden permissions, re-approval on permission change, read, list, delete.
            String html="<!doctype html><html><head><title>t</title></head><body><script>OPhone.call('android_info',{});</script></body></html>";
            JSONObject mini=kernel.call("miniapp_save",a("name","check-app","title","Check","html",html,"permissions",new JSONArray().put("android_info")),sc);
            check(mini.optBoolean("ok")&&mini.optBoolean("needs_user_approval"),"miniapp save: "+mini);
            boolean forbidden=false;try{kernel.call("miniapp_save",a("name","bad-app","title","Bad","html",html,"permissions",new JSONArray().put("install_apk")),sc);}catch(Exception expected){forbidden=true;}
            check(forbidden,"miniapp accepted a forbidden tool");
            JSONObject meta=MiniAppStore.meta(c,"check-app");meta.put("approved",true);MiniAppStore.saveMeta(c,meta);
            check(!kernel.call("miniapp_save",a("name","check-app","title","Check","html",html,"permissions",new JSONArray().put("android_info").put("python_run")),sc).optBoolean("approved",false)&&!MiniAppStore.meta(c,"check-app").optBoolean("approved"),"permission change kept old approval");
            check(kernel.call("miniapp_read",a("name","check-app"),sc).getString("html").equals(html),"miniapp read");
            check(kernel.call("miniapp_list",a(),sc).getJSONArray("apps").toString().contains("check-app"),"miniapp list");
            MiniAppStore.storageSet(c,"check-app","k","v");check("v".equals(MiniAppStore.storageGet(c,"check-app","k")),"miniapp storage");
            check(kernel.call("miniapp_delete",a("name","check-app"),sc).optBoolean("deleted"),"miniapp delete");
            reports.put("miniapp_store_and_permissions");
            check(UpdateManager.newer("v1.10.0","1.9.3")&&!UpdateManager.newer("v1.0.0","1.5.0")&&!UpdateManager.newer("1.5.0","1.5.0")&&UpdateManager.newer("2.0","1.99.99"),"release version comparison");
            String leaky="{\"model\":\"X1\",\"sdk_int\":35,\"locale\":\"zh-CN\",\"ok\":true} at 39.904211, 116.407395 via 10.0.0.2 on "+android.os.Build.MODEL+" {\"data\":\"{\\\"android_version\\\":\\\"14\\\"}\"}";
            String clean=FeedbackActivity.scrub(leaky);
            check(!clean.contains("X1")&&!clean.contains("35")&&!clean.contains("zh-CN")&&!clean.contains("39.904211")&&!clean.contains("10.0.0.2")&&!clean.contains("\\\"14\\\"")&&(android.os.Build.MODEL.length()<3||!clean.contains(android.os.Build.MODEL))&&clean.contains("\"ok\":true"),"feedback logs kept device, locale, location or IP data: "+clean);
            reports.put("update_version_compare");
        }finally{try{controller.finish(sc,a("outcome","cancelled","reason","Controlled autonomy test completed."));}catch(Exception ignored){}}
        return reports;
    }
}
