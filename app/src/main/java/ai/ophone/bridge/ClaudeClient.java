package ai.ophone.bridge;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Anthropic Messages API transport. The local conversation keeps the Chat Completions shape; this class converts it
 * to Messages blocks (tool_use / tool_result / image) and assembles the streamed reply back into that shape.
 */
final class ClaudeClient implements ModelClient {
    private static final String VERSION="2023-06-01";
    private volatile HttpURLConnection active;

    @Override public void cancel(){HttpURLConnection c=active;if(c!=null)c.disconnect();}

    @Override public String testConnection(String apiKey,ModelApiStore.Config config) throws Exception {return "Claude connected · "+listModels(apiKey,config).size()+" models visible";}

    @Override public List<String> listModels(String apiKey,ModelApiStore.Config config) throws Exception {
        HttpURLConnection c=open(config.modelsUrl()+"?limit=100","GET",apiKey);active=c;
        try{int status=c.getResponseCode();String body=DeepSeekClient.readBounded(status>=200&&status<300?c.getInputStream():c.getErrorStream(),1048576);
            if(status<200||status>=300)throw new IllegalStateException(DeepSeekClient.httpError("Claude",status,body));
            JSONArray data=new JSONObject(body).optJSONArray("data");if(data==null)throw new IllegalStateException("Claude returned an invalid model list");
            List<String> out=new ArrayList<>();for(int i=0;i<data.length();i++){String id=data.optJSONObject(i)==null?"":data.optJSONObject(i).optString("id");if(!id.isEmpty())out.add(id);}Collections.sort(out);return out;
        }finally{active=null;c.disconnect();}
    }

    @Override public DeepSeekClient.Result stream(String apiKey,ModelApiStore.Config config,JSONArray messages,JSONArray tools,DeepSeekClient.StreamListener listener) throws Exception {
        byte[] bytes=buildRequest(config,messages,tools).toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c=open(config.chatUrl(),"POST",apiKey);active=c;c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");c.setFixedLengthStreamingMode(bytes.length);
        try(OutputStream out=c.getOutputStream()){out.write(bytes);}
        try{int status=c.getResponseCode();if(status<200||status>=300)throw new IllegalStateException(DeepSeekClient.httpError("Claude",status,DeepSeekClient.readBounded(c.getErrorStream(),131072)));
            BufferedReader reader=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8));Accumulator acc=new Accumulator(listener);String line;
            while((line=reader.readLine())!=null)if(line.startsWith("data:"))acc.accept(line.substring(5).trim());
            return acc.result();
        }finally{active=null;c.disconnect();}
    }

    static JSONObject buildRequest(ModelApiStore.Config c,JSONArray messages,JSONArray tools) throws Exception {
        StringBuilder system=new StringBuilder();JSONArray out=new JSONArray();
        for(int i=0;i<messages.length();i++){JSONObject m=messages.getJSONObject(i);String role=m.optString("role");
            if("system".equals(role)){if(system.length()>0)system.append("\n\n");system.append(m.optString("content"));continue;}
            JSONArray blocks=new JSONArray();
            if("tool".equals(role)){JSONObject result=new JSONObject().put("type","tool_result").put("tool_use_id",DirectModelAgent.safeToolId(m.optString("tool_call_id")));Object content=m.opt("content");
                if(content instanceof JSONArray)result.put("content",convertParts((JSONArray)content));else result.put("content",String.valueOf(content==null?"":content));
                blocks.put(result);add(out,"user",blocks);continue;}
            Object content=m.opt("content");
            if(content instanceof JSONArray)blocks=convertParts((JSONArray)content);
            else{String text=content==null||content==JSONObject.NULL?"":String.valueOf(content);if(!text.trim().isEmpty())blocks.put(new JSONObject().put("type","text").put("text",text));}
            JSONArray calls=m.optJSONArray("tool_calls");
            for(int k=0;calls!=null&&k<calls.length();k++){JSONObject call=calls.getJSONObject(k),fn=call.optJSONObject("function");if(fn==null)continue;JSONObject input;try{input=new JSONObject(fn.optString("arguments","{}"));}catch(Exception bad){input=new JSONObject();}
                blocks.put(new JSONObject().put("type","tool_use").put("id",DirectModelAgent.safeToolId(call.optString("id"))).put("name",fn.optString("name")).put("input",input));}
            if(blocks.length()==0)continue;
            add(out,"assistant".equals(role)?"assistant":"user",blocks);
        }
        // The Messages API starts with a user turn.
        if(out.length()==0||!"user".equals(out.getJSONObject(0).optString("role"))){JSONArray first=new JSONArray().put(new JSONObject().put("type","text").put("text","(Conversation continues.)"));JSONArray shifted=new JSONArray().put(new JSONObject().put("role","user").put("content",first));for(int i=0;i<out.length();i++)shifted.put(out.get(i));out=shifted;}
        JSONObject request=new JSONObject().put("model",c.model).put("max_tokens",c.maxTokens).put("stream",true).put("messages",out);
        if(system.length()>0)request.put("system",system.toString());
        if(tools!=null&&tools.length()>0){JSONArray converted=new JSONArray();for(int i=0;i<tools.length();i++){JSONObject fn=tools.getJSONObject(i).optJSONObject("function");if(fn==null)continue;
            converted.put(new JSONObject().put("name",fn.optString("name")).put("description",fn.optString("description")).put("input_schema",fn.optJSONObject("parameters")==null?new JSONObject().put("type","object"):fn.getJSONObject("parameters")));}
            request.put("tools",converted);}
        return request;
    }
    /** Consecutive turns of one role become one turn: tool results after several calls, guidance after a result. */
    private static void add(JSONArray out,String role,JSONArray blocks) throws Exception {
        JSONObject last=out.length()>0?out.getJSONObject(out.length()-1):null;
        if(last!=null&&role.equals(last.optString("role"))){JSONArray existing=last.getJSONArray("content");for(int i=0;i<blocks.length();i++)existing.put(blocks.get(i));return;}
        out.put(new JSONObject().put("role",role).put("content",blocks));
    }
    private static JSONArray convertParts(JSONArray parts) throws Exception {
        JSONArray out=new JSONArray();
        for(int i=0;i<parts.length();i++){JSONObject p=parts.optJSONObject(i);if(p==null)continue;
            if("text".equals(p.optString("type")))out.put(new JSONObject().put("type","text").put("text",p.optString("text")));
            else if("image_url".equals(p.optString("type"))){String url=p.optJSONObject("image_url")==null?"":p.getJSONObject("image_url").optString("url");int comma=url.indexOf(',');
                if(url.startsWith("data:")&&comma>0){String media=url.substring(5,url.indexOf(';')>0?url.indexOf(';'):comma);out.put(new JSONObject().put("type","image").put("source",new JSONObject().put("type","base64").put("media_type",media).put("data",url.substring(comma+1))));}}}
        return out;
    }

    static DeepSeekClient.Result parseFixture(String fixture,DeepSeekClient.StreamListener listener) throws Exception {Accumulator acc=new Accumulator(listener);for(String line:fixture.split("\\r?\\n"))if(line.startsWith("data:"))acc.accept(line.substring(5).trim());return acc.result();}

    private static final class Accumulator {
        final DeepSeekClient.StreamListener listener;final StringBuilder text=new StringBuilder();final List<JSONObject> calls=new ArrayList<>();final java.util.Map<Integer,JSONObject> byIndex=new java.util.HashMap<>();
        JSONObject usage=new JSONObject();String finish="";boolean thinkingSent;
        Accumulator(DeepSeekClient.StreamListener listener){this.listener=listener;}
        void accept(String data) throws Exception {if(data.isEmpty())return;JSONObject e=new JSONObject(data);String type=e.optString("type");
            if("error".equals(type)){JSONObject err=e.optJSONObject("error");throw new IllegalStateException("Claude "+(err==null?"error":err.optString("type")+" · "+DetailedDiagnostics.clean(err.optString("message"))));}
            if("message_start".equals(type)){JSONObject u=e.optJSONObject("message")==null?null:e.getJSONObject("message").optJSONObject("usage");if(u!=null)usage=u;return;}
            if("content_block_start".equals(type)){JSONObject block=e.optJSONObject("content_block");if(block==null)return;
                if("tool_use".equals(block.optString("type"))){JSONObject call=new JSONObject().put("id",block.optString("id")).put("type","function").put("function",new JSONObject().put("name",block.optString("name")).put("arguments",""));calls.add(call);byIndex.put(e.optInt("index"),call);}
                else if("thinking".equals(block.optString("type"))&&!thinkingSent){thinkingSent=true;if(listener!=null)listener.onThinking();}
                return;}
            if("content_block_delta".equals(type)){JSONObject d=e.optJSONObject("delta");if(d==null)return;String kind=d.optString("type");
                if("text_delta".equals(kind)){String part=d.optString("text");text.append(part);if(listener!=null&&!part.isEmpty())listener.onText(part);}
                else if("input_json_delta".equals(kind)){JSONObject call=byIndex.get(e.optInt("index"));if(call!=null){JSONObject fn=call.getJSONObject("function");fn.put("arguments",fn.optString("arguments")+d.optString("partial_json"));}}
                else if("thinking_delta".equals(kind)&&!thinkingSent){thinkingSent=true;if(listener!=null)listener.onThinking();}
                return;}
            if("message_delta".equals(type)){JSONObject d=e.optJSONObject("delta");if(d!=null&&!d.isNull("stop_reason"))finish="tool_use".equals(d.optString("stop_reason"))?"tool_calls":d.optString("stop_reason");JSONObject u=e.optJSONObject("usage");if(u!=null)usage.put("output_tokens",u.optInt("output_tokens"));}
        }
        DeepSeekClient.Result result() throws Exception {JSONObject message=new JSONObject().put("role","assistant").put("content",text.toString());
            if(!calls.isEmpty()){JSONArray out=new JSONArray();for(JSONObject call:calls){JSONObject fn=call.getJSONObject("function");if(fn.optString("arguments").trim().isEmpty())fn.put("arguments","{}");out.put(call);}message.put("tool_calls",out);}
            return new DeepSeekClient.Result(message,usage,finish);}
    }

    private static HttpURLConnection open(String url,String method,String apiKey) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setRequestMethod(method);c.setConnectTimeout(15000);c.setReadTimeout(300000);c.setUseCaches(false);
        c.setRequestProperty("Accept","application/json, text/event-stream");c.setRequestProperty("x-api-key",apiKey);c.setRequestProperty("anthropic-version",VERSION);c.setRequestProperty("User-Agent","OPhoneAI/1.0");return c;
    }
}
