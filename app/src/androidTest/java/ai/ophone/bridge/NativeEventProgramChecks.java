package ai.ophone.bridge;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** New v0.35 scenarios use real SQLite, own-UID logcat and the separate Android JS worker. */
public final class NativeEventProgramChecks {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private interface Action{void run()throws Exception;}
    private static void rejects(Action a,String message)throws Exception{boolean rejected=false;try{a.run();}catch(Exception expected){rejected=true;}check(rejected,message);}
    private static JSONObject scope(JSONObject t,String id)throws Exception{return new JSONObject().put("task_id",t.getString("id")).put("conversation",t.getString("conversation")).put("call_id",id);}
    private static JSONObject rule(String type)throws Exception{return new JSONObject().put("types",new JSONArray().put(type)).put("min_level","info").put("cooldown_ms",1000);}
    private static void emit(AgentEvents events,JSONObject sc,String type,String level,String code)throws Exception{events.publish(type,"diagnostic",sc,"",level,new JSONObject().put("code",code));}
    public static JSONArray run(Context c,String suite)throws Exception{
        TaskController controller=TaskController.get(c);AgentEvents events=AgentEvents.get(c);LocalToolKernel kernel=new LocalToolKernel(c);
        String prefix="native-events-"+System.currentTimeMillis();ArrayList<String> tasks=new ArrayList<>();JSONArray reports=new JSONArray();
        String[] labels={"threshold_level_code","coalescing_and_versioned_ack","cross_task_isolation","informational_completion_gate","rule_validation","terminal_subscription_cleanup","real_own_uid_logcat","real_js_and_native_result_verification","script_errors_and_revisions","timeout_and_worker_restart","workspace_cli_and_path_scope","no_native_bridge_or_network"};
        try{for(int type=0;type<labels.length;type++){
            JSONObject t=controller.begin("c:"+suite+"-events-"+type,"human-events-"+type,"验证事件和小程序"),sc=scope(t,"events-"+type);String id=t.getString("id"),name=prefix+"-"+type;tasks.add(id);long started=System.currentTimeMillis();
            switch(type){
                case 0:{
                    events.subscribe(t,rule("diagnostic").put("code","threshold").put("min_level","error").put("min_count",2));
                    emit(events,sc,"diagnostic","info","threshold");emit(events,sc,"diagnostic","error","other");emit(events,sc,"diagnostic","error","threshold");
                    check(events.pending(id).length()==0,"noise or first error triggered threshold");emit(events,sc,"diagnostic","error","threshold");
                    JSONObject n=events.pending(id).getJSONObject(0);check(events.pending(id).length()==1&&n.getInt("count")==2&&n.optBoolean("requires_diagnosis"),"threshold event missing");break;
                }
                case 1:{
                    events.subscribe(t,rule("diagnostic"));emit(events,sc,"diagnostic","info","merge");JSONObject first=events.pending(id).getJSONObject(0);
                    Thread.sleep(1050);emit(events,sc,"diagnostic","info","merge");JSONObject latest=events.pending(id).getJSONObject(0);
                    check(events.pending(id).length()==1&&latest.getString("id").equals(first.getString("id"))&&latest.getLong("latest_event_id")>first.getLong("latest_event_id"),"pending event was not coalesced");
                    check(!events.ack(id,first.getString("id"),first.getLong("latest_event_id")).optBoolean("ok"),"stale event version acknowledged newer data");
                    check(events.ack(id,latest.getString("id"),latest.getLong("latest_event_id")).optBoolean("ok")&&events.pending(id).length()==0,"latest event was not acknowledged");break;
                }
                case 2:{
                    events.subscribe(t,rule("diagnostic"));emit(events,new JSONObject().put("task_id","other-task"),"diagnostic","error","foreign");check(events.pending(id).length()==0,"foreign task event leaked");
                    emit(events,sc,"diagnostic","info","own");JSONObject n=events.pending(id).getJSONObject(0);check(!events.ack("other-task",n.getString("id"),n.getLong("latest_event_id")).optBoolean("ok"),"foreign task ACK accepted");break;
                }
                case 3:{
                    events.subscribe(t,rule("diagnostic"));emit(events,sc,"diagnostic","info","notice");
                    controller.criteria(sc,new JSONObject().put("criteria",new JSONArray().put(new JSONObject().put("kind","device_property").put("field","model"))));
                    JSONObject waiting=kernel.call("task_verify",new JSONObject(),scope(t,"pending-info"));check(!waiting.optBoolean("verified")&&waiting.optBoolean("waiting_for_events"),"unread event bypassed completion");
                    JSONObject stored=AgentStore.get(c).task(id);check(stored.optJSONArray("diagnostic_checks")==null||stored.getJSONArray("diagnostic_checks").length()==0,"informational event demanded fault logs");
                    JSONObject n=events.pending(id).getJSONObject(0);kernel.call("event_query",new JSONObject(),scope(t,"query-info"));kernel.call("event_ack",new JSONObject().put("delivery_id",n.getString("id")).put("through_event_id",n.getLong("latest_event_id")),scope(t,"ack-info"));
                    check(controller.verify(sc,new JSONObject(),true).optBoolean("verified"),"processed information prevented native completion");break;
                }
                case 4:{
                    rejects(()->events.subscribe(t,rule("all_raw_system_logs")),"unsupported system log source accepted");
                    rejects(()->events.subscribe(t,rule("diagnostic").put("min_count",0)),"unbounded threshold accepted");
                    rejects(()->events.subscribe(t,rule("diagnostic").put("cooldown_ms",0)),"zero cooldown accepted");
                    for(int i=0;i<8;i++)events.subscribe(t,rule("diagnostic").put("subscription_id",name+"-"+i));rejects(()->events.subscribe(t,rule("diagnostic")),"subscription cap exceeded");break;
                }
                case 5:{
                    events.subscribe(t,rule("diagnostic"));emit(events,sc,"diagnostic","info","last");controller.finish(sc,new JSONObject().put("outcome","cancelled").put("reason","Native event lifecycle test completed; stop only this test subscription."));
                    emit(events,sc,"diagnostic","error","after-terminal");check(events.pending(id).length()==0&&events.query(id,new JSONObject()).getJSONArray("subscriptions").length()==0,"terminal task retained active notification");break;
                }
                case 6:{
                    String tag="OPhoneAIEventCheck";events.subscribe(t,rule("diagnostic").put("source","own_logcat").put("code",tag).put("min_level","error").put("min_count",2));LogcatEventMonitor.update(c);
                    Thread.sleep(500);android.util.Log.e(tag,"Native v0.35 event test one password=do-not-leak");android.util.Log.e(tag,"Native v0.35 event test two");
                    for(int i=0;i<30&&events.pending(id).length()==0;i++)Thread.sleep(100);
                    check(events.pending(id).length()==1,"own-UID logcat did not trigger actual threshold");JSONObject n=events.pending(id).getJSONObject(0);
                    check("own_logcat".equals(n.getJSONObject("event").getString("source"))&&!n.toString().contains("do-not-leak"),"log event source or redaction incorrect");
                    LogcatEventMonitor.stop();break;
                }
                case 7:{
                    ProgramWorkspace.save(c,new JSONObject().put("name",name).put("source","return {sum:input.values.reduce(function(a,b){return a+b;},0), count:input.values.length};"));
                    JSONObject run=ProgramWorkspace.run(c,new JSONObject().put("name",name).put("input",new JSONObject().put("values",new JSONArray("[2,3,5]"))),sc);
                    check(run.optBoolean("ok")&&run.getJSONObject("value").getInt("sum")==10&&run.getInt("worker_pid")!=android.os.Process.myPid(),"actual separate-process JS result failed");
                    controller.criteria(sc,new JSONObject().put("criteria",new JSONArray().put(new JSONObject().put("kind","program_result").put("run_id",run.getString("run_id")).put("equals",new JSONObject().put("count",3).put("sum",10)))));
                    check(controller.verify(sc,new JSONObject(),false).optBoolean("verified"),"native program result verification rejected correct JSON");
                    ProgramWorkspace.save(c,new JSONObject().put("name",name).put("source","return 0;"));check(!controller.verify(sc,new JSONObject(),false).optBoolean("verified"),"changed utility source reused an old successful run");break;
                }
                case 8:{
                    JSONObject saved=ProgramWorkspace.save(c,new JSONObject().put("name",name).put("source","return ("));JSONObject bad=ProgramWorkspace.run(c,new JSONObject().put("name",name),sc);
                    check(!bad.optBoolean("ok")&&bad.optString("error").contains("SyntaxError")&&!bad.optString("error_stack").isEmpty(),"syntax error lacked actual name/stack feedback");
                    ProgramWorkspace.save(c,new JSONObject().put("name",name).put("source","return input.toUpperCase();"));rejects(()->ProgramWorkspace.run(c,new JSONObject().put("name",name).put("revision",saved.getString("revision")),sc),"changed revision silently ran");
                    JSONObject good=ProgramWorkspace.run(c,new JSONObject().put("name",name).put("input","hello"),sc);check(good.optBoolean("ok")&&"HELLO".equals(good.optString("value")),"string JSON input failed");
                    rejects(()->ProgramRunner.checkJsonDepth(new String(new char[40]).replace('\0','[')),"deep JSON accepted");break;
                }
                case 9:{
                    ProgramWorkspace.save(c,new JSONObject().put("name",name).put("source","while(true){}"));long before=System.currentTimeMillis();JSONObject timeout=ProgramWorkspace.run(c,new JSONObject().put("name",name),sc);
                    check(!timeout.optBoolean("ok")&&System.currentTimeMillis()-before<8500,"infinite loop escaped worker deadline");
                    ProgramWorkspace.save(c,new JSONObject().put("name",name).put("source","return 3;"));JSONObject restarted=ProgramWorkspace.run(c,new JSONObject().put("name",name),sc);check(restarted.optBoolean("ok")&&restarted.getInt("value")==3,"worker did not restart after timeout");break;
                }
                case 10:{
                    ProgramWorkspace.command(c,new JSONObject().put("argv",new JSONArray().put("write").put(name+".txt").put("literal $(anything); x")),sc);
                    ProgramWorkspace.command(c,new JSONObject().put("argv",new JSONArray().put("append").put(name+".txt").put("\nprogress")),sc);
                    JSONObject read=ProgramWorkspace.command(c,new JSONObject().put("argv",new JSONArray().put("cat").put(name+".txt")),sc);check("literal $(anything); x\nprogress".equals(read.getString("text")),"CLI interpreted text or lost progress");
                    rejects(()->ProgramWorkspace.command(c,new JSONObject().put("argv",new JSONArray().put("cat").put("../agent_state.db")),sc),"workspace path escaped");
                    rejects(()->ProgramWorkspace.command(c,new JSONObject().put("argv",new JSONArray().put("sh").put("-c").put("id")),sc),"workspace CLI became arbitrary shell");break;
                }
                case 11:{
                    AtomicBoolean reached=new AtomicBoolean();try(ServerSocket server=new ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))){server.setSoTimeout(1600);
                        Thread listener=new Thread(()->{try(Socket socket=server.accept()){reached.set(true);socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(Exception ignored){}});listener.start();
                        String source="var blocked=false;try{var x=new XMLHttpRequest();x.open('GET',input.url,false);x.send();}catch(e){blocked=true;}return {native:typeof window.OPhoneAINative,bridge:typeof window.OPhoneAI,java:typeof java,blocked:blocked};";
                        ProgramWorkspace.save(c,new JSONObject().put("name",name).put("source",source));JSONObject run=ProgramWorkspace.run(c,new JSONObject().put("name",name).put("input",new JSONObject().put("url","http://127.0.0.1:"+server.getLocalPort()+"/probe")),sc);listener.join(1800);
                        check(run.optBoolean("ok"),"network isolation probe did not execute");JSONObject value=run.getJSONObject("value");check(value.optBoolean("blocked")&&!reached.get()&&"undefined".equals(value.getString("native"))&&"undefined".equals(value.getString("bridge"))&&"undefined".equals(value.getString("java")),"program obtained native bridge or network");
                    }break;
                }
            }
            reports.put(new JSONObject().put("case",labels[type]).put("passed",true).put("elapsed_ms",System.currentTimeMillis()-started));
            JSONObject finalTask=AgentStore.get(c).task(id);if(!TaskController.terminal(finalTask.getString("status")))controller.finish(sc,new JSONObject().put("outcome","cancelled").put("reason","Native v0.35 scenario finished; retain user tasks and close only test resources."));
        }return reports;}
        finally{
            LogcatEventMonitor.stop();for(String id:tasks)try{JSONObject t=AgentStore.get(c).task(id);if(t!=null&&!TaskController.terminal(t.getString("status")))controller.finish(scope(t,"cleanup"),new JSONObject().put("outcome","cancelled").put("reason","Native v0.35 cleanup after controlled test; not a user task."));events.closeTask(id);}catch(Exception ignored){}
            cleanupFiles(c,prefix,tasks);
        }
    }
    private static void cleanupFiles(Context c,String prefix,ArrayList<String> taskIds){
        if(!prefix.matches("native-events-[0-9]+"))return;
        File root=new File(c.getFilesDir(),"agent_workspace");
        for(String dir:new String[]{"programs","files","runs"}){File folder=new File(root,dir);for(File f:folder.listFiles()==null?new File[0]:folder.listFiles())try{
            if(!f.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator)||!f.isFile())continue;
            boolean own=f.getName().startsWith(prefix+"-");
            if("runs".equals(dir)&&f.length()<131072){byte[] bytes=new byte[(int)f.length()];try(FileInputStream in=new FileInputStream(f)){int at=0,n;while(at<bytes.length&&(n=in.read(bytes,at,bytes.length-at))>0)at+=n;}own=taskIds.contains(new JSONObject(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)).optString("task_id"));}
            if(own)f.delete();
        }catch(Exception ignored){}}
    }
}
