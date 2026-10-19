package ai.ophone.bridge;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Locale;

/** Completion comes from fresh Android observations, never from a final chat sentence. */
public final class TaskVerifier {
    private TaskVerifier() {}
    public static String intent(String goal) {
        String g=goal==null?"":goal.trim().toLowerCase(Locale.ROOT);
        if(g.isEmpty())return "general";
        // Pure negations and informational questions are conversation. Ambiguous
        // requests can still escalate through the model-visible capabilities probe.
        if(g.matches("(?s)^\\s*(请|帮我|请帮我)?(不要|别).*|(?s)^\\s*(please\\s+)?(don't|do\\s+not|not|without)\\b.*"))return "general";
        boolean requestFrame=g.matches("(?s)^\\s*(请|请你|帮我|请帮我|麻烦|我要你|我需要你|你来|替我|给我|去|现在|立即|马上|继续|接着|重新|改为|改成|换成|把|将|让|计划|定时|监控|等待).*")
                ||g.matches("(?s)^\\s*(please|can\\s+you|could\\s+you|would\\s+you|i\\s+(?:want|need)\\s+you\\s+to|help\\s+me|continue|resume|start|change|switch|replace|schedule|monitor|watch|record|search|find|enter|tap|click|swipe|type|play|pause|send|share|save|use|run|execute|create)\\b.*");
        boolean explicitDirective=g.matches("(?s).*(现在开始|立即开始|重新开始|改为|改成|换成|换为|你得|需要你|你自己|自己去).*")
                ||g.matches("(?s).*(start\\s+now|change\\s+(?:the\\s+)?target|switch\\s+(?:the\\s+)?target|start\\s+over|you\\s+need\\s+to).*");
        boolean question=g.endsWith("?")||g.endsWith("？")||g.matches("(?s)^\\s*(什么|为什么|怎么|如何|是否|有没有|能否|可以吗|请问|what|why|how|is\\s+there|are\\s+there|can\\s+i|could\\s+i).*");
        if(question&&!requestFrame)return "general";

        if(g.matches("(?s)^\\s*(请|请你|帮我|请帮我|麻烦|我要你|我需要你|替我|给我|去|继续|接着|重新|现在|立即|马上)?\\s*(下载|安装).*")
                ||g.matches("(?s)^\\s*(please\\s+|can\\s+you\\s+|could\\s+you\\s+|continue\\s+|resume\\s+|start\\s+)?(install|download)\\b.*")
                ||g.matches("(?s)^\\s*(continue|resume|finish)\\b.{0,200}\\b(install|download)\\b.*"))return "install_or_download";
        if(g.matches("(?s)^\\s*(请|请你|帮我|请帮我|麻烦|我要你|我需要你|替我|给我|再次|重新|现在|立即|马上)?\\s*(打开|启动).*")
                ||g.matches("(?s)^\\s*(please\\s+|can\\s+you\\s+|could\\s+you\\s+)?(open|launch)\\b.*"))return "open_app";
        if(g.matches("(?s).*(在|用|使用|通过).{0,30}(浏览器|网页|网站|终端|命令行|命令|脚本|接口|工作区|文件|shell|terminal|browser|http|workbench).*")
                ||g.matches("(?s)^\\s*(please\\s+|can\\s+you\\s+|could\\s+you\\s+)?(use|run|execute|fetch|visit|browse)\\b.*(browser|shell|terminal|http|workbench|script|file).*"))return "workbench";

        boolean deviceContext=g.matches("(?s).*(shouji\\s*ai|这台手机|当前手机|安卓|android|手机里|手机上|应用里|app里|当前页面|系统设置|无障碍|内置工具|内置浏览器|本地文件|相册|屏幕|摄像头|麦克风|通知栏).*");
        boolean actionVerb=g.matches("(?s).*(完成|解决|修复|处理|测试|验证|配置|设置|开启|关闭|清理|删除|修改|创建|运行|执行|访问|连接|上传|发布|同步|导出|导入|截取|复制|粘贴|登录|退出|搜索|查找|进入|点击|滑动|输入|播放|暂停|发送|分享|保存|录制|录屏|录像|直播|监控|等待|方式录).*")
                ||g.matches("(?s).*\\b(complete|solve|fix|handle|test|verify|configure|enable|disable|clean|delete|modify|create|run|execute|visit|connect|upload|publish|sync|export|import|capture|copy|paste|sign\\s+in|log\\s+out|search|find|enter|tap|click|swipe|type|play|pause|send|share|save|record|schedule|monitor|watch|wait|call)\\b.*");
        boolean contentOnly=g.matches("(?s)^\\s*(请|请你|帮我|请帮我|麻烦|给我)?\\s*(解释|翻译|总结|改写|写一首|写一段|介绍|比较|回答).*")
                ||g.matches("(?s)^\\s*(please\\s+|can\\s+you\\s+|could\\s+you\\s+)?(explain|translate|summarize|rewrite|write\\s+(?:a|an|the)\\s+(?:poem|story|paragraph)|compare|answer)\\b.*");
        if((requestFrame||explicitDirective||deviceContext)&&actionVerb&&(!contentOnly||deviceContext))return "phone_automation";
        if(requestFrame&&deviceContext&&!contentOnly)return "phone_automation";
        return "general";
    }
    public static void validate(JSONArray criteria,String goal) throws Exception {
        if(criteria==null||criteria.length()==0||criteria.length()>8) throw new IllegalArgumentException("one_to_eight_completion_criteria_required");
        boolean install=false,download=false,foreground=false,rootEvidence=false,screenRecording=false,streamRecording=false;
        for(int i=0;i<criteria.length();i++) {
            JSONObject c=criteria.getJSONObject(i); String kind=c.optString("kind");
            if("app_installed".equals(kind)) { identity(c); install=true; }
            else if("foreground_app".equals(kind)) { identity(c); foreground=true; }
            else if("ui_visible".equals(kind)) {
                identity(c); String text=c.optString("text");
                if(text.trim().isEmpty()||text.length()>300) throw new IllegalArgumentException("visible_text_required");
                foreground=true;
            } else if("download_saved".equals(kind)) {
                if(c.optString("file_id").isEmpty()) throw new IllegalArgumentException("download_file_id_required"); download=true;
            } else if("program_result".equals(kind)) {
                if(c.optString("run_id").isEmpty())throw new IllegalArgumentException("program_run_id_required");
            } else if("workspace_file".equals(kind)) {
                if(c.optString("name").isEmpty())throw new IllegalArgumentException("workspace_file_name_required");
            } else if("browser_page".equals(kind)) {
                if(c.optString("url").isEmpty())throw new IllegalArgumentException("browser_expected_url_required");
                if(goal.matches("(?is).*(https?://|网页|网站|浏览器|\\bwebsite\\b|\\bwebpage\\b|\\bbrowser\\b).*"))foreground=true;
            } else if("terminal_job".equals(kind)) {
                if(c.optString("job_id").isEmpty())throw new IllegalArgumentException("terminal_job_id_required");
                rootEvidence=rootEvidence||c.optBoolean("root",false);
            } else if("workbench_file".equals(kind)) {
                if(c.optString("path").isEmpty())throw new IllegalArgumentException("workbench_file_path_required");download=true;
            } else if("screen_recording".equals(kind)) {
                screenRecording=true;
                if(c.optString("recording_id").isEmpty())throw new IllegalArgumentException("recording_id_required");
                int seconds=c.optInt("min_duration_seconds",1);if(seconds<1||seconds>180)throw new IllegalArgumentException("recording_duration_out_of_range");
            } else if("stream_recording".equals(kind)) {
                streamRecording=true;
                if(c.optString("stream_id").isEmpty())throw new IllegalArgumentException("stream_id_required");
                int seconds=c.optInt("min_duration_seconds",1);if(seconds<1||seconds>180)throw new IllegalArgumentException("stream_duration_out_of_range");
            } else if("device_property".equals(kind)) {
                if(!java.util.Arrays.asList("battery_percent","model","accessibility","automation_enabled").contains(c.optString("field"))) throw new IllegalArgumentException("unsupported_device_property");
            } else throw new IllegalArgumentException("unsupported_completion_criterion");
        }
        String type=intent(goal);
        if("install_or_download".equals(type)&&!install&&!download) throw new IllegalArgumentException("download_or_install_goal_requires_file_or_installed_app_evidence");
        if("install_or_download".equals(type)&&goal.matches("(?is).*(安装|\\binstall\\b).*")&&!install) throw new IllegalArgumentException("installation_goal_requires_installed_app_evidence");
        if("open_app".equals(type)&&!foreground) throw new IllegalArgumentException("open_goal_requires_foreground_or_visible_ui_evidence");
        boolean recordingGoal=goal.matches("(?is).*(录制|录屏|直播|record|stream).*");
        boolean dualRecording=goal.matches("(?is).*(两种(?:方式)?|两份|两个(?:文件|录像)?|both|two\\s+(?:methods|ways|files|recordings)).*");
        if(recordingGoal&&dualRecording&&(!screenRecording||!streamRecording))throw new IllegalArgumentException("dual_recording_goal_requires_screen_and_stream_receipts");
        if(goal.toLowerCase(Locale.ROOT).matches("(?s)^(请|帮我|请帮我)?(用|使用|以)\\s*root(?:\\b|(?=权限|命令|shell|终端|模式|执行|运行|$)).*|^(please\\s+)?(use|execute|run)\\s+(the\\s+|a\\s+)?root\\b.*|^(please\\s+)?(execute|run)\\b.*\\bas\\s+root\\b.*")&&!rootEvidence)throw new IllegalArgumentException("root_command_goal_requires_uid_zero_terminal_job_evidence");
    }
    private static void identity(JSONObject c) {
        String p=c.optString("package"),n=c.optString("app_name");
        if(p.isEmpty()&&n.trim().isEmpty()) throw new IllegalArgumentException("package_or_app_name_required");
        if(!p.isEmpty()&&!p.matches("[A-Za-z0-9_.]{1,200}")) throw new IllegalArgumentException("invalid_package");
        if(n.length()>200) throw new IllegalArgumentException("invalid_app_name");
    }
    private static String norm(String s) { return Normalizer.normalize(s,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+",""); }
    private static boolean jsonEqual(Object a,Object b)throws Exception{
        if(a instanceof JSONObject&&b instanceof JSONObject){JSONObject x=(JSONObject)a,y=(JSONObject)b;if(x.length()!=y.length())return false;java.util.Iterator<String> keys=x.keys();while(keys.hasNext()){String key=keys.next();if(!y.has(key)||!jsonEqual(x.get(key),y.get(key)))return false;}return true;}
        if(a instanceof JSONArray&&b instanceof JSONArray){JSONArray x=(JSONArray)a,y=(JSONArray)b;if(x.length()!=y.length())return false;for(int i=0;i<x.length();i++)if(!jsonEqual(x.get(i),y.get(i)))return false;return true;}
        if(a instanceof Number&&b instanceof Number)return Double.compare(((Number)a).doubleValue(),((Number)b).doubleValue())==0;
        return a==null?b==null:a.equals(b);
    }
    static String resolvePackage(Context context,JSONObject criterion) throws Exception {
        String explicit=criterion.optString("package"); if(!explicit.isEmpty()) return explicit;
        String query=norm(criterion.optString("app_name"));
        PackageManager pm=context.getPackageManager(); ArrayList<String> exact=new ArrayList<>(),partial=new ArrayList<>();
        for(ApplicationInfo app:pm.getInstalledApplications(0)) {
            String label=norm(String.valueOf(pm.getApplicationLabel(app)));
            if(label.equals(query)||norm(app.packageName).equals(query)) exact.add(app.packageName);
            else if(query.length()>=2&&(label.contains(query)||norm(app.packageName).contains(query))) partial.add(app.packageName);
        }
        ArrayList<String> matches=exact.isEmpty()?partial:exact;
        if(matches.size()>1) throw new IllegalArgumentException("app_name_ambiguous_use_exact_package");
        return matches.isEmpty()?"":matches.get(0);
    }
    public static JSONObject verify(Context context,JSONObject task) throws Exception {
        JSONArray criteria=task.optJSONArray("criteria"),evidence=new JSONArray(); boolean passed=true;
        if(criteria==null||criteria.length()==0) return new JSONObject().put("ok",true).put("verified",false).put("error","completion_criteria_missing").put("evidence",evidence);
        validate(criteria,task.getString("goal"));
        for(int i=0;i<criteria.length();i++) {
            JSONObject c=criteria.getJSONObject(i),e=new JSONObject().put("kind",c.getString("kind")).put("checked_at",System.currentTimeMillis());
            boolean matches=false;
            try {
                String kind=c.getString("kind");
                if("app_installed".equals(kind)) {
                    String pkg=resolvePackage(context,c); e.put("package",pkg);
                    if(!pkg.isEmpty()) {
                        PackageInfo p=context.getPackageManager().getPackageInfo(pkg,0);
                        matches=true; e.put("installed",true).put("version",p.versionName).put("launchable",context.getPackageManager().getLaunchIntentForPackage(pkg)!=null);
                        if(c.optBoolean("launchable",false)) matches=context.getPackageManager().getLaunchIntentForPackage(pkg)!=null;
                    } else e.put("installed",false);
                } else if("foreground_app".equals(kind)||"ui_visible".equals(kind)) {
                    String pkg=resolvePackage(context,c); e.put("expected_package",pkg);
                    if(!RiskPolicy.automationEnabled(context)) throw new SecurityException("automation_disabled");
                    if(pkg.isEmpty()||!AppControlPolicy.isAllowed(context,pkg)) throw new SecurityException("target_not_available_or_not_allowed");
                    String foreground=DeviceAutomationService.getInstance()==null?"":DeviceAutomationService.getInstance().currentPackageName();
                    if(!AppControlPolicy.isAllowed(context,foreground))throw new SecurityException("foreground_app_blocked_by_user");
                    DeviceAutomationService automation=DeviceAutomationService.getInstance();
                    if(automation==null) throw new IllegalStateException("accessibility_disconnected");
                    JSONObject ui="ui_visible".equals(kind)?automation.snapshot():new JSONObject().put("package",foreground); String actual=ui.optString("package");
                    e.put("evidence_kind","ui_visible".equals(kind)?"fresh_accessibility_tree":"fresh_window_metadata_no_screenshot");
                    e.put("actual_package",actual); matches=pkg.equals(actual);
                    if("ui_visible".equals(kind)) {
                        boolean found=false; JSONArray nodes=ui.optJSONArray("nodes"); String expected=c.getString("text");
                        if(nodes!=null) for(int j=0;j<nodes.length();j++) {
                            JSONObject node=nodes.getJSONObject(j);
                            for(String field:new String[]{"text","desc"}) {
                                String value=node.optString(field);
                                if(c.optBoolean("contains",false)?value.contains(expected):value.equals(expected)) found=true;
                            }
                        }
                        e.put("text",expected).put("text_found",found); matches=matches&&found;
                    }
                } else if("download_saved".equals(kind)) {
                    File file=LocalDownloadStore.resolve(context,c.getString("file_id"));
                    matches=file!=null&&file.length()>=Math.max(1,c.optLong("min_bytes",1));
                    e.put("file_id",c.getString("file_id")).put("exists",file!=null).put("bytes",file==null?0:file.length());
                    if(file!=null&&!c.optBoolean("allow_existing",false)) matches=matches&&file.lastModified()>=task.getLong("created_at");
                } else if("program_result".equals(kind)) {
                    JSONObject run=ProgramWorkspace.receipt(context,c.getString("run_id"));matches=run.optBoolean("ok")&&task.getString("id").equals(run.optString("task_id"))&&run.optLong("started_at")>=task.getLong("created_at")&&run.getString("revision").equals(ProgramWorkspace.revision(context,run.getString("name")));
                    e.put("run_id",c.getString("run_id")).put("name",run.optString("name")).put("revision",run.optString("revision")).put("value",run.opt("value"));
                    if(c.has("equals"))matches=matches&&jsonEqual(c.get("equals"),run.opt("value"));
                } else if("workspace_file".equals(kind)) {
                    JSONObject f=ProgramWorkspace.fileEvidence(context,c.getString("name"));matches=f.optBoolean("exists")&&(c.optBoolean("allow_existing")||f.optLong("modified_at")>=task.getLong("created_at"));
                    if(c.has("sha256"))matches=matches&&c.getString("sha256").equalsIgnoreCase(f.optString("sha256"));e.put("file",f);
                } else if("browser_page".equals(kind)||"terminal_job".equals(kind)||"workbench_file".equals(kind)) {
                    if(!RiskPolicy.automationEnabled(context)||!AppControlPolicy.isAllowed(context,WorkbenchClient.PACKAGE))throw new SecurityException("workbench_not_allowed");
                    JSONObject scope=new JSONObject().put("task_id",task.getString("id"));
                    if("browser_page".equals(kind)) {
                        JSONObject observed=WorkbenchClient.get(context).call("browser_snapshot",new JSONObject().put("tab_id",c.optString("tab_id")),scope);e.put("browser",observed);
                        matches=observed.optBoolean("ok")&&!observed.optBoolean("loading")&&c.getString("url").equals(observed.optString("url"));
                        if(c.has("text_contains"))matches=matches&&observed.optJSONObject("dom")!=null&&observed.getJSONObject("dom").optString("text").contains(c.getString("text_contains"));
                    } else if("terminal_job".equals(kind)) {
                        JSONObject observed=WorkbenchClient.get(context).call("terminal_status",new JSONObject().put("job_id",c.getString("job_id")),scope);e.put("job",observed);
                        matches=observed.optBoolean("ok")&&"completed".equals(observed.optString("state"))&&task.getString("id").equals(observed.optString("task_id"))&&observed.optLong("started_at")>=task.getLong("created_at")&&observed.optInt("exit_code",-999)==c.optInt("exit_code",0)&&observed.optLong("finished_at")>0;
                        if(c.optBoolean("root",false))matches=matches&&observed.optBoolean("root_requested")&&observed.optBoolean("root_verified")&&observed.optInt("effective_uid",-1)==0;
                    } else {
                        JSONObject observed=WorkbenchClient.get(context).call("workbench_file",new JSONObject().put("action","stat").put("path",c.getString("path")),scope);e.put("file",observed);
                        matches=observed.optBoolean("ok")&&observed.optBoolean("exists")&&!observed.optBoolean("directory")&&observed.optLong("bytes")>=Math.max(0,c.optLong("min_bytes",1))&&(c.optBoolean("allow_existing")||observed.optLong("modified_at")>=task.getLong("created_at"));
                        if(c.has("sha256"))matches=matches&&c.getString("sha256").equalsIgnoreCase(observed.optString("sha256"));
                    }
                } else if("screen_recording".equals(kind)) {
                    JSONObject observed=ScreenRecordingStore.status(context,c.getString("recording_id"));e.put("recording",observed);
                    long required=Math.max(1,c.optInt("min_duration_seconds",1))*1000L;
                    matches=observed.optBoolean("ok")&&"completed".equals(observed.optString("state"))&&task.getString("id").equals(observed.optString("task_id"))&&observed.optLong("started_at")>=task.getLong("created_at")&&observed.optLong("elapsed_ms")>=Math.max(0,required-2000)&&observed.optLong("bytes")>=Math.max(1,c.optLong("min_bytes",1));
                    if(c.has("package"))matches=matches&&c.getString("package").equals(observed.optString("target_package"));
                    if(c.has("target_text"))matches=matches&&c.getString("target_text").equals(observed.optString("target_text"))&&observed.optBoolean("target_text_observed");
                } else if("stream_recording".equals(kind)) {
                    JSONObject observed=WorkbenchClient.get(context).call("stream_record_status",new JSONObject().put("stream_id",c.getString("stream_id")),new JSONObject().put("task_id",task.getString("id")));e.put("stream",observed);
                    long required=Math.max(1,c.optInt("min_duration_seconds",1))*1000L;matches=observed.optBoolean("ok")&&"completed".equals(observed.optString("state"))&&task.getString("id").equals(observed.optString("task_id"))&&observed.optLong("started_at")>=task.getLong("created_at")&&observed.optLong("elapsed_ms")>=Math.max(0,required-2000)&&observed.optLong("bytes")>=Math.max(1024,c.optLong("min_bytes",1024));
                } else if("device_property".equals(kind)) {
                    String field=c.getString("field"); Object value;
                    if("battery_percent".equals(field)) value=((BatteryManager)context.getSystemService(Context.BATTERY_SERVICE)).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                    else if("model".equals(field)) value=android.os.Build.MODEL;
                    else if("accessibility".equals(field)) value=DeviceAutomationService.getInstance()!=null;
                    else value=RiskPolicy.automationEnabled(context);
                    e.put("field",field).put("value",value); matches=!c.has("equals")||String.valueOf(c.get("equals")).equals(String.valueOf(value));
                }
            } catch(Exception error) { e.put("error",DetailedDiagnostics.clean(error.getClass().getSimpleName()+": "+error.getMessage())); }
            e.put("matched",matches); evidence.put(e); passed=passed&&matches;
        }
        return new JSONObject().put("ok",true).put("verified",passed).put("verification_level","fresh_android_observation").put("evidence",evidence);
    }
}
