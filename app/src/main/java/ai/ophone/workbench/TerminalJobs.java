package ai.ophone.workbench;

import android.system.Os;
import android.system.OsConstants;
import android.content.Context;
import ai.ophone.bridge.RootAccess;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Real Android shell jobs. Each job owns a process group, bounded output and a durable receipt. */
final class TerminalJobs {
    interface Events { void emit(String type, JSONObject scope, JSONObject payload); }
    private final Workspace workspace;
    private final Context context;
    private final File receipts;
    private final Events events;
    private final Map<String,Job> jobs=new ConcurrentHashMap<>();
    private final ExecutorService workers=Executors.newCachedThreadPool();
    private static final int CAP=262144;
    TerminalJobs(Context context,Workspace workspace,File files,Events events){this.context=context;this.workspace=workspace;this.events=events;receipts=new File(files,"workbench_terminal_receipts");receipts.mkdirs();prune();}
    private void prune(){File[] entries=receipts.listFiles((d,n)->n.endsWith(".json"));if(entries==null)return;Arrays.sort(entries,Comparator.comparingLong(File::lastModified).reversed());for(int i=0;i<entries.length;i++)if(i>=64||entries[i].lastModified()<System.currentTimeMillis()-7*24*60*60*1000L){String name=entries[i].getName().replace(".json","");for(String suffix:new String[]{".json",".stdout",".stderr",".pid"})new File(receipts,name+suffix).delete();}}
    private static String quote(String s){return "'"+s.replace("'","'\\''")+"'";}
    final class Job {
        String id,owner; JSONObject scope; Process process; File pidFile,lease,exitFile,reasonFile; long started,deadline;volatile long ended;volatile int exit=-1,pid,effectiveUid=-1;
        volatile String state="starting",error="";volatile boolean stopped,rootVerified;boolean root;ByteArrayOutputStream out=new ByteArrayOutputStream(),err=new ByteArrayOutputStream();long outTotal,errTotal;
        synchronized void append(boolean stderr,byte[] b,int n){ByteArrayOutputStream target=stderr?err:out;int room=CAP-target.size();if(room>0)target.write(b,0,Math.min(room,n));if(stderr)errTotal+=n;else outTotal+=n;}
        synchronized JSONObject snapshot(int outOffset,int errOffset)throws Exception {
            byte[] a=out.toByteArray(),b=err.toByteArray();outOffset=Math.max(0,Math.min(a.length,outOffset));errOffset=Math.max(0,Math.min(b.length,errOffset));
            int aEnd=Math.min(a.length,outOffset+16384),bEnd=Math.min(b.length,errOffset+16384);
            return new JSONObject().put("ok",true).put("job_id",id).put("task_id",owner).put("state",state).put("started_at",started).put("finished_at",ended)
                .put("root_requested",root).put("root_verified",rootVerified).put("effective_uid",effectiveUid<0?JSONObject.NULL:effectiveUid)
                .put("exit_code",ended==0?JSONObject.NULL:exit).put("pid",pid).put("stdout",new String(a,outOffset,aEnd-outOffset,StandardCharsets.UTF_8)).put("stderr",new String(b,errOffset,bEnd-errOffset,StandardCharsets.UTF_8))
                .put("stdout_offset",aEnd).put("stderr_offset",bEnd).put("stdout_bytes",outTotal).put("stderr_bytes",errTotal).put("output_truncated",outTotal>CAP||errTotal>CAP).put("error",error);
        }
        synchronized void save(){try{JSONObject s=snapshot(0,0);s.remove("stdout");s.remove("stderr");if(ended>0){Workspace.write(new File(receipts,id+".stdout"),out.toByteArray());Workspace.write(new File(receipts,id+".stderr"),err.toByteArray());}Workspace.write(new File(receipts,id+".json"),s.toString().getBytes(StandardCharsets.UTF_8));}catch(Exception ignored){}}
        void kill(){if(lease!=null)lease.delete();if(root){return;}try{if(pid>1)Os.kill(-pid,OsConstants.SIGKILL);}catch(Exception ignored){}if(process!=null)process.destroy();}
    }
    JSONObject start(JSONObject a,JSONObject scope)throws Exception {
        String command=a.getString("command");if(command.trim().isEmpty()||command.length()>32768)throw new IllegalArgumentException("command_required_max_32768");
        boolean root=a.optBoolean("root",false);JSONObject rootPolicy=RootAccess.policy(context);String su=null;
        if(root){if(!rootPolicy.optBoolean("enabled"))throw new SecurityException("root_shell_disabled_by_user");su=RootAccess.executable();if(su==null)throw new IllegalStateException("su_not_found_device_root_required");}
        synchronized(jobs){int running=0;for(Job j:jobs.values())if(j.ended==0)running++;if(running>=4)throw new IllegalStateException("four_terminal_jobs_already_running");if(jobs.size()>32)for(Job old:new ArrayList<>(jobs.values()))if(old.ended>0)jobs.remove(old.id);
            if(!new File("/system/bin/setsid").canExecute())throw new IllegalStateException("setsid_unavailable_no_safe_job_group");
            File cwd=workspace.resolve(a.optString("cwd",""));if(!cwd.isDirectory())throw new IllegalArgumentException("cwd_not_directory");
            prune();Job j=new Job();j.id="job-"+UUID.randomUUID();j.scope=new JSONObject(scope.toString());j.owner=scope.optString("task_id");j.started=System.currentTimeMillis();j.deadline=j.started+Math.max(100,Math.min(120000,a.optLong("timeout_ms",30000)));j.pidFile=new File(receipts,j.id+".pid");
            j.root=root;ProcessBuilder pb;
            if(root){File control=RootAccess.directory(context);j.pidFile=new File(control,j.id+".identity");j.lease=new File(control,j.id+".lease");j.exitFile=new File(control,j.id+".exit");j.reasonFile=new File(control,j.id+".reason");Workspace.write(j.lease,new byte[]{1});
                pb=new ProcessBuilder(su,"-c",RootJobScript.script(command,RootAccess.policyFile(context),rootPolicy.getString("generation"),j.lease,j.pidFile,j.exitFile,j.reasonFile,cwd,(j.deadline+999)/1000,0));
            }else {j.effectiveUid=android.os.Process.myUid();pb=new ProcessBuilder("/system/bin/setsid","/system/bin/sh","-c","echo $$ > "+quote(j.pidFile.getAbsolutePath())+"; exec /system/bin/sh -c "+quote(command));}pb.directory(cwd);
            pb.environment().put("HOME",workspace.root.getAbsolutePath());pb.environment().put("TMPDIR",new File(workspace.root,"tmp").getAbsolutePath());new File(workspace.root,"tmp").mkdirs();
            JSONObject env=a.optJSONObject("env");if(env!=null){Iterator<String> keys=env.keys();while(keys.hasNext()){String k=keys.next(),v=env.getString(k);if(!k.matches("[A-Za-z_][A-Za-z0-9_]{0,100}")||v.length()>4096)throw new IllegalArgumentException("invalid_environment");pb.environment().put(k,v);}}
            try{j.process=pb.start();}catch(Exception failure){if(j.lease!=null)j.lease.delete();throw failure;}j.state=root?"authorizing_root":"running";jobs.put(j.id,j);j.save();
            Future<?> out=workers.submit(()->drain(j,j.process.getInputStream(),false)),err=workers.submit(()->drain(j,j.process.getErrorStream(),true));
            workers.submit(()->finish(j,out,err));return j.snapshot(0,0);
        }
    }
    private void drain(Job j,InputStream in,boolean stderr){try{byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)j.append(stderr,b,n);}catch(IOException ignored){}finally{try{in.close();}catch(Exception ignored){}}}
    private void finish(Job j,Future<?> out,Future<?> err){try{
        long identityEnd=Math.min(j.deadline,System.currentTimeMillis()+(j.root?15000:500));
        while(j.pid==0&&System.currentTimeMillis()<identityEnd){if(j.pidFile.exists())try{String[] parts=new String(Workspace.read(j.pidFile,64),StandardCharsets.UTF_8).trim().split("\\s+");j.pid=Integer.parseInt(parts[0]);if(j.root){j.effectiveUid=Integer.parseInt(parts[1]);j.rootVerified=j.effectiveUid==0;if(!j.rootVerified)throw new SecurityException("root_uid_verification_failed");j.state="running";}}catch(Exception ignored){j.pid=0;}if(j.pid==0){if(j.process.waitFor(10,TimeUnit.MILLISECONDS)&&!j.pidFile.exists())break;Thread.sleep(5);}}
        if(j.pid<=1){j.error=j.root?"root_authorization_failed_or_identity_missing":"process_group_identity_unavailable";j.kill();if(j.root)j.process.destroy();}else if(j.stopped)j.kill();
        long remaining=Math.max(1,j.deadline-System.currentTimeMillis());boolean done=j.process.waitFor(remaining,TimeUnit.MILLISECONDS);
        if(!done){j.state="timed_out";j.error="terminal_timeout";j.kill();j.process.waitFor(1000,TimeUnit.MILLISECONDS);}else if(j.stopped)j.state="cancelled";else j.state="completed";
        // A shell can exit while a descendant holds stdout. The group still belongs to this job.
        j.kill();try{out.get(700,TimeUnit.MILLISECONDS);}catch(Exception ignored){}try{err.get(700,TimeUnit.MILLISECONDS);}catch(Exception ignored){}
        try{j.exit=j.process.exitValue();}catch(Exception ignored){j.exit=-1;}
        if(j.root){String reason=j.reasonFile.exists()?new String(Workspace.read(j.reasonFile,64),StandardCharsets.UTF_8).trim():"";
            if(!j.rootVerified){j.state="failed";j.error="root_authorization_failed_or_not_uid_zero";}
            else if("timeout".equals(reason)){j.state="timed_out";j.error="terminal_timeout";}
            else if(!"timed_out".equals(j.state)&&(j.stopped||"revoked".equals(reason))){j.state="cancelled";j.error="revoked".equals(reason)?"root_policy_revoked":"terminal_cancelled";}
            else if(j.exitFile.exists()){j.exit=Integer.parseInt(new String(Workspace.read(j.exitFile,32),StandardCharsets.UTF_8).trim());j.state="completed";}
            else if(!"timed_out".equals(j.state)){j.state="failed";j.error="root_exit_receipt_missing_outcome_unknown";}
        }
    }catch(Exception failure){j.state="failed";j.error=String.valueOf(failure.getMessage());j.kill();}
        finally{j.ended=System.currentTimeMillis();j.save();j.pidFile.delete();if(j.lease!=null)j.lease.delete();if(j.exitFile!=null)j.exitFile.delete();if(j.reasonFile!=null)j.reasonFile.delete();try{events.emit("completed".equals(j.state)&&j.exit==0?"terminal.completed":"terminal.failed",j.scope,j.snapshot(0,0));}catch(Exception ignored){}}
    }
    private Job live(JSONObject a,JSONObject scope)throws Exception {Job j=jobs.get(a.getString("job_id"));if(j==null)throw new IllegalArgumentException("job_not_running_in_this_service");if(!j.owner.equals(scope.optString("task_id")))throw new SecurityException("terminal_job_task_mismatch");return j;}
    JSONObject status(JSONObject a,JSONObject scope)throws Exception {
        String id=a.getString("job_id");if(!id.matches("job-[0-9a-f-]{36}"))throw new IllegalArgumentException("invalid_job_id");Job j=jobs.get(id);
        if(j!=null){if(!j.owner.equals(scope.optString("task_id")))throw new SecurityException("terminal_job_task_mismatch");return j.snapshot(a.optInt("stdout_offset"),a.optInt("stderr_offset"));}
        JSONObject stored=new JSONObject(new String(Workspace.read(new File(receipts,id+".json"),32768),StandardCharsets.UTF_8));if(!stored.optString("task_id").equals(scope.optString("task_id")))throw new SecurityException("terminal_job_task_mismatch");
        if(stored.optLong("finished_at")==0)stored.put("state","interrupted").put("error","service_restarted_outcome_unknown");else for(String channel:new String[]{"stdout","stderr"}){File output=new File(receipts,id+"."+channel);byte[] bytes=output.exists()?Workspace.read(output,CAP):new byte[0];int begin=Math.max(0,Math.min(bytes.length,a.optInt(channel+"_offset"))),end=Math.min(bytes.length,begin+16384);stored.put(channel,new String(bytes,begin,end-begin,StandardCharsets.UTF_8)).put(channel+"_offset",end);}return stored;
    }
    JSONObject input(JSONObject a,JSONObject scope)throws Exception {Job j=live(a,scope);if(j.ended!=0)throw new IllegalStateException("terminal_job_finished");byte[] bytes=a.optString("text").getBytes(StandardCharsets.UTF_8);if(bytes.length>16384)throw new IllegalArgumentException("stdin_too_large");synchronized(j){j.process.getOutputStream().write(bytes);j.process.getOutputStream().flush();if(a.optBoolean("close_stdin"))j.process.getOutputStream().close();}return new JSONObject().put("ok",true).put("bytes",bytes.length);}
    JSONObject stop(JSONObject a,JSONObject scope)throws Exception {Job j=live(a,scope);j.stopped=true;j.kill();return j.snapshot(0,0);}
    int cancelOwner(String owner){int count=0;for(Job j:jobs.values())if(owner.equals(j.owner)&&j.ended==0){j.stopped=true;j.kill();count++;}return count;}
    void close(){for(Job j:jobs.values())if(j.ended==0){j.stopped=true;j.kill();}workers.shutdown();}
}
