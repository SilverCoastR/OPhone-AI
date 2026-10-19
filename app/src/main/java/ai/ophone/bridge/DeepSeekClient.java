package ai.ophone.bridge;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** OpenAI Chat Completions compatible DeepSeek transport with SSE tool-call assembly. */
public final class DeepSeekClient {
    static final String CHAT_URL="https://api.deepseek.com/chat/completions";
    static final String MODELS_URL="https://api.deepseek.com/models";
    private volatile HttpURLConnection active;
    public interface StreamListener {void onText(String text);void onThinking();}
    public static final class Result {
        public final JSONObject message;public final JSONObject usage;public final String finishReason;
        Result(JSONObject message,JSONObject usage,String finish){this.message=message;this.usage=usage;this.finishReason=finish;}
    }
    public void cancel(){HttpURLConnection c=active;if(c!=null)c.disconnect();}

    public String testConnection(String apiKey) throws Exception {
        HttpURLConnection c=open(MODELS_URL,"GET",apiKey);active=c;
        try {int status=c.getResponseCode();String body=readBounded(status>=200&&status<300?c.getInputStream():c.getErrorStream(),131072);
            if(status<200||status>=300)throw new IllegalStateException(httpError(status,body));
            JSONObject parsed=new JSONObject(body);if(parsed.optJSONArray("data")==null)throw new IllegalStateException("DeepSeek returned an invalid model list");
            return "DeepSeek connected · "+parsed.getJSONArray("data").length()+" models visible";
        } finally {active=null;c.disconnect();}
    }
    public Result stream(String apiKey,ModelApiStore.Config config,JSONArray messages,JSONArray tools,StreamListener listener) throws Exception {
        JSONObject request=buildRequest(config,messages,tools);
        HttpURLConnection c=open(CHAT_URL,"POST",apiKey);active=c;c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");
        byte[] bytes=request.toString().getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(bytes.length);
        try(OutputStream out=c.getOutputStream()){out.write(bytes);}
        try {int status=c.getResponseCode();if(status<200||status>=300)throw new IllegalStateException(httpError(status,readBounded(c.getErrorStream(),131072)));
            BufferedReader reader=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8));
            Accumulator accumulator=new Accumulator(listener);String line;
            while((line=reader.readLine())!=null){if(line.startsWith("data:"))accumulator.accept(line.substring(5).trim());}
            return accumulator.result();
        } finally {active=null;c.disconnect();}
    }
    static JSONObject buildRequest(ModelApiStore.Config c,JSONArray messages,JSONArray tools) throws Exception {
        JSONObject out=new JSONObject().put("model",c.model).put("messages",messages).put("stream",true)
                .put("stream_options",new JSONObject().put("include_usage",true)).put("max_tokens",c.maxTokens)
                .put("thinking",new JSONObject().put("type",c.thinking?"enabled":"disabled"));
        if(c.thinking&& !"none".equals(c.reasoningEffort))out.put("reasoning_effort",c.reasoningEffort);
        // DeepSeek V4 thinking + tools rejects tool_choice. In thinking mode the service
        // chooses tools automatically and requires reasoning_content replay on the next turn.
        if(tools!=null&&tools.length()>0){out.put("tools",tools);if(!c.thinking)out.put("tool_choice","auto");}
        return out;
    }
    static Result parseFixture(String fixture,StreamListener listener) throws Exception {
        String[] lines=fixture.split("\\r?\\n");List<String> events=new ArrayList<>();
        for(String line:lines)if(line.startsWith("data:"))events.add(line.substring(5).trim());
        return parseEvents(events,listener);
    }
    private static Result parseEvents(List<String> events,StreamListener listener) throws Exception {
        Accumulator accumulator=new Accumulator(listener);for(String data:events)accumulator.accept(data);return accumulator.result();
    }
    private static final class Accumulator {
        final StreamListener listener;final StringBuilder text=new StringBuilder(),reasoning=new StringBuilder();final JSONArray calls=new JSONArray();JSONObject usage=new JSONObject();String finish="";boolean thinkingSent;
        Accumulator(StreamListener listener){this.listener=listener;}
        void accept(String data) throws Exception {if(data.isEmpty()||"[DONE]".equals(data))return;JSONObject chunk=new JSONObject(data);if(chunk.optJSONObject("usage")!=null)usage=chunk.getJSONObject("usage");JSONArray choices=chunk.optJSONArray("choices");if(choices==null)return;
            for(int i=0;i<choices.length();i++){JSONObject choice=choices.getJSONObject(i);if(!choice.isNull("finish_reason"))finish=choice.optString("finish_reason");JSONObject delta=choice.optJSONObject("delta");if(delta==null)continue;String thought=nullableString(delta,"reasoning_content");if(!thought.isEmpty()){reasoning.append(thought);if(!thinkingSent){thinkingSent=true;if(listener!=null)listener.onThinking();}}String part=nullableString(delta,"content");if(!part.isEmpty()){text.append(part);if(listener!=null)listener.onText(part);}JSONArray chunks=delta.optJSONArray("tool_calls");if(chunks!=null)for(int j=0;j<chunks.length();j++)mergeToolCall(calls,chunks.getJSONObject(j));}}
        Result result() throws Exception {JSONObject message=new JSONObject().put("role","assistant").put("content",text.toString());if(reasoning.length()>0)message.put("reasoning_content",reasoning.toString());if(calls.length()>0)message.put("tool_calls",calls);return new Result(message,usage,finish);}
    }
    private static String nullableString(JSONObject value,String key){return value==null||value.isNull(key)?"":value.optString(key,"");}
    private static void mergeToolCall(JSONArray assembled,JSONObject delta) throws Exception {
        int index=delta.optInt("index",0);while(assembled.length()<=index)assembled.put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","").put("arguments","")));
        JSONObject target=assembled.getJSONObject(index);if(delta.has("id"))target.put("id",delta.optString("id"));if(delta.has("type"))target.put("type",delta.optString("type"));
        JSONObject fn=delta.optJSONObject("function");if(fn!=null){JSONObject dst=target.getJSONObject("function");if(fn.has("name"))dst.put("name",dst.optString("name")+fn.optString("name"));if(fn.has("arguments"))dst.put("arguments",dst.optString("arguments")+fn.optString("arguments"));}
    }
    private static HttpURLConnection open(String url,String method,String apiKey) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setRequestMethod(method);c.setConnectTimeout(15000);c.setReadTimeout(300000);c.setUseCaches(false);
        c.setRequestProperty("Accept","application/json, text/event-stream");c.setRequestProperty("Authorization","Bearer "+apiKey);c.setRequestProperty("User-Agent","OPhoneAI/1.0");return c;
    }
    private static String readBounded(InputStream in,int max) throws Exception {if(in==null)return "";ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int read,total=0;while((read=in.read(buffer))!=-1&&total<max){int n=Math.min(read,max-total);out.write(buffer,0,n);total+=n;}return new String(out.toByteArray(),StandardCharsets.UTF_8);}
    private static String httpError(int status,String body){String safe=DetailedDiagnostics.clean(body==null?"":body);if(safe.length()>600)safe=safe.substring(0,600);return "DeepSeek HTTP "+status+(safe.trim().isEmpty()?"":" · "+safe.trim());}
}
