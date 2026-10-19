package ai.ophone.workbench;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

final class Workspace {
    final File root;
    Workspace(Context c) { root=new File(c.getFilesDir(),"workbench_workspace");root.mkdirs(); }
    File resolve(String path)throws Exception {
        File f=new File(root,path).getCanonicalFile();String base=root.getCanonicalPath();
        if(!f.getPath().equals(base)&&!f.getPath().startsWith(base+File.separator))throw new SecurityException("workspace_path_escape");
        return f;
    }
    static String digest(byte[] bytes)throws Exception {StringBuilder s=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))s.append(String.format("%02x",b&255));return s.toString();}
    static byte[] read(File f,int max)throws Exception {
        try(InputStream in=new FileInputStream(f);ByteArrayOutputStream out=new ByteArrayOutputStream()) {byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(out.size()+n>max)throw new IOException("file_exceeds_read_limit");out.write(b,0,n);}return out.toByteArray();}
    }
    static void write(File f,byte[] data)throws Exception {f.getParentFile().mkdirs();try(FileOutputStream out=new FileOutputStream(f)){out.write(data);out.getFD().sync();}}
    JSONObject stat(String path)throws Exception {
        File f=resolve(path);long modified=f.lastModified();if(f.exists()&&android.os.Build.VERSION.SDK_INT>=27){android.system.StructTimespec time=android.system.Os.stat(f.getAbsolutePath()).st_mtim;modified=time.tv_sec*1000+time.tv_nsec/1000000;}
        JSONObject o=new JSONObject().put("ok",true).put("path",path).put("exists",f.exists()).put("directory",f.isDirectory()).put("bytes",f.length()).put("modified_at",modified).put("mtime_precision_ms",android.os.Build.VERSION.SDK_INT>=27?1:1000);
        if(f.isFile()&&f.length()<=8*1024*1024)o.put("sha256",digest(read(f,8*1024*1024)));return o;
    }
    JSONObject call(JSONObject a)throws Exception {
        String op=a.optString("action","list"),path=a.optString("path","");File f=resolve(path);
        if("stat".equals(op))return stat(path);
        if("mkdir".equals(op)){if(!f.isDirectory()&&!f.mkdirs())throw new IOException("mkdir_failed");return stat(path);}
        if("write".equals(op)){byte[] b=a.getString("text").getBytes(StandardCharsets.UTF_8);if(b.length>262144)throw new IOException("write_max_256KiB");write(f,b);return stat(path);}
        if("read".equals(op))return stat(path).put("text",new String(read(f,Math.min(65536,Math.max(1,a.optInt("max_bytes",16384)))),StandardCharsets.UTF_8));
        if(!"list".equals(op))throw new IllegalArgumentException("unknown_file_action");
        JSONArray items=new JSONArray();File[] files=f.listFiles();if(files==null)throw new IOException("not_a_directory");
        for(File child:files){if(items.length()>=200)break;items.put(new JSONObject().put("name",child.getName()).put("directory",child.isDirectory()).put("bytes",child.length()));}
        return new JSONObject().put("ok",true).put("path",path).put("entries",items).put("truncated",files.length>200).put("workspace_root",root.getAbsolutePath());
    }
}
