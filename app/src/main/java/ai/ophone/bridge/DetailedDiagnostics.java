package ai.ophone.bridge;

import android.content.Context;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Own-app errors and stack locations, queryable by the agent without a computer. */
public final class DetailedDiagnostics {
    private DetailedDiagnostics() {}
    public static String clean(String value) {
        if(value==null) return "";
        String s=value.replaceAll("(?im)\\b(authorization|proxy-authorization|cookie|set-cookie)\\s*[:=]\\s*[^\\r\\n]*", "$1: [redacted]")
                .replaceAll("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+", "Bearer [redacted]")
                .replaceAll("(?i)(password|passwd|secret|token|nonce)([\\\"']?\\s*[:=]\\s*)\\\"[^\\\"\\r\\n]*\\\"", "$1$2\\\"[redacted]\\\"")
                .replaceAll("(?i)(authorization|cookie|password|passwd|secret|token|nonce)([\\\"'\\s:=]+)[^\\s,;\\\"'}]+", "$1$2[redacted]")
                .replaceAll("(?i)(https?://[^\\s?#]+)[?#][^\\s]+","$1?[redacted]");
        return s.substring(0,Math.min(12000,s.length()));
    }
    public static String stack(Throwable error) {
        StringBuilder out=new StringBuilder(); int causes=0;
        while(error!=null && causes++<6) {
            out.append(error.getClass().getName()).append(": ").append(clean(error.getMessage())).append('\n');
            int frames=0;
            for(StackTraceElement frame:error.getStackTrace()) { if(frames++>=45) break; out.append(" at ").append(frame.toString()).append('\n'); }
            error=error.getCause(); if(error!=null) out.append("Caused by: ");
        }
        return clean(out.toString());
    }
    public static void event(Context c,String level,String source,String task,String call,String code,String message,Throwable error) {
        try { AgentStore.get(c).addLog(level,source,task,call,code,clean(message),error==null?"":stack(error)); }
        catch(Exception ignored) { /* Diagnostics must not recursively break execution. */ }
    }
    public static void install(Context c) {
        Context app=c.getApplicationContext();
        File pending=new File(app.getFilesDir(),"pending_agent_crash.json");
        if(pending.isFile()) {
            try {
                java.io.ByteArrayOutputStream buffer=new java.io.ByteArrayOutputStream();
                try(java.io.FileInputStream in=new java.io.FileInputStream(pending)) {
                    byte[] chunk=new byte[2048];int count;
                    while((count=in.read(chunk))!=-1&&buffer.size()<32000)buffer.write(chunk,0,count);
                }
                byte[] bytes=buffer.toByteArray();
                JSONObject crash=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
                AgentStore.get(app).addLog("error","java_crash","","","uncaught_exception",crash.optString("message"),crash.optString("stack"));
                pending.delete();
            } catch(Exception ignored) {}
        }
        Thread.UncaughtExceptionHandler previous=Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread,error)->{
            try {
                JSONObject crash=new JSONObject().put("time_ms",System.currentTimeMillis())
                        .put("message",clean(error.getClass().getName()+": "+error.getMessage())).put("stack",stack(error));
                try(FileOutputStream out=new FileOutputStream(pending)) {
                    out.write(crash.toString().getBytes(StandardCharsets.UTF_8)); out.getFD().sync();
                }
            } catch(Exception ignored) {}
            if(previous!=null) previous.uncaughtException(thread,error);
        });
    }
}
