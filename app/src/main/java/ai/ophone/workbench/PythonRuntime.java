package ai.ophone.workbench;

import android.content.Context;
import android.os.Build;
import android.system.Os;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.*;
import java.util.zip.*;

/** Embedded CPython. The launcher lives in the native library directory; the standard library is extracted once per release. */
final class PythonRuntime {
    static final class Env { File launcher,home,bin;Map<String,String> vars=new LinkedHashMap<>();String version; }
    private static Env ready;private static String failure;
    private PythonRuntime(){}
    private static File launcher(Context c){return new File(c.getApplicationInfo().nativeLibraryDir,"libophone_python.so");}
    private static JSONObject manifest(Context c)throws Exception {try(InputStream in=c.getAssets().open("python/manifest.json")){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));}}
    private static String abi(JSONObject m){JSONObject abis=m.optJSONObject("abis");if(abis!=null)for(String abi:Build.SUPPORTED_ABIS)if(abis.has(abi))return abi;return null;}
    static synchronized Env ensure(Context c,Workspace workspace)throws Exception {
        if(ready!=null&&ready.launcher.canExecute()&&new File(ready.home,".complete").exists())return ready;
        try{ready=install(c,workspace);failure=null;return ready;}catch(Exception e){failure=e.getClass().getSimpleName()+": "+e.getMessage();throw e;}
    }
    private static Env install(Context c,Workspace workspace)throws Exception {
        File exe=launcher(c);JSONObject m=manifest(c);String abi=abi(m);
        if(abi==null||!exe.isFile())throw new IllegalStateException("python_unavailable_for_abi_"+Build.SUPPORTED_ABIS[0]);
        String series=m.getString("series");File base=new File(c.getFilesDir(),"python"),home=new File(base,m.getString("id"));
        if(!new File(home,".complete").exists()){
            File[] old=base.listFiles();if(old!=null)for(File f:old)delete(f);
            File staging=new File(base,".staging-"+UUID.randomUUID()),lib=new File(staging,"lib/python"+series);
            extract(c,"python/"+m.getJSONObject("stdlib").getString("zip"),lib);extract(c,"python/"+m.getJSONObject("abis").getJSONObject(abi).getString("zip"),lib);
            new File(lib,"site-packages").mkdirs();
            if(!staging.renameTo(home)){delete(staging);throw new IOException("python_install_rename_failed");}
            Workspace.write(new File(home,".complete"),m.toString().getBytes(StandardCharsets.UTF_8));
        }
        installHelper(c,new File(home,"lib/python"+series+"/site-packages/ophone.py"));
        File certs=new File(home,"cacert.pem");if(!certs.isFile())writeSystemCertificates(certs);
        Env e=new Env();e.launcher=exe;e.home=home;e.bin=new File(home,"bin");e.version=m.getString("version");e.bin.mkdirs();
        for(String name:new String[]{"python3","python"}){File link=new File(e.bin,name);if(!link.exists())try{Os.symlink(exe.getAbsolutePath(),link.getAbsolutePath());}catch(Exception ignored){}}
        String nativeDir=c.getApplicationInfo().nativeLibraryDir,path=System.getenv("PATH"),libraryPath=System.getenv("LD_LIBRARY_PATH");
        e.vars.put("PYTHONHOME",home.getAbsolutePath());
        e.vars.put("LD_LIBRARY_PATH",libraryPath==null||libraryPath.isEmpty()?nativeDir:nativeDir+":"+libraryPath);
        e.vars.put("PATH",e.bin.getAbsolutePath()+":"+(path==null?"/system/bin":path));
        e.vars.put("SSL_CERT_FILE",certs.getAbsolutePath());
        e.vars.put("PYTHONUSERBASE",new File(workspace.root,".python").getAbsolutePath());
        e.vars.put("PYTHONIOENCODING","utf-8");
        e.vars.put("OPHONE_PYTHON",exe.getAbsolutePath());
        return e;
    }
    /** Keeps the bundled ophone.py status-report helper in site-packages current. */
    private static void installHelper(Context c,File target)throws Exception {
        byte[] wanted;try(InputStream in=c.getAssets().open("python/ophone.py")){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);wanted=out.toByteArray();}
        if(target.isFile()&&Arrays.equals(Workspace.read(target,65536),wanted))return;Workspace.write(target,wanted);
    }
    /** OpenSSL inside Python cannot read Android's hashed certificate directory, so export the system trust store as PEM. */
    private static void writeSystemCertificates(File target)throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidCAStore");store.load(null,null);StringBuilder pem=new StringBuilder();
        for(String alias:Collections.list(store.aliases())){if(!alias.startsWith("system:"))continue;Certificate cert=store.getCertificate(alias);if(cert==null)continue;
            pem.append("-----BEGIN CERTIFICATE-----\n").append(Base64.encodeToString(cert.getEncoded(),Base64.NO_WRAP).replaceAll("(.{64})","$1\n")).append("\n-----END CERTIFICATE-----\n");}
        File tmp=new File(target.getPath()+".tmp");Workspace.write(tmp,pem.toString().getBytes(StandardCharsets.US_ASCII));if(!tmp.renameTo(target))throw new IOException("python_certificates_write_failed");
    }
    private static void extract(Context c,String asset,File dest)throws Exception {
        String root=dest.getCanonicalPath();byte[] b=new byte[65536];
        try(ZipInputStream zip=new ZipInputStream(new BufferedInputStream(c.getAssets().open(asset)))){ZipEntry entry;
            while((entry=zip.getNextEntry())!=null){File f=new File(dest,entry.getName()).getCanonicalFile();if(!f.getPath().startsWith(root+File.separator))throw new SecurityException("python_archive_path_escape");
                if(entry.isDirectory()){f.mkdirs();continue;}f.getParentFile().mkdirs();try(OutputStream out=new FileOutputStream(f)){int n;while((n=zip.read(b))!=-1)out.write(b,0,n);}}}
    }
    private static void delete(File f){File[] children=f.isDirectory()?f.listFiles():null;if(children!=null)for(File child:children)delete(child);f.delete();}
    static synchronized JSONObject status(Context c)throws Exception {
        JSONObject o=new JSONObject().put("bundled",true).put("supported_abis",new JSONArray(Arrays.asList("arm64-v8a","x86_64"))).put("device_abis",new JSONArray(Arrays.asList(Build.SUPPORTED_ABIS)));
        try{JSONObject m=manifest(c);o.put("version",m.getString("version")).put("available",abi(m)!=null&&launcher(c).isFile());}catch(Exception e){o.put("available",false);}
        o.put("installed",ready!=null).put("tool","python_run(code|script) or python3 inside terminal_start").put("status_reports","from ophone import report, step; report(stage, message, progress=0..1, level=info|warn|error)").put("packages","standard library; pip via python3 -m ensurepip, then python3 -m pip install --user <pure-Python package>");
        if(failure!=null)o.put("error",failure);return o;
    }
}
