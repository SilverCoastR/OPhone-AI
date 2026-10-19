package ai.ophone.bridge;

import android.content.Context;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Optional event source; a fixed own-UID stream exists only while a subscription requests it. */
public final class LogcatEventMonitor {
    private static volatile Process process;private static volatile boolean running;private static volatile long retryAfter,lastLineAt;
    private static volatile String error="not_requested";
    private static volatile long epoch;
    private LogcatEventMonitor(){}
    public static synchronized JSONObject status()throws Exception{return new JSONObject().put("running",running).put("scope","own UID only").put("last_line_at",lastLineAt).put("retry_after",retryAfter).put("state",error);}
    public static synchronized void update(Context context){
        if(!AgentEvents.get(context).logcatRequested()){stop();error="not_requested";return;}
        if(running||System.currentTimeMillis()<retryAfter)return;
        running=true;error="starting";final long ticket=++epoch;Context app=context.getApplicationContext();
        new Thread(()->{
            Process owned=null;
            try{
                String since=new SimpleDateFormat("MM-dd HH:mm:ss.SSS",Locale.US).format(new Date());
                owned=new ProcessBuilder("logcat","--uid="+android.os.Process.myUid(),"-v","threadtime","-T",since,"*:W").redirectErrorStream(true).start();synchronized(LogcatEventMonitor.class){if(ticket!=epoch){owned.destroy();return;}process=owned;error="streaming";}
                Pattern pattern=Pattern.compile("^\\d\\d-\\d\\d\\s+\\d\\d:\\d\\d:\\d\\d\\.\\d+\\s+\\d+\\s+\\d+\\s+([WEF])\\s+([^:]{1,80}):\\s?(.*)$");
                try(BufferedReader in=new BufferedReader(new InputStreamReader(owned.getInputStream(),StandardCharsets.UTF_8))){String line;while(running&&ticket==epoch&&(line=in.readLine())!=null){
                    if(line.length()>4096)line=line.substring(0,4096);Matcher match=pattern.matcher(line);if(!match.matches())continue;
                    String level="W".equals(match.group(1))?"warn":"F".equals(match.group(1))?"fatal":"error";String text=DetailedDiagnostics.clean(match.group(3));lastLineAt=System.currentTimeMillis();
                    AgentEvents.get(app).publish("diagnostic","own_logcat",null,"",level,new JSONObject().put("code",match.group(2).trim()).put("message",text.substring(0,Math.min(500,text.length()))).put("uid",android.os.Process.myUid()));
                }}
                if(running&&ticket==epoch)throw new IllegalStateException("own_uid_logcat_stream_ended");
            }catch(Exception failure){if(running&&ticket==epoch){error="unavailable";retryAfter=System.currentTimeMillis()+60000;
                try{AgentEvents.get(app).publish("diagnostic","own_logcat",null,"","error",new JSONObject().put("code","source_unavailable").put("source_unavailable",true).put("message","Own-UID logcat stream is unavailable; no system or other-app access is assumed."));}catch(Exception ignored){}}}
            finally{if(owned!=null)owned.destroy();synchronized(LogcatEventMonitor.class){if(ticket==epoch){process=null;running=false;}}}
        },"OPhoneAI-own-log-events").start();
    }
    public static synchronized void stop(){epoch++;running=false;if(process!=null)process.destroy();process=null;}
}
