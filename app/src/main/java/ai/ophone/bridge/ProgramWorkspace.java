package ai.ophone.bridge;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Private utility programs, plain work files and immutable native run receipts live in distinct directories. */
public final class ProgramWorkspace {
    private ProgramWorkspace(){}
    private static File directory(Context c,String name)throws Exception{File dir=new File(c.getFilesDir(),"agent_workspace/"+name);if(!dir.isDirectory()&&!dir.mkdirs())throw new IllegalStateException("program_workspace_unavailable");return dir;}
    private static String name(String n){if(!n.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")||n.contains(".."))throw new IllegalArgumentException("invalid_workspace_name");return n;}
    private static File file(Context c,String dir,String n)throws Exception{File root=directory(c,dir),f=new File(root,name(n));if(!f.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator))throw new SecurityException("workspace_path_escape");return f;}
    private static String read(File f,int max)throws Exception{if(!f.isFile())throw new IllegalArgumentException("workspace_file_not_found");if(f.length()>max)throw new IllegalArgumentException("workspace_file_too_large");try(FileInputStream in=new FileInputStream(f);java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){byte[] b=new byte[2048];int n;while((n=in.read(b))!=-1){if(out.size()+n>max)throw new IllegalArgumentException("workspace_file_too_large");out.write(b,0,n);}return new String(out.toByteArray(),StandardCharsets.UTF_8);}}
    private static synchronized void write(File f,String text)throws Exception{
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);if(bytes.length>131072)throw new IllegalArgumentException("workspace_file_too_large");
        File root=f.getParentFile().getParentFile();long used=0;int count=0;
        for(File dir:root.listFiles()==null?new File[0]:root.listFiles())if(dir.isDirectory())for(File item:dir.listFiles()==null?new File[0]:dir.listFiles())if(item.isFile()){used+=item.length();count++;}
        if(used-(f.isFile()?f.length():0)+bytes.length>2*1024*1024||!f.exists()&&count>=200)throw new IllegalStateException("workspace_quota_exceeded");
        AtomicFile atomic=new AtomicFile(f);FileOutputStream out=null;try{out=atomic.startWrite();out.write(bytes);atomic.finishWrite(out);}catch(Exception e){if(out!=null)atomic.failWrite(out);throw e;}
    }
    public static synchronized JSONObject save(Context c,JSONObject args)throws Exception{
        String n=name(args.getString("name")),source=args.getString("source");if(source.trim().isEmpty()||source.length()>32768)throw new IllegalArgumentException("program_source_required_max_32k");
        JSONObject p=new JSONObject().put("name",n).put("source",source).put("revision",TaskController.digest(source)).put("updated_at",System.currentTimeMillis()).put("language","javascript");
        write(file(c,"programs",n+".json"),p.toString());return new JSONObject().put("ok",true).put("name",n).put("revision",p.getString("revision")).put("language","javascript");
    }
    public static JSONObject run(Context c,JSONObject args,JSONObject scope)throws Exception{
        String n=name(args.getString("name"));JSONObject program=new JSONObject(read(file(c,"programs",n+".json"),131072));
        String revision=program.getString("revision");if(args.has("revision")&&!revision.equals(args.getString("revision")))throw new IllegalStateException("program_revision_changed_read_source_before_running");
        String wrapped=new JSONArray().put(args.has("input")?args.get("input"):new JSONObject()).toString();String input=wrapped.substring(1,wrapped.length()-1);
        long started=System.currentTimeMillis();JSONObject result=ProgramRunner.run(c,program.getString("source"),input);
        String id="run-"+UUID.randomUUID();result.put("run_id",id).put("name",n).put("revision",revision).put("started_at",started).put("finished_at",System.currentTimeMillis())
                .put("task_id",scope==null?"":scope.optString("task_id","")).put("parent_pid",android.os.Process.myPid());
        write(file(c,"runs",id+".json"),result.toString());
        if(!result.optBoolean("ok"))AgentStore.get(c).addLog("error","program",scope==null?"":scope.optString("task_id"),scope==null?"":scope.optString("call_id"),"program_failed",DetailedDiagnostics.clean(result.optString("error")),DetailedDiagnostics.clean(result.optString("error_stack")));
        AgentEvents.get(c).publish(result.optBoolean("ok")?"program.completed":"program.failed","program",scope,"",result.optBoolean("ok")?"info":"error",
                new JSONObject().put("name",n).put("run_id",id).put("revision",revision).put("error",result.optString("error","")));
        return result;
    }
    public static JSONObject receipt(Context c,String id)throws Exception{return new JSONObject(read(file(c,"runs",name(id)+".json"),131072));}
    public static String revision(Context c,String n)throws Exception{return new JSONObject(read(file(c,"programs",name(n)+".json"),131072)).getString("revision");}
    public static JSONObject command(Context c,JSONObject args,JSONObject scope)throws Exception{
        JSONArray argv=args.getJSONArray("argv");if(argv.length()<1||argv.length()>4)throw new IllegalArgumentException("one_to_four_workspace_arguments_required");
        for(int i=0;i<argv.length();i++)if(!(argv.get(i) instanceof String)||argv.getString(i).length()>32768)throw new IllegalArgumentException("invalid_workspace_argument");
        String op=argv.getString(0);JSONObject out=new JSONObject().put("ok",true).put("interface","workspace CLI; no OS shell").put("command",op);
        if("help".equals(op))return out.put("commands",new JSONArray("[\"ls\",\"cat NAME\",\"stat NAME\",\"write NAME TEXT\",\"append NAME TEXT\",\"programs\",\"source NAME\",\"run NAME INPUT_JSON\"]"));
        if("ls".equals(op)||"programs".equals(op)){
            if(argv.length()!=1)throw new IllegalArgumentException("unexpected_workspace_arguments");JSONArray files=new JSONArray();File root=directory(c,"ls".equals(op)?"files":"programs");
            for(File f:root.listFiles()==null?new File[0]:root.listFiles())if(f.isFile()&&!f.getName().endsWith(".bak"))files.put(new JSONObject().put("name",f.getName()).put("bytes",f.length()).put("modified_at",f.lastModified()));return out.put("files",files);
        }
        if("source".equals(op)){if(argv.length()!=2)throw new IllegalArgumentException("source_requires_name");return out.put("program",new JSONObject(read(file(c,"programs",name(argv.getString(1))+".json"),131072)));}
        if("run".equals(op)){if(argv.length()<2||argv.length()>3)throw new IllegalArgumentException("run_requires_program_and_optional_json");JSONObject a=new JSONObject().put("name",argv.getString(1));if(argv.length()==3)a.put("input",new JSONTokener(argv.getString(2)).nextValue());return run(c,a,scope);}
        if(!java.util.Arrays.asList("cat","stat","write","append").contains(op))throw new IllegalArgumentException("unsupported_workspace_command_no_os_shell");
        if(argv.length()!=("write".equals(op)||"append".equals(op)?3:2))throw new IllegalArgumentException("invalid_workspace_command_arity");File f=file(c,"files",argv.getString(1));
        if("write".equals(op)||"append".equals(op)){
            if(!RiskPolicy.automationEnabled(c))throw new SecurityException("automation_disabled");String text=argv.getString(2);if("append".equals(op)&&f.isFile())text=read(f,131072)+text;write(f,text);
        }
        if(!f.isFile())throw new IllegalArgumentException("workspace_file_not_found");
        out.put("name",f.getName()).put("bytes",f.length()).put("modified_at",f.lastModified()).put("sha256",TaskController.digest(read(f,131072)));
        if("cat".equals(op))out.put("text",read(f,131072));return out;
    }
    public static JSONObject fileEvidence(Context c,String n)throws Exception{File f=file(c,"files",n);return new JSONObject().put("exists",f.isFile()).put("bytes",f.isFile()?f.length():0).put("sha256",f.isFile()?TaskController.digest(read(f,131072)):"").put("modified_at",f.isFile()?f.lastModified():0);}
}
