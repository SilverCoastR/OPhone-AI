package ai.ophone.bridge;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class LocalToolKernel {
    private final Context context;

    public LocalToolKernel(Context context) {
        this.context = context.getApplicationContext();
    }

    public JSONObject call(String tool, JSONObject args) throws Exception {
        return call(tool,args,null);
    }

    public JSONObject call(String tool,JSONObject args,JSONObject scope) throws Exception {
        if (args == null) args = new JSONObject();
        // The user pressed "Take over" on the control mask: the phone is theirs until they hand it back.
        if (TaskProgress.userControl() && TaskController.isMutation(tool))
            return new JSONObject().put("ok", false).put("error", "user_takeover")
                    .put("message", "The user pressed Take over and is operating the phone themselves. Do not act on the phone until they hand control back (you will be told). Wait, or ask them with ask_user.");
        String auditPackage = activePackage();

        try {
            TaskController.get(context).beforeTool(scope,tool,args);
            TaskProgress.toolStarted(context,scope,tool);
            JSONObject result = callInternal(tool, args, scope);
            TaskProgress.toolFinished(scope);
            if(eventful(tool)){
                boolean ok=!result.has("ok")||result.optBoolean("ok");String pkg=result.optString("package","");
                JSONObject event=AgentEvents.get(context).publish(ok?"operation.completed":"operation.failed","kernel",scope,pkg,ok?"info":"error",new JSONObject().put("tool",tool).put("request_completed",ok).put("outcome_verified",result.optBoolean("foreground_matches_target",false)).put("error",result.optString("error","")));
                result.put("operation_event",event);
            }
            TaskController.get(context).afterTool(scope,tool,result,null);
            String target = auditPackage;
            if ("launch_app".equals(tool)) {
                target = args.optString("package",
                        args.optString("app_name", auditPackage));
            } else if ("uninstall_app".equals(tool)) {
                target = args.optString("package", auditPackage);
            }
            boolean succeeded = !result.has("ok") || result.optBoolean("ok");
            AuditLog.record(context, tool, target, succeeded, auditDetail(tool, args));
            return result;
        } catch (Exception e) {
            if(eventful(tool))try{AgentEvents.get(context).publish("operation.failed","kernel",scope,"","error",new JSONObject().put("tool",tool).put("error",DetailedDiagnostics.clean(e.getMessage())));}catch(Exception ignored){}
            TaskProgress.toolFinished(scope);
            TaskController.get(context).afterTool(scope,tool,new JSONObject(),e);
            if(scope==null||scope.optString("task_id").isEmpty()) DetailedDiagnostics.event(context,"error","tool","","","tool_failed",e.getClass().getName()+": "+e.getMessage(),e);
            String target = auditPackage;
            if ("launch_app".equals(tool)) {
                target = args.optString("package",
                        args.optString("app_name", auditPackage));
            } else if ("uninstall_app".equals(tool)) {
                target = args.optString("package", auditPackage);
            }
            AuditLog.record(context, tool, target, false,
                    e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
            throw e;
        }
    }

    private static boolean eventful(String tool){return TaskController.isMutation(tool)||"program_run".equals(tool)||"program_save".equals(tool)||"workspace_command".equals(tool);}

    private JSONObject callInternal(String tool, JSONObject args, JSONObject scope) throws Exception {
        if ("capabilities".equals(tool)) {
            JSONObject out=new JSONObject().put("ok",true).put("native_tasks",true).put("own_diagnostics",true)
                    .put("system_logcat",new JSONObject().put("available",false).put("reason","READ_LOGS is not granted and no system-log adapter is configured"))
                    .put("app_private_data",new JSONObject().put("available",true).put("resources",new JSONArray("[\"task_status\",\"diagnostic_query\",\"diagnostic_read\",\"execution_status\"]")))
                    .put("automation_enabled",RiskPolicy.automationEnabled(context)).put("accessibility_connected",DeviceAutomationService.getInstance()!=null)
                    .put("downloads_enabled",RiskPolicy.downloadsEnabled(context)).put("native_package_changes_enabled",RiskPolicy.packageChangesEnabled(context))
                    .put("device_time",new JSONObject().put("epoch_ms",System.currentTimeMillis()).put("timezone",java.util.TimeZone.getDefault().getID()))
                    .put("screen_recording",new JSONObject().put("available",Build.VERSION.SDK_INT>=21).put("system_consent_each_capture",true).put("scheduled_start_max_hours",24).put("audio",false).put("max_seconds",180).put("gallery_path","Movies/OPhoneAI"))
                    .put("direct_stream_recording",new JSONObject().put("available",Build.VERSION.SDK_INT>=28).put("formats",new JSONArray("[\"FLV\",\"HLS\",\"MP4\"]")).put("browser_discovery",true).put("offline_live_watch",true).put("watch_max_hours",24).put("signed_urls_redacted",true).put("max_seconds",180).put("gallery_path","Movies/OPhoneAI"))
                    .put("model_transport",new JSONObject().put("chatgpt_webview",true).put("direct_deepseek",true)
                            .put("deepseek_key_configured",new ModelApiStore(context).hasKey()).put("direct_api_version","1.0.0"))
                    .put("platform_compatibility",new JSONObject().put("runtime","standard Android APIs")
                            .put("vendor_specific_runtime_required",false).put("oem_package_dependency",false)
                            .put("manufacturer_detected",Build.MANUFACTURER)
                            .put("physical_multi_oem_validation_complete",false).put("min_android_sdk",24).put("browser_min_android_sdk",28));
            android.content.SharedPreferences diagnosticCaps=context.getSharedPreferences("diagnostic_capabilities",0);
            out.put("own_app_logcat",new JSONObject().put("status",diagnosticCaps.getBoolean("own_logcat_probed",false)
                    ?(diagnosticCaps.getBoolean("own_logcat_available",false)?"available":"unavailable"):"probe_on_request")
                    .put("scope","own UID only, not system/other apps"));
            out.put("workbench",WorkbenchClient.get(context).availability()).put("root_shell",RootAccess.status(context));
            out.put("accessibility_health",AccessibilityHealth.status(context));
            out.put("event_feedback",new JSONObject().put("persistent",true).put("task_scoped",true).put("explicit_ack",true).put("source_order","completion/window events, structured state, UI tree, screenshot fallback").put("own_logcat_monitor",LogcatEventMonitor.status()));
            out.put("programs",new JSONObject().put("language","JavaScript").put("available",Build.VERSION.SDK_INT>=28).put("execution","separate process/WebView profile, JSON input/output, 5-second worker watchdog").put("android_or_java_bridge",false).put("network",false).put("workspace_cli",true).put("arbitrary_os_shell",false).put("python_or_apk_compiler",false));
            out.put("memory",new JSONObject().put("available",true).put("items",MemoryStore.get(context).count()).put("tools","memory_search, memory_save, memory_delete").put("user_visible",true));
            out.put("status_reports",new JSONObject().put("python","from ophone import report, step").put("shell","echo '@@ophone:report {\"stage\":\"...\",\"message\":\"...\"}'").put("javascript_program","report(stage, message, progress, level)").put("event","terminal.report"));
            out.put("autonomy",new JSONObject().put("dynamic_capability_discovery",true).put("durable_task_state",true).put("operational_checkpoints",true).put("event_wait_and_resume",true).put("fresh_evidence_verification",true).put("safe_fallback_planning",true).put("app_specific_workflows_hardcoded",false));
            JSONArray names=new JSONArray(), definitions=mcpTools();for(int i=0;i<definitions.length();i++)names.put(definitions.getJSONObject(i).getString("name"));
            return out.put("tools",names);
        }
        if("root_status".equals(tool)){if(args.optBoolean("probe",false)){requireAutomationEnabled();return RootAccess.probe(context);}return RootAccess.status(context);}
        if("screen_record_start".equals(tool)){
            requireAutomationEnabled();requireAllowedCurrentApp(args,true);
            String expected=args.optString("expected_text","").trim();if(!expected.isEmpty()){DeviceAutomationService service=DeviceAutomationService.getInstance();if(service==null)throw new IllegalStateException("accessibility_disconnected");JSONObject ui=service.snapshot();boolean found=false;JSONArray nodes=ui.optJSONArray("nodes");for(int i=0;nodes!=null&&i<nodes.length();i++){JSONObject node=nodes.optJSONObject(i);if(node!=null&&(node.optString("text").contains(expected)||node.optString("desc").contains(expected))){found=true;break;}}if(!found)throw new IllegalStateException("expected_recording_target_text_not_visible");args.put("target_text_observed",true).put("target_text_observed_at",System.currentTimeMillis());}
            JSONObject result=ScreenRecordingStore.request(context,args,scope);TaskController.get(context).recordingStarted(scope,result,args,"screen_recording");return result;
        }
        if("screen_record_status".equals(tool)){
            JSONObject task=TaskController.get(context).scoped(scope,args);
            JSONObject result=ScreenRecordingStore.waitStatus(context,args.optString("recording_id"),args.optLong("wait_ms",0));
            if(result.optBoolean("ok")&&!task.getString("id").equals(result.optString("task_id")))throw new SecurityException("recording_task_mismatch");
            return result;
        }
        if(WorkbenchTools.NAMES.contains(tool)){
            requireAutomationEnabled();requirePackageAllowed(WorkbenchClient.PACKAGE);
            if("terminal_start".equals(tool)&&args.optBoolean("root",false)&&!RiskPolicy.rootShellEnabled(context))throw new SecurityException("root_shell_disabled_by_user");
            if("http_request".equals(tool)&&args.has("output_file")&&!RiskPolicy.downloadsEnabled(context))throw new SecurityException("downloads_disabled_by_user");
            if("browser_open".equals(tool))WorkbenchClient.get(context).showBrowser();
            JSONObject result=WorkbenchClient.get(context).call(tool,args,scope);if("stream_record_start".equals(tool)||"stream_record_watch_start".equals(tool))TaskController.get(context).recordingStarted(scope,result,args,"stream_recording");return result;
        }
        if ("task_status".equals(tool)) return TaskController.get(context).status(scope,args);
        if ("task_checkpoint".equals(tool)) return TaskController.get(context).checkpoint(scope,args);
        if ("task_wait".equals(tool)) return TaskController.get(context).waitForEvent(scope,args);
        if("event_subscribe".equals(tool))return AgentEvents.get(context).subscribe(TaskController.get(context).scoped(scope,args),args);
        if("event_query".equals(tool))return AgentEvents.get(context).query(TaskController.get(context).scoped(scope,args).getString("id"),args);
        if("event_ack".equals(tool))return AgentEvents.get(context).ack(TaskController.get(context).scoped(scope,args).getString("id"),args.getString("delivery_id"),args.getLong("through_event_id"));
        if("event_unsubscribe".equals(tool))return AgentEvents.get(context).unsubscribe(TaskController.get(context).scoped(scope,args).getString("id"),args.getString("subscription_id"));
        if("program_save".equals(tool)){requireAutomationEnabled();return ProgramWorkspace.save(context,args);}
        if("program_run".equals(tool)){requireAutomationEnabled();return ProgramWorkspace.run(context,args,scope);}
        if("workspace_command".equals(tool)){if(args.optJSONArray("argv")!=null&&"run".equals(args.getJSONArray("argv").optString(0)))requireAutomationEnabled();return ProgramWorkspace.command(context,args,scope);}
        if ("task_set_criteria".equals(tool)) return TaskController.get(context).criteria(scope,args);
        if ("ask_user".equals(tool)) return TaskController.get(context).awaitUser(scope,args);
        if ("return_to_chat".equals(tool)) return TaskProgress.bringChatToFront(context,scope==null?"":scope.optString("conversation"));
        if ("android_intent".equals(tool)) { requireAutomationEnabled(); return AndroidSystemTools.startIntent(context, args, scope); }
        if ("android_resolve".equals(tool)) return AndroidSystemTools.resolve(context, args);
        if ("android_info".equals(tool)) return AndroidSystemTools.info(context, args);
        if ("android_control".equals(tool)) { requireAutomationEnabled(); return AndroidSystemTools.control(context, args); }
        if ("miniapp_save".equals(tool)) return MiniAppStore.save(context, args);
        if ("miniapp_read".equals(tool)) return MiniAppStore.read(context, MiniAppStore.name(args.getString("name")));
        if ("miniapp_list".equals(tool)) return MiniAppStore.list(context);
        if ("miniapp_delete".equals(tool)) return MiniAppStore.delete(context, MiniAppStore.name(args.getString("name")));
        if ("miniapp_open".equals(tool)) { MiniAppStore.meta(context, MiniAppStore.name(args.getString("name"))); context.startActivity(MiniAppActivity.intent(context, args.getString("name"))); return new JSONObject().put("ok", true).put("opened", args.getString("name")); }
        if ("memory_search".equals(tool)) return MemoryStore.get(context).search(args);
        if ("memory_save".equals(tool)) return MemoryStore.get(context).save(args);
        if ("memory_delete".equals(tool)) return MemoryStore.get(context).delete(args.getString("id"));
        if ("task_verify".equals(tool)) return TaskController.get(context).verify(scope,args,false);
        if ("task_finish".equals(tool)) return TaskController.get(context).finish(scope,args);
        if ("diagnostic_query".equals(tool)) return AgentStore.get(context).logs(args);
        if ("read_app_logcat".equals(tool)) return OwnAppLogcat.read(context,args.optInt("max_lines",100));
        if ("diagnostic_read".equals(tool)) return AgentStore.get(context).log(args.getLong("id"));
        if ("execution_status".equals(tool)) {
            JSONObject task=TaskController.get(context).scoped(scope,args);
            String call=args.optString("call_id");
            JSONObject operation=AgentStore.get(context).operation(task.getString("id"),call);
            JSONObject receiptScope=new JSONObject().put("conversation",task.getString("conversation")).put("call_id",call);
            JSONObject receipt=ExecutionJournal.receipt(context,receiptScope);
            // Image bytes are never copied into diagnostic text; request a fresh screenshot instead.
            JSONObject response=receipt.optJSONObject("response");
            if(response!=null&&response.optJSONObject("result")!=null) {
                JSONArray content=response.getJSONObject("result").optJSONArray("content");
                for(int i=0;content!=null&&i<content.length();i++)if("image".equals(content.getJSONObject(i).optString("type")))content.getJSONObject(i).put("data","[saved image; request screenshot to inspect]");
            }
            return new JSONObject().put("ok",true).put("operation",operation==null?JSONObject.NULL:operation).put("receipt",receipt);
        }
        if ("runtime_status".equals(tool)) return RuntimeDiagnostics.status(context);
        if ("runtime_logs".equals(tool)) return RuntimeDiagnostics.logs(context, args.optInt("limit", 20));
        if ("runtime_recover".equals(tool)) return ChatActivity.recoverRuntime(args.optString("mode", "auto"));
        if ("device_status".equals(tool)) {
            return deviceStatus();
        }
        if ("list_apps".equals(tool)) {
            return listApps(args.optBoolean("include_system", false));
        }
        if ("find_apps".equals(tool)) {
            String query = args.optString("query");
            if (query.trim().isEmpty() || query.length() > 200) {
                throw new IllegalArgumentException("invalid_query");
            }
            int max = Math.max(1, Math.min(30, args.optInt("max_results", 10)));
            return findApps(query, max);
        }
        if ("wait_for_ui".equals(tool)) {
            requireAutomationEnabled();
            DeviceAutomationService a = requireAccessibility();
            String mode = args.optString("mode", "appear");
            if (!java.util.Arrays.asList("appear", "disappear", "package", "stable").contains(mode)) throw new IllegalArgumentException("invalid_wait_mode");
            String text = args.optString("text", ""), pkg = args.optString("package", "");
            if (text.length() > 200 || ("appear".equals(mode) || "disappear".equals(mode)) && text.isEmpty()) throw new IllegalArgumentException("text_required_max_200");
            if ("package".equals(mode) && pkg.isEmpty()) throw new IllegalArgumentException("package_required");
            if (!pkg.isEmpty()) requirePackageAllowed(pkg);
            JSONObject result = a.waitFor(mode, text, pkg, Math.max(200, Math.min(30000, args.optLong("timeout_ms", 8000))));
            if (!result.optString("package").isEmpty() && !AppControlPolicy.isAllowed(context, result.optString("package")))
                return new JSONObject().put("ok", false).put("error", "foreground_app_blocked_by_policy").put("package", result.optString("package"));
            return result;
        }
        if ("get_ui".equals(tool)) {
            DeviceAutomationService a = requireAllowedCurrentApp(args, false);
            JSONObject snapshot = a.snapshot();
            String expected = args.optString("expected_package");
            if (!expected.isEmpty()) {
                snapshot.put("expected_package", expected);
                snapshot.put("foreground_matches_target", expected.equals(snapshot.optString("package")));
            }
            return snapshot;
        }
        if ("screenshot".equals(tool)) {
            DeviceAutomationService a = requireAllowedCurrentApp(args, false);
            JSONObject result = a.screenshotPngBase64(8000L);
            if (!result.optBoolean("ok", false)) {
                throw new IllegalStateException(result.optString("error", "screenshot_failed"));
            }
            return result;
        }
        if ("tap".equals(tool)) {
            DeviceAutomationService a = requireAllowedCurrentApp(args, true);
            return observed(a, args, () -> a.tap((float) args.optDouble("x"), (float) args.optDouble("y")));
        }
        if ("swipe".equals(tool)) {
            long duration = Math.max(100L, Math.min(5000L,
                    args.optLong("duration_ms", 400L)));
            DeviceAutomationService a = requireAllowedCurrentApp(args, true);
            return observed(a, args, () -> a.swipe((float) args.optDouble("x1"), (float) args.optDouble("y1"),
                    (float) args.optDouble("x2"), (float) args.optDouble("y2"), duration));
        }
        if ("click_text".equals(tool)) {
            String text = args.optString("text");
            if (text.length() < 1 || text.length() > 1000) {
                throw new IllegalArgumentException("invalid_text");
            }
            DeviceAutomationService a = requireAllowedCurrentApp(args, true);
            return observed(a, args, () -> a.clickText(text, args.optBoolean("contains", true)));
        }
        if ("input_text".equals(tool)) {
            String text = args.optString("text");
            if (text.length() > 20000) {
                throw new IllegalArgumentException("text_too_long");
            }
            DeviceAutomationService a = requireAllowedCurrentApp(args, true);
            return observed(a, args, () -> a.setText(text));
        }
        if ("press_global".equals(tool)) {
            requireAutomationEnabled();
            String action = args.optString("action");
            if (!isAllowedGlobal(action)) {
                throw new IllegalArgumentException("invalid_global_action");
            }
            DeviceAutomationService service = args.optString("expected_package").isEmpty()
                    ? requireAccessibility() : requireAllowedCurrentApp(args, true);
            return observed(service, args, () -> service.global(action));
        }
        if ("launch_app".equals(tool)) {
            requireAutomationEnabled();
            String requestedPackage = args.optString("package").trim();
            String requestedName = args.optString("app_name").trim();

            JSONObject resolved = resolveLaunchTarget(requestedPackage, requestedName);
            if (!resolved.optBoolean("ok", false)) {
                return resolved;
            }

            String pkg = resolved.getString("package");
            requirePackageAllowed(pkg);

            Intent launch = context.getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch == null) {
                JSONObject out = new JSONObject();
                out.put("ok", false);
                out.put("error", "app_not_launchable");
                out.put("package", pkg);
                out.put("label", resolved.optString("label"));
                return out;
            }

            boolean agentPip =
                    ChatActivity.prepareForExternalAutomation(context);
            if (agentPip) {
                try {
                    Thread.sleep(250L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }

            AgentEvents.LaunchWatch launchWatch=AgentEvents.get(context).expectForeground(pkg,scope);
            boolean alreadyForeground=pkg.equals(activePackage());
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try{context.startActivity(launch);}catch(Exception error){AgentEvents.get(context).awaitForeground(launchWatch,0);throw error;}

            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("agent_pip", agentPip);
            out.put("package", pkg);
            out.put("label", resolved.optString("label"));

            JSONObject observed=AgentEvents.get(context).awaitForeground(launchWatch,alreadyForeground?500:4000);

            String foreground = activePackage();
            if (!foreground.isEmpty()) {
                out.put("foreground_package", foreground);
                boolean reachedTarget = pkg.equals(foreground);
                out.put("foreground_matches_target", reachedTarget);
                out.put("outcome_verified",reachedTarget).put("execution_state",reachedTarget?"foreground_observed":"foreground_not_confirmed");
                if(reachedTarget){
                    if(observed==null)observed=AgentEvents.get(context).publish("app.foreground","kernel",scope,pkg,"info",new JSONObject().put("package",pkg).put("checked_at",System.currentTimeMillis()).put("foreground_matches_target",true).put("evidence_kind","fresh_window_metadata_fallback").put("reason",alreadyForeground?"already_in_foreground":"window_event_timeout"));
                    out.put("completion_event",observed).put("next_step","The requested app is observed in the foreground. For an open-only goal set foreground_app criteria and task_verify; do not loop screenshots or UI reads. App-specific content/controls still require structured UI, with screenshot only when needed.");
                }else if(observed!=null)out.put("window_event",observed).put("completion_event_invalidated",true);
                if (!reachedTarget) {
                    out.put("interstitial", true);
                    DeviceAutomationService automation =
                            DeviceAutomationService.getInstance();
                    if (automation != null
                            && AppControlPolicy.isAllowed(context, foreground)) {
                        try {
                            JSONObject snapshot = automation.snapshot();
                            if (snapshot.optBoolean("ok", false)) {
                                out.put("interstitial_ui",
                                        compactUiSummary(snapshot, 30));
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    out.put("next_step",
                            out.has("interstitial_ui")
                                    ? "Use interstitial_ui to explain what is blocking the target app. "
                                      + "Only call get_ui if more detail is necessary."
                                    : "Call get_ui to inspect the blocking system/OEM screen. "
                                      + "Do not claim the target app is ready yet.");
                }
            }

            return out;
        }
        if ("open_url".equals(tool)) {
            requireAutomationEnabled();
            return PackageOps.openUrl(context, args.optString("url"));
        }
        if ("download_file".equals(tool)) {
            if (!RiskPolicy.downloadsEnabled(context)) {
                throw new SecurityException("downloads_disabled_by_user");
            }
            String url = args.optString("url");
            String filename = args.optString("filename");
            long max = args.optLong("max_bytes", 256L * 1024L * 1024L);
            return LocalDownloadStore.download(context, url, filename, max);
        }
        if ("install_apk".equals(tool)) {
            if (!RiskPolicy.packageChangesEnabled(context)) {
                throw new SecurityException("package_changes_disabled_by_user");
            }
            return PackageOps.requestInstall(context, args.optString("file_id"));
        }
        if ("uninstall_app".equals(tool)) {
            if (!RiskPolicy.packageChangesEnabled(context)) {
                throw new SecurityException("package_changes_disabled_by_user");
            }
            String pkg = args.optString("package");
            requirePackageAllowed(pkg);
            return PackageOps.requestUninstall(context, pkg);
        }

        throw new IllegalArgumentException("unknown_tool");
    }

    public JSONArray mcpTools() throws Exception {
        JSONArray tools = new JSONArray();
        WorkbenchTools.append(tools);
        JSONObject eventProps=new JSONObject();eventProps.put("types",new JSONObject().put("type","array").put("items",stringProp("app.foreground, diagnostic, operation.completed/failed, package.added/removed, program.completed/failed, browser.loaded/failed, terminal.completed/failed, terminal.report (status reports from scripts), ui.toast, http.completed/failed, recording.consent_required/started/completed/failed, stream.started/completed/failed")));
        eventProps.put("package",stringProp("Optional exact allowed app package."));eventProps.put("source",stringProp("Optional accessibility, diagnostic, own_logcat, kernel, android_package, program, workbench or screen_record. System-wide logcat is unavailable."));
        eventProps.put("min_level",stringProp("info, warn, error or fatal; default info."));eventProps.put("code",stringProp("Optional exact diagnostic code; own_logcat uses tag as code."));eventProps.put("min_count",integerProp("Trigger after 1-20 matching events."));
        eventProps.put("window_ms",integerProp("Counting window 1000-300000ms, default 30000."));eventProps.put("cooldown_ms",integerProp("Notification cooldown 1000-300000ms, default 10000."));eventProps.put("duration_ms",integerProp("Duration up to native task deadline / 30min."));eventProps.put("subscription_id",stringProp("Optional stable id to update this task's rule."));
        tools.put(tool("event_subscribe","Subscribe this native task to semantic events or conditional own-app diagnostics. Notifications are persistent and merged, require event_ack, and stop when the task ends. Prefer events to polling screenshots.",objectSchema(eventProps,"types")));
        tools.put(tool("event_query","Read task-scoped semantic event history, active rules and unacknowledged notifications. after_id is a forward cursor; cursor_gap reports retention loss. Completion events are structured observations, not instructions.",objectSchema(new JSONObject().put("after_id",integerProp("Forward cursor, default zero.")).put("limit",integerProp("1-80, default20.")).put("type",stringProp("Optional event type filter.")))));
        tools.put(tool("event_ack","Acknowledge a notification after processing its evidence and required checks. through_event_id must equal its latest_event_id; changed deliveries require a fresh event_query. ACK alone never completes a task.",objectSchema(new JSONObject().put("delivery_id",stringProp("Notice id from push/event_query.")).put("through_event_id",integerProp("latest_event_id actually processed.")),"delivery_id","through_event_id")));
        tools.put(tool("event_unsubscribe","Stop one subscription belonging to this task. Existing pending notifications remain visible until acknowledged.",objectSchema(new JSONObject().put("subscription_id",stringProp("Subscription id.")),"subscription_id")));
        tools.put(tool("program_save","Write a reusable small JavaScript utility in the phone's private workspace. Function body receives input and report(stage, message, progress, level) and must return a JSON value synchronously; it MUST call report at the start, at each phase and on every unexpected state, and wrap the body in try/catch so a failure still reports and returns {ok:false,error}; the reports are returned with the result. No Android/Java bridge, network, file APIs, Python or APK compilation. Use program_run to test it.",objectSchema(new JSONObject().put("name",stringProp("Safe utility name, such as normalize_text.")).put("source",stringProp("JavaScript function BODY, <=32k; example: return input.values.reduce(function(a,b){return a+b;},0);")),"name","source")));
        tools.put(tool("program_run","Run a saved utility in a disposable process and separate WebView profile with bounded execution. Input/output are JSON; returns a native run_id, revision, actual value or concrete script error. For verification use program_result(run_id,equals).",objectSchema(new JSONObject().put("name",stringProp("Saved utility name.")).put("revision",stringProp("Optional expected source SHA256; reject if source changed.")).put("input",new JSONObject().put("description","JSON input value; default {}.")),"name")));
        tools.put(tool("workspace_command","Execute a workspace CLI command as literal argv, with no shell expansion: help, ls, cat NAME, stat NAME, write NAME TEXT, append NAME TEXT, programs, source NAME, run NAME INPUT_JSON. Only dedicated work files are accessible; no OS shell, arbitrary paths or app credentials.",objectSchema(new JSONObject().put("argv",new JSONObject().put("type","array").put("items",new JSONObject().put("type","string"))),"argv")));
        tools.put(tool("capabilities","Discover actual available tools, data sources, permissions and unavailable capabilities. System logcat is separate from own-app diagnostics.",objectSchema(new JSONObject())));
        tools.put(tool("task_status","Read the native original goal, durable phase, completion criteria, pending receipts and diagnostic issues. The task is independent of page DOM.",objectSchema(new JSONObject())));
        JSONObject askProps=new JSONObject();askProps.put("question",stringProp("One clear question in the user's language, <=1000 chars."));
        askProps.put("options",new JSONObject().put("type","array").put("items",stringProp("Short answer choice, <=80 chars.")).put("description","Optional 2-6 quick answers shown as buttons; the user may still type anything."));
        tools.put(tool("return_to_chat","Bring OPhone AI's chat back to the front. Call it as soon as you no longer need other apps (before writing the final answer, a long analysis or a question), so the user is not left looking at another app.",objectSchema(new JSONObject())));
        tools.put(tool("ask_user","Pause this task and ask the user when you truly need their decision, missing information (account choice, verification code, preference) or confirmation of a risky/irreversible step. Do not ask for things you can find out yourself. After calling it, stop calling tools and end the turn with the question; the user's next message is the answer and continues the same task.",objectSchema(askProps,"question")));
        JSONObject intentProps=new JSONObject();
        intentProps.put("action",stringProp("android.intent.action.VIEW (default), SEND, SENDTO, DIAL, INSERT, WEB_SEARCH, MAIN (explicit activity), SET_ALARM, SET_TIMER, SHOW_ALARMS, android.media.action.STILL_IMAGE_CAMERA/VIDEO_CAMERA, or any android.settings.* action."));
        intentProps.put("data",stringProp("URI: https/http, geo:, tel: (dial only), mailto:, smsto:, market:, content://com.android.calendar/events for INSERT, or an app deep link (custom scheme needs package)."));
        intentProps.put("type",stringProp("MIME type, e.g. text/plain, image/png, vnd.android.cursor.dir/event."));
        intentProps.put("package",stringProp("Target package (must be allowed by app policy)."));
        intentProps.put("activity",stringProp("Explicit activity class (full or starting with '.'), from android_resolve."));
        intentProps.put("extras",new JSONObject().put("type","object").put("description","String/number/boolean extras, e.g. android.intent.extra.TEXT, android.intent.extra.alarm.HOUR, title, beginTime (epoch ms)."));
        intentProps.put("share_file",stringProp("Relative workbench workspace file to attach as EXTRA_STREAM with a one-time read grant (use with SEND)."));
        intentProps.put("chooser",booleanProp("Show the system app chooser; default true for SEND without package."));
        tools.put(tool("android_intent","Open Android functionality directly instead of navigating UI: open links/deep links, share text or a workspace file, compose SMS/email, dial (never calls), add calendar events, set alarms/timers, open a specific settings page or an app's exported activity. The target app shows its own screen; sending, calling, saving or paying still requires a visible confirmation there, which you then perform or leave to the user. Returns handlers and foreground confirmation.",objectSchema(intentProps)));
        JSONObject resolveProps=new JSONObject();resolveProps.put("package",stringProp("App to describe: label, version, launch activity, which common intents it handles, exported activities."));
        resolveProps.put("action",stringProp("Or: an intent spec (action/data/type) to list which apps handle it."));resolveProps.put("data",stringProp("Intent data URI."));resolveProps.put("type",stringProp("Intent MIME type."));
        tools.put(tool("android_resolve","Discover what an app can be asked to do (its 'manual') or which apps handle an intent, so you can use android_intent instead of long UI routes.",objectSchema(resolveProps)));
        JSONObject infoProps=new JSONObject();infoProps.put("sections",new JSONObject().put("type","array").put("items",stringProp("battery | network | storage | display | audio | locale | power")).put("description","Default: all."));
        tools.put(tool("android_info","Read structured system state: battery (percent, charging, temperature), network (wifi/cellular/vpn, validated, metered), storage, display, audio volumes, locale/time, power (screen, saver, battery optimization). Prefer this over reading Settings screens.",objectSchema(infoProps)));
        JSONObject controlProps=new JSONObject();JSONObject controlAction=stringProp("set_volume | clipboard_set | torch");controlAction.put("enum",new JSONArray().put("set_volume").put("clipboard_set").put("torch"));controlProps.put("action",controlAction);
        controlProps.put("stream",stringProp("For set_volume: media | ring | alarm | notification."));controlProps.put("percent",integerProp("For set_volume: 0-100."));controlProps.put("level",integerProp("For set_volume: absolute level instead of percent."));
        controlProps.put("text",stringProp("For clipboard_set."));controlProps.put("on",booleanProp("For torch."));
        tools.put(tool("android_control","Small reversible device controls without UI: set a volume stream, put text on the clipboard, toggle the flashlight.",objectSchema(controlProps,"action")));
        JSONObject miniSave=new JSONObject();miniSave.put("name",stringProp("Id: lowercase letters, digits, - or _, <=40."));miniSave.put("title",stringProp("Display title, <=60 chars."));miniSave.put("description",stringProp("What it does, <=300 chars."));
        miniSave.put("html",stringProp("Complete single-file HTML app (inline CSS/JS, <=512KiB, mobile-first, light and dark). No network: fetch/XHR/WebSocket and remote URLs are blocked. Runtime: await OPhone.call(tool, args) returns the tool's result object (check .ok); OPhone.storage.get/set/getJSON/setJSON persist per app; OPhone.workspaceUrl(path) shows workspace images/video; OPhone.toast(text); OPhone.close(); OPhone.info. Bundled libraries: /lib/markdown-it.min.js, /lib/katex/katex.min.js + /lib/katex/katex.min.css, /lib/highlight.min.js, /lib/mermaid.min.js."));
        miniSave.put("permissions",new JSONObject().put("type","array").put("items",stringProp("Tool name")).put("description","Every tool the app calls, e.g. android_info, python_run, terminal_status, http_request. The user approves this list on first use; installing apps, root, task control and memory writes are never available."));
        tools.put(tool("miniapp_save","Create or update a mini app: a small interactive tool with its own screen (dashboard, form, calculator, file viewer, remote control) that the user can reopen from the chat drawer. Prefer a mini app when the user wants a reusable interface rather than a one-off answer. Its page must show progress for every long step and handle every OPhone.call failure visibly (never a silent spinner). Test it with miniapp_open after saving.",objectSchema(miniSave,"name","title","html")));
        tools.put(tool("miniapp_open","Open a saved mini app on screen.",objectSchema(new JSONObject().put("name",stringProp("Mini app id.")),"name")));
        tools.put(tool("miniapp_read","Read a mini app's metadata and HTML source before changing it.",objectSchema(new JSONObject().put("name",stringProp("Mini app id.")),"name")));
        tools.put(tool("miniapp_list","List saved mini apps.",objectSchema(new JSONObject())));
        tools.put(tool("miniapp_delete","Delete a mini app.",objectSchema(new JSONObject().put("name",stringProp("Mini app id.")),"name")));
        JSONObject memSearch=new JSONObject();memSearch.put("query",stringProp("Keywords from the goal, app name or screen, <=500 chars."));memSearch.put("app",stringProp("Optional package or app name to prefer."));
        JSONObject memKind=stringProp("preference | fact | playbook | failure");memKind.put("enum",new JSONArray().put("preference").put("fact").put("playbook").put("failure"));memSearch.put("kind",memKind);memSearch.put("limit",integerProp("1-10, default 5."));
        tools.put(tool("memory_search","Search long-term memory kept on this phone: user preferences, facts, playbooks (how a task was done before in an app: route, UI landmarks, pitfalls) and failure notes. Search at the start of a task with the goal and target app; treat results as hints and still verify the current screen.",objectSchema(memSearch)));
        JSONObject memSave=new JSONObject();memSave.put("kind",memKind);memSave.put("title",stringProp("Short title, <=120 chars, e.g. 'Settings: check battery level'."));memSave.put("text",stringProp("<=4000 chars. Playbook: goal, app, ordered steps with visible texts/landmarks, waits, verification, pitfalls. Failure: what was tried and why it failed."));
        memSave.put("app",stringProp("Package or app name this applies to, if any."));memSave.put("tags",new JSONObject().put("type","array").put("items",stringProp("Tag")).put("description","Up to 12 short tags."));memSave.put("id",stringProp("Existing memory id to update instead of creating a duplicate."));
        tools.put(tool("memory_save","Save or update a long-term memory. After a task succeeds, save or refine a playbook; after a dead end, save a failure note; when the user states a lasting preference, save it. Never store passwords, verification codes, payment data, tokens or private message contents. The user can see and delete all memories.",objectSchema(memSave,"kind","title","text")));
        tools.put(tool("memory_delete","Delete a memory that is wrong or outdated.",objectSchema(new JSONObject().put("id",stringProp("Memory id.")),"id")));
        JSONObject checkpointProps=new JSONObject();
        checkpointProps.put("summary",stringProp("Concise observable progress and decisions; no hidden chain-of-thought."));
        checkpointProps.put("next_action",stringProp("Next concrete action or observation."));
        JSONObject planItems=new JSONObject().put("type","array").put("items",new JSONObject().put("type","string"));
        checkpointProps.put("completed_steps",planItems).put("remaining_steps",planItems).put("blockers",planItems);
        tools.put(tool("task_checkpoint","Persist a concise capability-neutral execution checkpoint in the native task. Use after initial planning and material plan changes so recovery does not depend on chat DOM or model memory.",objectSchema(checkpointProps,"summary","next_action")));
        tools.put(tool("task_wait","Suspend model activity while native event subscriptions watch asynchronous conditions. The task automatically resumes on a delivered event or the bounded deadline; subscribe first and do not poll screenshots.",objectSchema(new JSONObject().put("reason",stringProp("Concrete condition being awaited.")).put("until_epoch_ms",integerProp("Device epoch deadline, at least 1 second and at most 24 hours ahead.")),"reason","until_epoch_ms")));
        JSONObject criterion=new JSONObject();
        JSONObject kind=stringProp("Completion condition kind.");kind.put("enum",new JSONArray("[\"app_installed\",\"foreground_app\",\"ui_visible\",\"download_saved\",\"device_property\",\"program_result\",\"workspace_file\",\"browser_page\",\"terminal_job\",\"workbench_file\",\"screen_recording\",\"stream_recording\"]"));
        criterion.put("kind",kind);criterion.put("package",stringProp("Exact package if known; otherwise provide app_name."));
        criterion.put("app_name",stringProp("Target installed app's visible name, never an unrelated app."));
        criterion.put("text",stringProp("Text/description that must actually appear for ui_visible."));
        criterion.put("contains",booleanProp("Partial visible-text matching; default false."));
        criterion.put("launchable",booleanProp("For app_installed, also require a launcher."));
        criterion.put("file_id",stringProp("For download_saved, the real file_id returned by download_file."));
        criterion.put("min_bytes",integerProp("Minimum saved file size."));
        criterion.put("allow_existing",booleanProp("Permit a previously saved file only if this satisfies the original goal."));
        criterion.put("run_id",stringProp("Native run id for program_result; must belong to this task."));criterion.put("name",stringProp("Dedicated workspace file name for workspace_file."));criterion.put("sha256",stringProp("Optional expected file hash."));
        JSONObject field=stringProp("For device_property, the observed field.");field.put("enum",new JSONArray("[\"battery_percent\",\"model\",\"accessibility\",\"automation_enabled\"]"));criterion.put("field",field);
        criterion.put("root",booleanProp("For terminal_job, require actual root_requested/root_verified and effective_uid=0. Mandatory evidence for an explicit root command goal."));
        criterion.put("tab_id",stringProp("For browser_page, tab id; selected tab if omitted."));criterion.put("url",stringProp("For browser_page, exact expected HTTP(S) URL."));criterion.put("text_contains",stringProp("For browser_page, expected DOM text."));criterion.put("job_id",stringProp("For terminal_job, task-owned job id."));criterion.put("exit_code",integerProp("For terminal_job, expected actual exit code, default zero."));criterion.put("path",stringProp("For workbench_file, relative path."));
        criterion.put("recording_id",stringProp("For screen_recording, task-owned id returned by screen_record_start."));criterion.put("stream_id",stringProp("For stream_recording, task-owned id returned by stream_record_start."));criterion.put("target_text",stringProp("For screen_recording, target text observed before capture started."));criterion.put("min_duration_seconds",integerProp("For either recording kind, required duration, 1-180 seconds."));
        criterion.put("equals",new JSONObject().put("description","Optional expected scalar device property; omit when the user just asks to read it."));
        JSONObject conditions=new JSONObject().put("type","array").put("minItems",1).put("maxItems",8).put("items",objectSchema(criterion,"kind"));
        tools.put(tool("task_set_criteria","Define evidence needed to fulfill the ORIGINAL native goal. Installation needs app_installed; opening an app needs foreground_app/ui_visible. EACH app_installed/foreground_app/ui_visible item must include package or app_name; ui_visible also needs text. Never weaken a goal just to pass.",objectSchema(new JSONObject().put("criteria",conditions),"criteria")));
        tools.put(tool("task_verify","Independently check completion conditions against fresh Android state; returns actual evidence and unmet conditions. Does not trust a chat success claim.",objectSchema(new JSONObject())));
        JSONObject endProps=new JSONObject();JSONObject outcome=stringProp("Finish only after verification, or report a concrete blocker/failure/cancellation.");outcome.put("enum",new JSONArray("[\"success\",\"blocked\",\"failed\",\"cancelled\"]"));
        endProps.put("outcome",outcome);endProps.put("summary",stringProp("Concise result supported by evidence."));endProps.put("reason",stringProp("Concrete reason required for blocked/failed/cancelled; ordinary navigation is not a user blocker."));
        tools.put(tool("task_finish","Propose task completion. success is accepted ONLY if fresh Android verification passes. For real blockers record what was observed and attempted, then explain the specific required user action.",objectSchema(endProps,"outcome")));
        JSONObject diagnosticProps=new JSONObject();diagnosticProps.put("task_id",stringProp("Optional native task id filter from task_status."));
        diagnosticProps.put("after_id",integerProp("Cursor for forward pagination; omit for most recent results."));diagnosticProps.put("limit",integerProp("1-80, default 20."));
        diagnosticProps.put("level",stringProp("Optional exact severity: error, warning, info."));diagnosticProps.put("source",stringProp("Optional exact source, such as tool, task, java_crash, webview_js or bridge."));
        diagnosticProps.put("query",stringProp("Search code, safe message and stack locations."));
        tools.put(tool("diagnostic_query","Search persistent detailed OWN-APP errors, crash records, WebView and task diagnostics by task, source, severity or text. Returns record ids; use diagnostic_read for full safe stack frames. Not system logcat.",objectSchema(diagnosticProps)));
        tools.put(tool("diagnostic_read","Read a detailed own-app diagnostic entry, including redacted exception message, causes and stack locations.",objectSchema(new JSONObject().put("id",integerProp("Diagnostic id returned by diagnostic_query.")),"id")));
        tools.put(tool("read_app_logcat","Read bounded logcat filtered to OPhone AI's OWN UID only, without a computer. This does not grant system/other-app log access. Returns unavailable if restricted by this device.",objectSchema(new JSONObject().put("max_lines",integerProp("1-200 lines; default 100.")))));
        tools.put(tool("execution_status","Inspect a native operation and saved receipt after uncertain outcomes. Does not execute or replay the operation.",objectSchema(new JSONObject().put("call_id",stringProp("Previous call_id in this native task.")),"call_id")));
        tools.put(tool("runtime_status",
                "Proactively inspect the phone executor health, current stage, last error, heartbeat age, pending feedback and execution receipts. Use at the start of phone tasks and when progress or feedback is uncertain.",
                objectSchema(new JSONObject())));
        JSONObject logProps = new JSONObject();
        logProps.put("limit", integerProp("Recent diagnostic events, 1-80; default 20. No prompts, typed text or credentials."));
        tools.put(tool("runtime_logs", "Read bounded native execution and recovery diagnostics. Inspect after tool errors or stalled progress, then decide how to recover.", objectSchema(logProps)));
        JSONObject recoveryProps = new JSONObject();
        JSONObject recoveryMode = stringProp("Allowed recovery: auto, retry_delivery, restart_observers. Never replays Android actions or changes permissions.");
        recoveryMode.put("enum", new JSONArray("[\"auto\",\"retry_delivery\",\"restart_observers\"]"));
        recoveryProps.put("mode", recoveryMode);
        tools.put(tool("runtime_recover", "Repair observers or retry saved feedback after inspecting runtime_status/runtime_logs. Verify status and actual Android state afterwards, then continue the user's original task. Does not change code, permissions or account data.", objectSchema(recoveryProps)));


        tools.put(tool("device_status",
                "Read Android device status and OPhone AI permission/risk state.",
                objectSchema(new JSONObject())));

        JSONObject recordingStart=new JSONObject();recordingStart.put("expected_package",stringProp("Exact allowed package that must currently be in the foreground and will be recorded."));recordingStart.put("expected_text",stringProp("Optional target title/name that must be visible before recording; stored as durable setup evidence."));recordingStart.put("duration_seconds",integerProp("Video duration, 5-180 seconds; default 60."));recordingStart.put("output_name",stringProp("Optional safe MP4 filename. The result is published to Movies/OPhoneAI."));recordingStart.put("start_at_epoch_ms",integerProp("Optional device epoch time, at most 24 hours ahead. Android capture consent is requested now; the foreground service starts recording at this time."));
        tools.put(tool("screen_record_start","Start one bounded video-only Android screen recording of the current target app. Android always shows its own capture confirmation; the user must approve it. Never retry while consent or recording is pending. Poll screen_record_status using the returned recording_id.",objectSchema(recordingStart,"expected_package")));
        tools.put(tool("screen_record_status","Inspect or briefly wait for the same recording without replaying it. Completion returns duration, bytes, SHA-256 and the MediaStore URI.",objectSchema(new JSONObject().put("recording_id",stringProp("Id returned by screen_record_start.")).put("wait_ms",integerProp("Wait up to 15000ms for a state change; default 0.")),"recording_id")));

        tools.put(tool("get_ui",
                "Read the current Android Accessibility UI tree. Blocked apps are refused.",
                objectSchema(new JSONObject())));

        JSONObject waitProps = new JSONObject();
        JSONObject waitMode = stringProp("appear (default): text becomes visible; disappear: text is gone; package: app is foreground; stable: UI stops changing.");
        waitMode.put("enum", new JSONArray("[\"appear\",\"disappear\",\"package\",\"stable\"]"));
        waitProps.put("mode", waitMode);
        waitProps.put("text", stringProp("Visible text or description to wait for (case-insensitive substring)."));
        waitProps.put("package", stringProp("Expected foreground package; required for mode=package, optional filter for appear."));
        waitProps.put("timeout_ms", integerProp("200-30000, default 8000."));
        tools.put(tool("wait_for_ui", "Wait until a UI condition holds instead of sleeping or polling screenshots. matched=false with timed_out=true is an observation, not an error.",
                objectSchema(waitProps)));

        tools.put(tool("screenshot",
                "Capture the current Android display as PNG. Blocked apps are refused.",
                objectSchema(new JSONObject())));

        JSONObject tapProps = new JSONObject();
        tapProps.put("x", numberProp("Screen X coordinate in physical pixels."));
        tapProps.put("y", numberProp("Screen Y coordinate in physical pixels."));
        tapProps.put("observe", booleanProp("Return effect (what changed: texts, package, toasts) after the UI settles; default true."));
        tools.put(tool("tap", "Tap a screen coordinate in the currently allowed app. The result's effect field reports what visibly changed; no_visible_change means re-inspect before retrying.",
                objectSchema(tapProps, "x", "y")));

        JSONObject swipeProps = new JSONObject();
        swipeProps.put("x1", numberProp("Start X."));
        swipeProps.put("y1", numberProp("Start Y."));
        swipeProps.put("x2", numberProp("End X."));
        swipeProps.put("y2", numberProp("End Y."));
        swipeProps.put("duration_ms", integerProp("Gesture duration in milliseconds, 100-5000."));
        swipeProps.put("observe", booleanProp("Return effect after the UI settles; default true."));
        tools.put(tool("swipe", "Swipe in the currently allowed app. Returns effect (added/removed text, package, toasts).",
                objectSchema(swipeProps, "x1", "y1", "x2", "y2")));

        JSONObject clickProps = new JSONObject();
        clickProps.put("text", stringProp("Visible text or content description to click."));
        clickProps.put("contains", booleanProp("Allow partial matching."));
        clickProps.put("observe", booleanProp("Return effect after the UI settles; default true."));
        tools.put(tool("click_text", "Click a visible element in the currently allowed app. Returns effect (added/removed text, package, toasts).",
                objectSchema(clickProps, "text")));

        JSONObject inputProps = new JSONObject();
        inputProps.put("text", stringProp("Text to put in the focused editable field."));
        inputProps.put("observe", booleanProp("Return effect after the UI settles; default true."));
        tools.put(tool("input_text", "Set text in the currently allowed app. Returns effect.",
                objectSchema(inputProps, "text")));

        JSONObject globalProps = new JSONObject();
        JSONObject action = stringProp("Android global action.");
        JSONArray actionEnum = new JSONArray();
        actionEnum.put("back"); actionEnum.put("home"); actionEnum.put("recents");
        actionEnum.put("notifications"); actionEnum.put("quick_settings");
        action.put("enum", actionEnum);
        globalProps.put("action", action);
        globalProps.put("observe", booleanProp("Return effect after the UI settles; default true."));
        tools.put(tool("press_global", "Perform an Android global navigation action. Returns effect.",
                objectSchema(globalProps, "action")));

        JSONObject launchProps = new JSONObject();
        launchProps.put("package", stringProp(
                "Optional exact Android package name. Prefer app_name when the user names an app."));
        launchProps.put("app_name", stringProp(
                "Optional human-visible app name, for example Gallery, Photos, 相册, Chrome, Files. "
                        + "OPhone AI resolves it against installed launchable apps. "
                        + "Provide package or app_name."));
        tools.put(tool("launch_app",
                "Launch an installed app if user policy allows it. "
                        + "Do not guess package names: use app_name directly, or find_apps first if ambiguous. "
                        + "If foreground_matches_target is false, call get_ui to inspect the system/OEM interstitial "
                        + "before claiming the app is ready.",
                objectSchema(launchProps)));

        JSONObject findProps = new JSONObject();
        findProps.put("query", stringProp(
                "App display name or package fragment to search across user and system apps."));
        findProps.put("max_results", integerProp("Maximum results, 1-30; default 10."));
        tools.put(tool("find_apps",
                "Find installed apps by visible name or package. "
                        + "Use this when the user names an app but the exact package is unknown.",
                objectSchema(findProps, "query")));

        JSONObject appsProps = new JSONObject();
        appsProps.put("include_system", booleanProp("Include system applications."));
        tools.put(tool("list_apps", "List installed apps and whether OPhone AI is allowed to control them.",
                objectSchema(appsProps)));

        JSONObject openProps = new JSONObject();
        openProps.put("url", stringProp("HTTP or HTTPS URL."));
        tools.put(tool("open_url", "Open a web URL with Android.",
                objectSchema(openProps, "url")));

        JSONObject downloadProps = new JSONObject();
        downloadProps.put("url", stringProp("HTTP or HTTPS file URL."));
        downloadProps.put("filename", stringProp("Optional local filename."));
        downloadProps.put("max_bytes", integerProp("Maximum bytes; default 256 MiB, maximum 1 GiB."));
        tools.put(tool("download_file", "Download a file into OPhone AI local storage if downloads are enabled.",
                objectSchema(downloadProps, "url")));

        JSONObject installProps = new JSONObject();
        installProps.put("file_id", stringProp("APK file_id previously returned by download_file."));
        tools.put(tool("install_apk",
                "Request installation of a downloaded APK. High-risk permission must be enabled and Android may require user confirmation.",
                objectSchema(installProps, "file_id")));

        JSONObject uninstallProps = new JSONObject();
        uninstallProps.put("package", stringProp("Android package to request uninstall."));
        tools.put(tool("uninstall_app",
                "Request uninstall of an app. High-risk permission must be enabled and Android requires user confirmation.",
                objectSchema(uninstallProps, "package")));

        String[] windowTools = { "get_ui", "screenshot", "tap", "swipe",
                "click_text", "input_text", "press_global" };
        for (int i = 0; i < tools.length(); i++) {
            JSONObject definition = tools.getJSONObject(i);
            for (String name : windowTools) {
                if (name.equals(definition.optString("name"))) {
                    definition.getJSONObject("inputSchema").getJSONObject("properties")
                            .put("expected_package", stringProp(
                                    "Optional package from the latest observed UI. Restores the task app if the chat was foregrounded; actions reject a different app. Read get_ui after a mismatch."));
                }
            }
        }
        return tools;
    }

    public JSONObject deviceStatus() throws Exception {
        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("model", Build.MODEL);
        out.put("manufacturer", Build.MANUFACTURER);
        out.put("android_version", Build.VERSION.RELEASE);
        out.put("sdk", Build.VERSION.SDK_INT);
        out.put("accessibility", DeviceAutomationService.getInstance() != null);
        out.put("accessibility_health",AccessibilityHealth.status(context));
        out.put("local_mcp", true);
        out.put("mcp_bind", LocalServerConfig.bind(context));
        out.put("active_package", activePackage());
        out.put("automation_enabled", RiskPolicy.automationEnabled(context));
        out.put("downloads_enabled", RiskPolicy.downloadsEnabled(context));
        out.put("package_changes_enabled", RiskPolicy.packageChangesEnabled(context));
        out.put("boot_enabled", RiskPolicy.bootEnabled(context));
        out.put("overlay_enabled", RiskPolicy.overlayEnabled(context));
        out.put("blocked_apps", AppControlPolicy.blocked(context).size());

        BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        if (bm != null) {
            out.put("battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
        }

        JSONArray caps = new JSONArray();
        JSONArray tools = mcpTools();
        for (int i = 0; i < tools.length(); i++) {
            caps.put(tools.getJSONObject(i).getString("name"));
        }
        out.put("capabilities", caps);
        return out;
    }

    private void requireAutomationEnabled() {
        if (!RiskPolicy.automationEnabled(context)) {
            throw new SecurityException("automation_disabled_by_user");
        }
    }

    private DeviceAutomationService requireAllowedCurrentApp(JSONObject args, boolean requireExpected) {
        requireAutomationEnabled();
        DeviceAutomationService service = requireAccessibility();
        String expected = args.optString("expected_package").trim();
        if (!expected.isEmpty()) requirePackageAllowed(expected);
        String pkg = service.currentPackageName();
        if (!expected.isEmpty() && !expected.equals(context.getPackageName())
                && context.getPackageName().equals(pkg)) {
            // Returning to the chat to watch progress must not redirect the next
            // store/browser action into the chat composer. Restore the known task.
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(expected);
            if (launch != null) {
                ChatActivity.prepareForExternalAutomation(context);
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(launch);
                try { Thread.sleep(650L); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                pkg = service.currentPackageName();
            }
        }
        if (pkg.isEmpty()) throw new IllegalStateException("no_automation_window");
        requirePackageAllowed(pkg);
        if (requireExpected && !expected.isEmpty() && !expected.equals(pkg)) {
            throw new IllegalStateException("automation_target_changed: expected=" + expected
                    + " actual=" + pkg + "; call get_ui before another action");
        }
        return service;
    }

    private void requirePackageAllowed(String pkg) {
        if (!AppControlPolicy.isAllowed(context, pkg)) {
            throw new SecurityException("app_control_blocked_by_user: " + pkg);
        }
    }

    private DeviceAutomationService requireAccessibility() {
        DeviceAutomationService a = DeviceAutomationService.getInstance();
        if (a == null) {
            throw new IllegalStateException("accessibility_not_enabled");
        }
        return a;
    }

    private String activePackage() {
        DeviceAutomationService a = DeviceAutomationService.getInstance();
        return a == null ? "" : a.currentPackageName();
    }

    interface Action { boolean run() throws Exception; }
    /** Runs a UI action and, unless observe=false, reports what visibly changed (texts, package, toasts). */
    private JSONObject observed(DeviceAutomationService a, JSONObject args, Action action) throws Exception {
        boolean observe = args.optBoolean("observe", true);
        JSONObject before = null;
        if (observe) try { before = a.uiSignature(); } catch (Exception ignored) {}
        long at = System.currentTimeMillis();
        JSONObject out = boolResult(action.run());
        if (out.optBoolean("ok") && before != null) {
            try {
                JSONObject effect = a.effect(before, at, Math.max(300, Math.min(4000, args.optLong("settle_ms", 1500))));
                String after = effect.optString("package_after");
                if (!after.isEmpty() && !AppControlPolicy.isAllowed(context, after)) effect = new JSONObject().put("changed", true).put("package_after", after).put("blocked_by_policy", true);
                out.put("effect", effect);
            } catch (Exception e) { out.put("effect_error", String.valueOf(e.getMessage())); }
        }
        return out;
    }

    private JSONObject boolResult(boolean ok) throws Exception {
        JSONObject out = new JSONObject();
        out.put("ok", ok);
        if (!ok) out.put("error", "action_not_performed");
        return out;
    }

    private boolean isAllowedGlobal(String action) {
        return "back".equals(action)
                || "home".equals(action)
                || "recents".equals(action)
                || "notifications".equals(action)
                || "quick_settings".equals(action);
    }

    private JSONObject listApps(boolean includeSystem) throws Exception {
        PackageManager pm = context.getPackageManager();
        List<PackageInfo> packages = pm.getInstalledPackages(0);
        JSONArray out = new JSONArray();

        for (PackageInfo pi : packages) {
            if (pi.applicationInfo == null) continue;
            boolean system = (pi.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if (!includeSystem && system) continue;

            JSONObject item = new JSONObject();
            item.put("package", pi.packageName);
            item.put("label", String.valueOf(pm.getApplicationLabel(pi.applicationInfo)));
            item.put("system", system);
            item.put("version", pi.versionName == null ? "" : pi.versionName);
            item.put("control_allowed", AppControlPolicy.isAllowed(context, pi.packageName));
            out.put(item);
            if (out.length() >= 1000) break;
        }

        JSONObject root = new JSONObject();
        root.put("ok", true);
        root.put("count", out.length());
        root.put("apps", out);
        return root;
    }

    private JSONObject compactUiSummary(JSONObject snapshot, int maxItems) throws Exception {
        JSONObject out = new JSONObject();
        out.put("package", snapshot.optString("package"));

        JSONArray texts = new JSONArray();
        JSONArray actions = new JSONArray();
        Set<String> seen = new LinkedHashSet<>();
        JSONArray nodes = snapshot.optJSONArray("nodes");
        if (nodes == null) {
            out.put("visible_texts", texts);
            out.put("actions", actions);
            return out;
        }

        for (int i = 0; i < nodes.length() && texts.length() < maxItems; i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (node == null) continue;
            String text = node.optString("text").trim();
            String desc = node.optString("desc").trim();
            String value = !text.isEmpty() ? text : desc;
            if (!value.isEmpty()) {
                if (value.length() > 300) value = value.substring(0, 300);
                if (seen.add(value)) texts.put(value);
            }

            if (node.optBoolean("clickable", false) && !value.isEmpty()
                    && actions.length() < 12) {
                JSONObject action = new JSONObject();
                action.put("text", value);
                action.put("bounds", node.optString("bounds"));
                actions.put(action);
            }
        }

        out.put("visible_texts", texts);
        out.put("actions", actions);
        return out;
    }

    private JSONObject resolveLaunchTarget(String requestedPackage,
                                             String requestedName) throws Exception {
        PackageManager pm = context.getPackageManager();

        if (!requestedPackage.isEmpty()) {
            if (requestedPackage.length() > 300) {
                throw new IllegalArgumentException("invalid_package");
            }
            try {
                ApplicationInfo info = pm.getApplicationInfo(requestedPackage, 0);
                JSONObject out = new JSONObject();
                out.put("ok", true);
                out.put("package", requestedPackage);
                out.put("label", String.valueOf(pm.getApplicationLabel(info)));
                out.put("launchable",
                        pm.getLaunchIntentForPackage(requestedPackage) != null);
                return out;
            } catch (PackageManager.NameNotFoundException e) {
                JSONObject out = new JSONObject();
                out.put("ok", false);
                out.put("error", "app_not_found");
                out.put("package", requestedPackage);
                return out;
            }
        }

        if (requestedName.isEmpty() || requestedName.length() > 200) {
            throw new IllegalArgumentException("package_or_app_name_required");
        }

        List<AppCandidate> candidates = collectAppMatches(requestedName, true);
        if (candidates.isEmpty()) {
            JSONObject out = new JSONObject();
            out.put("ok", false);
            out.put("error", "app_not_found");
            out.put("query", requestedName);
            return out;
        }

        AppCandidate first = candidates.get(0);
        if (candidates.size() > 1
                && candidates.get(1).score == first.score
                && first.score < 1000) {
            JSONObject out = new JSONObject();
            out.put("ok", false);
            out.put("error", "ambiguous_app_name");
            out.put("query", requestedName);
            JSONArray matches = new JSONArray();
            for (int i = 0; i < Math.min(8, candidates.size()); i++) {
                matches.put(candidateJson(candidates.get(i)));
            }
            out.put("candidates", matches);
            return out;
        }

        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("package", first.packageName);
        out.put("label", first.label);
        out.put("launchable", first.launchable);
        return out;
    }

    private JSONObject findApps(String query, int maxResults) throws Exception {
        List<AppCandidate> candidates = collectAppMatches(query, false);
        JSONArray apps = new JSONArray();
        for (int i = 0; i < Math.min(maxResults, candidates.size()); i++) {
            apps.put(candidateJson(candidates.get(i)));
        }

        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("query", query);
        out.put("count", apps.length());
        out.put("apps", apps);
        return out;
    }

    private List<AppCandidate> collectAppMatches(String query,
                                                  boolean launchableOnly) {
        PackageManager pm = context.getPackageManager();
        String needle = normalizeAppName(query);
        List<AppCandidate> results = new ArrayList<>();

        for (PackageInfo pi : pm.getInstalledPackages(0)) {
            if (pi.applicationInfo == null) continue;

            String label = String.valueOf(
                    pm.getApplicationLabel(pi.applicationInfo));
            String packageName = pi.packageName;
            String normalizedLabel = normalizeAppName(label);
            String normalizedPackage = normalizeAppName(packageName);

            int score = appMatchScore(
                    needle, normalizedLabel, normalizedPackage);
            if (score <= 0) continue;

            boolean launchable =
                    pm.getLaunchIntentForPackage(packageName) != null;
            if (launchableOnly && !launchable) continue;

            AppCandidate candidate = new AppCandidate();
            candidate.packageName = packageName;
            candidate.label = label;
            candidate.system =
                    (pi.applicationInfo.flags
                            & ApplicationInfo.FLAG_SYSTEM) != 0;
            candidate.allowed =
                    AppControlPolicy.isAllowed(context, packageName);
            candidate.launchable = launchable;
            candidate.score = score;
            results.add(candidate);
        }

        results.sort(Comparator
                .comparingInt((AppCandidate c) -> c.score)
                .reversed()
                .thenComparing(c -> c.label.toLowerCase(Locale.ROOT))
                .thenComparing(c -> c.packageName));
        return results;
    }

    private int appMatchScore(String needle,
                              String normalizedLabel,
                              String normalizedPackage) {
        if (needle.isEmpty()) return 0;
        if (normalizedLabel.equals(needle)) return 1000;
        if (normalizedPackage.equals(needle)) return 980;
        if (normalizedLabel.startsWith(needle)) return 850;
        if (normalizedPackage.endsWith(needle)) return 800;
        if (normalizedLabel.contains(needle)) return 700;
        if (normalizedPackage.contains(needle)) return 600;

        if (isGalleryAlias(needle)
                && (normalizedPackage.contains("gallery")
                || normalizedPackage.contains("photos")
                || normalizedLabel.contains("gallery")
                || normalizedLabel.contains("photos")
                || normalizedLabel.contains("相册")
                || normalizedLabel.contains("图库")
                || normalizedLabel.contains("照片"))) {
            return 650;
        }

        return 0;
    }

    private boolean isGalleryAlias(String needle) {
        return "gallery".equals(needle)
                || "photos".equals(needle)
                || "photo".equals(needle)
                || "album".equals(needle)
                || "相册".equals(needle)
                || "图库".equals(needle)
                || "照片".equals(needle);
    }

    private String normalizeAppName(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(
                value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        return normalized.replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    private JSONObject candidateJson(AppCandidate candidate)
            throws Exception {
        JSONObject item = new JSONObject();
        item.put("package", candidate.packageName);
        item.put("label", candidate.label);
        item.put("system", candidate.system);
        item.put("launchable", candidate.launchable);
        item.put("control_allowed", candidate.allowed);
        return item;
    }

    private static final class AppCandidate {
        String packageName;
        String label;
        boolean system;
        boolean allowed;
        boolean launchable;
        int score;
    }

    private String auditDetail(String tool, JSONObject args) {
        if ("launch_app".equals(tool) || "uninstall_app".equals(tool)) {
            return "target=" + args.optString("package");
        }
        if ("download_file".equals(tool)) {
            return "download=" + args.optString("filename", "url");
        }
        if ("install_apk".equals(tool)) {
            return "file_id=" + args.optString("file_id");
        }
        if ("open_url".equals(tool)) {
            return "open_url";
        }
        return "";
    }

    private JSONObject tool(String name, String description, JSONObject inputSchema) throws Exception {
        JSONObject t = new JSONObject();
        t.put("name", name);
        t.put("description", description);
        t.put("inputSchema", inputSchema);
        return t;
    }

    private JSONObject objectSchema(JSONObject properties, String... required) throws Exception {
        JSONObject schema = new JSONObject();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("additionalProperties", false);
        if (required != null && required.length > 0) {
            JSONArray req = new JSONArray();
            for (String r : required) req.put(r);
            schema.put("required", req);
        }
        return schema;
    }

    private JSONObject stringProp(String description) throws Exception {
        JSONObject p = new JSONObject();
        p.put("type", "string");
        p.put("description", description);
        return p;
    }

    private JSONObject numberProp(String description) throws Exception {
        JSONObject p = new JSONObject();
        p.put("type", "number");
        p.put("description", description);
        return p;
    }

    private JSONObject integerProp(String description) throws Exception {
        JSONObject p = new JSONObject();
        p.put("type", "integer");
        p.put("description", description);
        return p;
    }

    private JSONObject booleanProp(String description) throws Exception {
        JSONObject p = new JSONObject();
        p.put("type", "boolean");
        p.put("description", description);
        return p;
    }
}
