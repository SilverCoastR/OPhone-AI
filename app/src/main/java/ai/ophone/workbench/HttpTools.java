package ai.ophone.workbench;

import android.webkit.CookieManager;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class HttpTools {
    static URL checked(String value)throws Exception {URL u=new URL(value);if(!Arrays.asList("http","https").contains(u.getProtocol())||u.getHost().isEmpty()||u.getUserInfo()!=null)throw new IllegalArgumentException("http_https_URL_without_embedded_credentials_required");return u;}
    static boolean sameOrigin(URL a,URL b){return a.getProtocol().equals(b.getProtocol())&&a.getHost().equalsIgnoreCase(b.getHost())&&port(a)==port(b);}
    static int port(URL u){return u.getPort()<0?u.getDefaultPort():u.getPort();}
    static JSONObject request(Workspace files,JSONObject a)throws Exception {
        URL url=checked(a.getString("url"));String method=a.optString("method","GET").toUpperCase(Locale.ROOT);
        if(!Arrays.asList("GET","HEAD","POST","PUT","PATCH","DELETE","OPTIONS").contains(method))throw new IllegalArgumentException("unsupported_HTTP_method");
        JSONObject headers=a.optJSONObject("headers");if(headers==null)headers=new JSONObject();headers=new JSONObject(headers.toString());
        byte[] body=a.has("body")?a.getString("body").getBytes(StandardCharsets.UTF_8):null;
        if(body!=null&&body.length>262144)throw new IOException("request_body_max_256KiB");
        if(body!=null&&Arrays.asList("GET","HEAD").contains(method))throw new IllegalArgumentException("body_not_supported_for_GET_HEAD");
        long deadline=System.currentTimeMillis()+Math.min(15000,Math.max(1000,a.optInt("timeout_ms",10000)));
        JSONArray redirects=new JSONArray();
        for(int hop=0;hop<=5;hop++) {
            HttpURLConnection c=(HttpURLConnection)url.openConnection();
            try {
                int remaining=(int)(deadline-System.currentTimeMillis());if(remaining<=0)throw new java.net.SocketTimeoutException("HTTP_total_deadline");
                c.setConnectTimeout(Math.min(5000,remaining));c.setReadTimeout(Math.min(5000,remaining));c.setInstanceFollowRedirects(false);c.setRequestMethod(method);
                Iterator<String> keys=headers.keys();while(keys.hasNext()){String k=keys.next(),v=headers.getString(k);if(!k.matches("[A-Za-z0-9!#$%&'*+.^_`|~-]+")||v.contains("\r")||v.contains("\n"))throw new IllegalArgumentException("invalid_HTTP_header");c.setRequestProperty(k,v);}
                if(a.optBoolean("use_browser_cookies")){String cookie=CookieManager.getInstance().getCookie(url.toString());if(cookie!=null)c.setRequestProperty("Cookie",cookie);}
                if(body!=null){c.setDoOutput(true);c.setFixedLengthStreamingMode(body.length);try(OutputStream os=c.getOutputStream()){os.write(body);}}
                int status=c.getResponseCode();Map<String,List<String>> response=c.getHeaderFields();
                if(a.optBoolean("use_browser_cookies"))for(Map.Entry<String,List<String>> h:response.entrySet())if("Set-Cookie".equalsIgnoreCase(h.getKey()))for(String cookie:h.getValue())CookieManager.getInstance().setCookie(url.toString(),cookie);
                String location=c.getHeaderField("Location");
                if(a.optBoolean("follow_redirects",true)&&location!=null&&Arrays.asList(301,302,303,307,308).contains(status)) {
                    if(hop==5)throw new IOException("HTTP_redirect_limit");URL next=checked(new URL(url,location).toString());
                    if("https".equals(url.getProtocol())&&"http".equals(next.getProtocol()))throw new SecurityException("HTTPS_redirect_downgrade_rejected");
                    if(!sameOrigin(url,next)){for(String key:new ArrayList<String>(jsonKeys(headers)))if(!Arrays.asList("accept","accept-language","content-type","user-agent").contains(key.toLowerCase(Locale.ROOT)))headers.remove(key);}
                    redirects.put(new JSONObject().put("status",status).put("url",url.toString()));url=next;
                    if(status==303||((status==301||status==302)&&"POST".equals(method))){method="GET";body=null;}
                    continue;
                }
                int max=Math.min(8*1024*1024,Math.max(1,a.optInt("max_bytes",1048576)));ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                InputStream stream=status>=400?c.getErrorStream():c.getInputStream();boolean truncated=false;
                if(stream!=null)try(InputStream in=stream){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(System.currentTimeMillis()>deadline)throw new java.net.SocketTimeoutException("HTTP_total_deadline");if(bytes.size()+n>max){bytes.write(b,0,max-bytes.size());truncated=true;break;}bytes.write(b,0,n);}}
                byte[] data=bytes.toByteArray();JSONObject safeHeaders=new JSONObject();for(Map.Entry<String,List<String>> h:response.entrySet())if(h.getKey()!=null)safeHeaders.put(h.getKey(),"set-cookie".equalsIgnoreCase(h.getKey())?"[cookie stored only in workbench profile when requested]":new JSONArray(h.getValue()));
                JSONObject out=new JSONObject().put("ok",status>=200&&status<400).put("status",status).put("url",url.toString()).put("method",method).put("headers",safeHeaders).put("redirects",redirects).put("bytes",data.length).put("truncated",truncated).put("sha256",Workspace.digest(data));
                String mime=c.getContentType();out.put("content_type",mime==null?"":mime);
                if(mime==null||mime.contains("text")||mime.contains("json")||mime.contains("xml"))out.put("text",new String(data,0,Math.min(data.length,16384),StandardCharsets.UTF_8)).put("text_truncated",data.length>16384);
                else out.put("body_base64_preview",android.util.Base64.encodeToString(Arrays.copyOf(data,Math.min(2048,data.length)),android.util.Base64.NO_WRAP));
                if(a.has("output_file")){if(truncated)throw new IOException("response_too_large_no_partial_file_saved");File f=files.resolve(a.getString("output_file"));Workspace.write(f,data);out.put("file",files.stat(a.getString("output_file")));}
                if(status>=400)out.put("error","HTTP_"+status);return out;
            } finally {c.disconnect();}
        }
        throw new IOException("HTTP_redirect_limit");
    }
    private static List<String> jsonKeys(JSONObject o){List<String> keys=new ArrayList<>();Iterator<String> it=o.keys();while(it.hasNext())keys.add(it.next());return keys;}
}
