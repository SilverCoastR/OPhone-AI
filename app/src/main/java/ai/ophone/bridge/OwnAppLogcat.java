package ai.ophone.bridge;

import android.content.Context;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Fixed read-only command, always filtered to this app's UID. Never a shell tool. */
public final class OwnAppLogcat {
    private OwnAppLogcat() {}
    public static JSONObject read(Context context,int requestedLines) throws Exception {
        int limit=Math.max(1,Math.min(200,requestedLines));
        Process process=null;ByteArrayOutputStream out=new ByteArrayOutputStream();boolean finished=false;
        try {
            process=new ProcessBuilder("logcat","-d","-t",String.valueOf(limit),"--uid="+android.os.Process.myUid(),"-v","threadtime")
                    .redirectErrorStream(true).start();
            InputStream in=process.getInputStream();byte[] buffer=new byte[2048];long until=System.currentTimeMillis()+2500;
            while(System.currentTimeMillis()<until&&out.size()<65536) {
                while(in.available()>0&&out.size()<65536) {
                    int count=in.read(buffer,0,Math.min(buffer.length,65536-out.size()));if(count<0)break;out.write(buffer,0,count);
                }
                try {process.exitValue();finished=true;break;}catch(IllegalThreadStateException running){Thread.sleep(10);}
            }
            while(in.available()>0&&out.size()<65536) {int n=in.read(buffer,0,Math.min(buffer.length,65536-out.size()));if(n<0)break;out.write(buffer,0,n);}
            String raw=new String(out.toByteArray(),StandardCharsets.UTF_8);
            String text=DetailedDiagnostics.clean(raw);
            boolean ok=finished&&process.exitValue()==0;
            context.getSharedPreferences("diagnostic_capabilities",0).edit().putBoolean("own_logcat_probed",true).putBoolean("own_logcat_available",ok).apply();
            return new JSONObject().put("ok",ok).put("available",ok).put("scope","OPhoneAI own UID only")
                    .put("system_logcat",false).put("uid",android.os.Process.myUid()).put("text",text)
                    .put("truncated",out.size()>=65536||raw.length()>12000||!finished).put("error",ok?JSONObject.NULL:"own_uid_logcat_unavailable");
        } finally {if(process!=null)process.destroy();}
    }
}
