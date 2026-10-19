package ai.ophone.bridge;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Owns the DeepSeek planning/tool loop. Native TaskController remains the completion authority. */
public final class DirectModelAgent {
    public interface Ui {
        void onStatus(String value);void onResponseStart();void onText(String value);
        void onTool(String name);void onProgress(String value);void onFinished();void onError(String safeMessage);
    }
    private static final ConcurrentHashMap<String,DirectModelAgent> ACTIVE=new ConcurrentHashMap<>();
    private static final Ui HEADLESS=new Ui(){public void onStatus(String value){}public void onResponseStart(){}public void onText(String value){}public void onTool(String name){}public void onProgress(String value){}public void onFinished(){}public void onError(String value){}};
    private final Context context;private final ModelApiStore settings;private final ApiConversationStore history;
    private final LocalToolKernel kernel;private final DeepSeekClient client=new DeepSeekClient();private final ConcurrentLinkedQueue<JSONObject> guidance=new ConcurrentLinkedQueue<>();
    private volatile boolean running,cancelled;private volatile Thread heartbeat;private volatile Ui ui=HEADLESS;
    private volatile String ownedConversation="",taskId="",terminationReason="";

    DirectModelAgent(Context c){context=c.getApplicationContext();settings=new ModelApiStore(c);history=new ApiConversationStore(c);kernel=new LocalToolKernel(c);}
    public boolean isRunning(){String id=ownedConversation.isEmpty()?history.conversationId():ownedConversation;return running||ACTIVE.containsKey(id);}
    static boolean isConversationRunning(String conversation){return conversation!=null&&ACTIVE.containsKey(conversation);}
    public void attachUi(Ui next){Ui safe=next==null?HEADLESS:next;DirectModelAgent current=ACTIVE.get(history.conversationId());if(current!=null&&current!=this){current.ui=safe;return;}ui=safe;}
    public void cancel(){terminate();}
    public void terminate(){DirectModelAgent current=ACTIVE.get(history.conversationId());if(current!=null&&current!=this){current.terminateLocal(current.context.getString(R.string.agent_terminate_reason),true);return;}terminateLocal(context.getString(R.string.agent_terminate_reason),true);}
    private void terminateLocal(String reason,boolean cascade){cancelled=true;terminationReason=reason;client.cancel();stopHeartbeat();String conversation=ownedConversation;if(!conversation.isEmpty()){progress(context.getString(R.string.agent_terminating));if(cascade)new Thread(()->PlatformSession.cancelConversation(context,conversation,reason),"OPhoneAI-task-cancel").start();}}
    static void stopProvider(String provider){if(!PlatformSession.DEEPSEEK.equals(provider))return;for(DirectModelAgent agent:ACTIVE.values())agent.terminateLocal(agent.context.getString(R.string.agent_provider_exit_reason),false);}
    public void exitPlatform(){terminateLocal(context.getString(R.string.agent_provider_exit_reason),false);PlatformSession.cancelProvider(context,PlatformSession.DEEPSEEK);PlatformPreference.clear(context);}
    public JSONArray history(){return history.messages();}
    public void clear(){if(isRunning())throw new IllegalStateException("agent_is_running");history.create("deepseek");}

    public boolean guide(String input){String text=input==null?"":input.trim();if(text.isEmpty())return false;DirectModelAgent current=ACTIVE.get(history.conversationId());if(current!=null&&current!=this)return current.guideLocal(text);return guideLocal(text);}
    private boolean guideLocal(String text){if(!running||ownedConversation.isEmpty())return false;try{JSONObject message=new JSONObject().put("role","user").put("content",text).put("provider","deepseek").put("created_at",System.currentTimeMillis()).put("guidance",true).put("guidance_id",UUID.randomUUID().toString());history.append(ownedConversation,message);guidance.add(message);progress(context.getString(R.string.agent_guidance_received));return true;}catch(Exception error){throw new IllegalStateException(error);}}

    public void send(final String input,final Ui next){final String text=input==null?"":input.trim();if(text.isEmpty())throw new IllegalArgumentException("empty_message");if(isRunning())throw new IllegalStateException("agent_is_running");start(text,next,false);}
    private void start(String text,Ui next,boolean internal){String conversation=history.conversationId();if(ACTIVE.putIfAbsent(conversation,this)!=null)throw new IllegalStateException("agent_is_running");ownedConversation=conversation;ui=next==null?HEADLESS:next;running=true;cancelled=false;terminationReason="";new Thread(()->run(text,internal,conversation),"OPhoneAI-DeepSeek-agent").start();}
    private void run(String input,boolean internalResume,String conversation){
        try {
            if(!settings.hasKey())throw new IllegalStateException(context.getString(R.string.model_api_key_required));
            history.setProvider("deepseek");history.append(conversation,new JSONObject().put("role","user").put("content",input).put("provider","deepseek").put("created_at",System.currentTimeMillis()).put("internal",internalResume));
            progress(context.getString(internalResume?R.string.agent_restoring:R.string.agent_request_received));
            String human="api-"+UUID.randomUUID().toString();JSONObject observed=TaskController.get(context).bridge("observe_request",new JSONObject().put("conversation",conversation).put("human_key",human).put("goal",input).put("guidance_if_active",true));
            JSONObject task=observed.optBoolean("managed")?observed.getJSONObject("task"):null;if(task!=null){taskId=task.getString("id");startHeartbeat(conversation,taskId);progress(observed.optBoolean("continued")?context.getString(R.string.agent_guidance_merged):context.getString(R.string.agent_task_created,task.optString("goal")));}
            JSONArray fullTools=openAiTools(kernel.mcpTools()),tools=task==null?discoveryTools(fullTools):fullTools,live=history.messages(conversation);ModelApiStore.Config config=settings.config();int calls=0,guards=0,protocolRetries=0,turn=0;
            while(!cancelled){drainGuidance(live);boolean managed=task!=null;String planning=context.getString(managed?R.string.agent_planning_task:R.string.agent_deciding_capability);ui.onStatus(planning);progress(context.getString(R.string.agent_processing_step,++turn));ui.onResponseStart();final StringBuilder turnText=new StringBuilder();final boolean[] thinking={false};
                DeepSeekClient.Result result=client.stream(settings.apiKey(),config,requestMessages(live,managed,!managed),tools,new DeepSeekClient.StreamListener(){
                    public void onText(String value){turnText.append(value);}public void onThinking(){if(!thinking[0]){thinking[0]=true;ui.onStatus(context.getString(R.string.agent_thinking));progress(context.getString(R.string.agent_analyzing));}}
                });
                if(cancelled)break;result.message.put("provider","deepseek").put("created_at",System.currentTimeMillis());history.append(conversation,result.message);live.put(new JSONObject(result.message.toString()));
                JSONArray toolCalls=result.message.optJSONArray("tool_calls");
                if((toolCalls==null||toolCalls.length()==0)&&looksLikeProtocol(result.message.optString("content"))){JSONArray recovered=protocolToolCalls(result.message.optString("content"));if(recovered.length()>0){result.message.put("content","").put("tool_calls",recovered).put("protocol_recovered",true);history.replaceLastAssistant(conversation,result.message);live.remove(live.length()-1);live.put(new JSONObject(result.message.toString()));toolCalls=recovered;progress(context.getString(R.string.agent_protocol_recovered));}}
                if(toolCalls!=null&&toolCalls.length()>0){
                    for(int i=0;i<toolCalls.length();i++){
                        if(cancelled)break;if(++calls>80)throw new IllegalStateException(context.getString(R.string.agent_tool_limit));JSONObject call=toolCalls.getJSONObject(i),fn=call.getJSONObject("function");String name=fn.optString("name"),providerId=call.optString("id","tool-"+UUID.randomUUID());
                        ui.onTool(name);progress(context.getString(R.string.agent_executing_tool,name));JSONObject args;try{args=new JSONObject(fn.optString("arguments","{}"));}catch(Exception malformed){args=new JSONObject();}
                        if(task==null&&!"capabilities".equals(name))throw new IllegalStateException("discovery_phase_only_allows_capabilities");
                        JSONObject scope=task==null?null:new JSONObject().put("conversation",conversation).put("task_id",task.getString("id")).put("call_id","api-"+UUID.randomUUID());JSONObject value;
                        try {value=kernel.call(name,args,scope);}catch(Throwable error){value=new JSONObject().put("ok",false).put("error",DetailedDiagnostics.clean(error.getClass().getSimpleName()+": "+error.getMessage()));}
                        progress(context.getString(value.optBoolean("ok")?R.string.agent_tool_completed:R.string.agent_tool_error,name));JSONObject persisted=toolMessage(providerId,name,value,config.model,false).put("provider","deepseek").put("created_at",System.currentTimeMillis()),wire=toolMessage(providerId,name,value,config.model,true);history.append(conversation,persisted);live.put(wire);
                        if(task==null&&"capabilities".equals(name)&&value.optBoolean("ok")){
                            task=TaskController.get(context).begin(conversation,human,input);taskId=task.getString("id");startHeartbeat(conversation,taskId);tools=fullTools;
                            progress(context.getString(R.string.agent_capability_attached));
                        }
                        if(task!=null)try{TaskController.get(context).bridge("feedback",new JSONObject().put("conversation",conversation).put("task_id",task.getString("id")).put("call_id",scope.getString("call_id")));}catch(Exception ignored){}
                    }
                    continue;
                }
                String finalText=turnText.length()>0?turnText.toString():result.message.optString("content");
                if(looksLikeProtocol(finalText)){result.message.put("internal",true).put("hidden_reason","protocol_markup_suppressed");history.replaceLastAssistant(conversation,result.message);if(++protocolRetries>2)throw new IllegalStateException(context.getString(R.string.agent_protocol_retry_error));JSONObject guard=new JSONObject().put("role","user").put("content","[OPhone AI protocol guard] Never print DSML, XML tool tags, tool-call JSON, or internal protocol. Use native function calls when tools are available; otherwise answer in ordinary language.").put("internal",true).put("provider","deepseek");history.append(conversation,guard);live.put(new JSONObject(guard.toString()));continue;}
                if(task!=null){JSONObject status=TaskController.get(context).status(new JSONObject().put("conversation",conversation).put("task_id",task.getString("id")),new JSONObject());task=status.getJSONObject("task");if("waiting_event".equals(task.optString("status"))){String notice=finalText.isEmpty()?context.getString(R.string.agent_waiting_notice):finalText;progress(context.getString(R.string.agent_waiting_progress));ui.onStatus(context.getString(R.string.agent_waiting_native_event));ui.onText(notice);ui.onFinished();return;}if(!TaskController.terminal(task.optString("status"))){if(++guards>3)throw new IllegalStateException(context.getString(R.string.agent_nonterminal_error));progress(context.getString(R.string.agent_task_incomplete));JSONObject guard=new JSONObject().put("role","user").put("content","[OPhone AI native guard] The original phone task is still nonterminal. Read task_status, continue autonomously, verify fresh evidence, and call task_finish. Do not give a final answer yet.").put("internal",true);history.append(conversation,guard);live.put(new JSONObject(guard.toString()));continue;}progress(context.getString(R.string.agent_task_status,task.optString("status")));}
                if(!finalText.isEmpty())ui.onText(finalText);ui.onFinished();return;
            }
            ui.onStatus(terminationReason.isEmpty()?context.getString(R.string.task_terminated):terminationReason);ui.onFinished();
        } catch(Throwable error){if(cancelled){ui.onStatus(terminationReason.isEmpty()?context.getString(R.string.task_terminated):terminationReason);ui.onFinished();}else ui.onError(DetailedDiagnostics.clean(error.getMessage()==null?error.getClass().getSimpleName():error.getMessage()));}
        finally {stopHeartbeat();running=false;taskId="";ACTIVE.remove(conversation,this);}
    }
    private void drainGuidance(JSONArray live)throws Exception{JSONObject message;while((message=guidance.poll())!=null){String id=message.optString("guidance_id");boolean present=false;for(int i=0;i<live.length();i++)if(id.equals(live.optJSONObject(i)==null?"":live.optJSONObject(i).optString("guidance_id"))){present=true;break;}if(!present)live.put(new JSONObject(message.toString()));}}
    private void progress(String text){if(text==null||text.trim().isEmpty()||ownedConversation.isEmpty())return;String clean=text.trim();try{history.append(ownedConversation,new JSONObject().put("role","progress").put("content",clean).put("provider","deepseek").put("created_at",System.currentTimeMillis()));}catch(Exception ignored){}try{ui.onProgress(clean);}catch(Exception ignored){}}
    private void startHeartbeat(String conversation,String id){stopHeartbeat();heartbeat=new Thread(()->{while(!Thread.currentThread().isInterrupted()&&!cancelled){try{TaskController.get(context).bridge("heartbeat",new JSONObject().put("conversation",conversation).put("task_id",id));Thread.sleep(5000);}catch(InterruptedException done){Thread.currentThread().interrupt();break;}catch(Exception ignored){try{Thread.sleep(2000);}catch(InterruptedException done){Thread.currentThread().interrupt();break;}}}},"OPhoneAI-DeepSeek-heartbeat");heartbeat.start();}
    private void stopHeartbeat(){Thread thread=heartbeat;heartbeat=null;if(thread!=null)thread.interrupt();}
    public static void supervise(Context context){try{ModelApiStore settings=new ModelApiStore(context);if(!settings.hasKey())return;ApiConversationStore history=new ApiConversationStore(context);String conversation=history.conversationId();if(ACTIVE.containsKey(conversation))return;DirectModelAgent agent=new DirectModelAgent(context);JSONObject task=TaskController.get(context).claimDirectResume(conversation);if(task==null)return;agent.start(agent.context.getString(R.string.agent_continue),HEADLESS,true);}catch(Exception error){DetailedDiagnostics.event(context,"error","provider","","","direct_model_auto_resume_failed","Unable to resume the persisted DeepSeek task.",error);}}
    static JSONArray openAiTools(JSONArray nativeTools) throws Exception {JSONArray out=new JSONArray();for(int i=0;i<nativeTools.length();i++){JSONObject n=nativeTools.getJSONObject(i);out.put(new JSONObject().put("type","function").put("function",new JSONObject().put("name",n.getString("name")).put("description",n.optString("description")).put("parameters",n.getJSONObject("inputSchema"))));}return out;}
    static JSONObject toolMessage(String providerId,String name,JSONObject value,String model,boolean includeImage) throws Exception {JSONObject clean=sanitize(new JSONObject(value.toString())),message=new JSONObject().put("role","tool").put("tool_call_id",providerId);String b64=value.optString("image_base64","");boolean vision=includeImage&&"screenshot".equals(name)&&ModelApiStore.MODEL_FLASH.equals(model)&&value.optInt("bytes",0)<=32*1024*1024&&!b64.isEmpty();if(vision){JSONArray content=new JSONArray().put(new JSONObject().put("type","text").put("text",clean.toString())).put(new JSONObject().put("type","image_url").put("image_url",new JSONObject().put("url","data:image/png;base64,"+b64).put("detail","original")));message.put("content",content);}else message.put("content",clean.toString());return message;}
    static JSONArray requestMessages(JSONArray stored,boolean toolsAvailable) throws Exception {return requestMessages(stored,toolsAvailable,false);}
    static JSONArray requestMessages(JSONArray stored,boolean toolsAvailable,boolean discoveryOnly) throws Exception {
        JSONArray out=new JSONArray().put(new JSONObject().put("role","system").put("content",discoveryOnly?DirectModelPrompt.discovery():DirectModelPrompt.text(toolsAvailable)));
        for(int i=0;i<stored.length();i++){
            JSONObject src=stored.getJSONObject(i);String role=src.optString("role");
            if("progress".equals(role)||"tool".equals(role))continue;
            JSONArray calls=src.optJSONArray("tool_calls");
            if(!"assistant".equals(role)||calls==null||calls.length()==0){out.put(wireMessage(src));continue;}
            LinkedHashSet<String> pending=new LinkedHashSet<>();
            for(int c=0;c<calls.length();c++){String id=calls.optJSONObject(c)==null?"":calls.optJSONObject(c).optString("id");if(!id.isEmpty())pending.add(id);}
            JSONArray replies=new JSONArray();int cursor=i+1;
            for(;cursor<stored.length();cursor++){
                JSONObject next=stored.optJSONObject(cursor);if(next==null)continue;
                String nextRole=next.optString("role");if("progress".equals(nextRole))continue;
                if(!"tool".equals(nextRole))break;
                String id=next.optString("tool_call_id");if(pending.remove(id))replies.put(wireMessage(next));
            }
            // A cancelled or interrupted turn can persist an assistant call before every
            // tool has answered. DeepSeek rejects that whole history block, so omit it.
            if(!pending.isEmpty())continue;
            out.put(wireMessage(src));for(int r=0;r<replies.length();r++)out.put(replies.getJSONObject(r));i=cursor-1;
        }
        return out;
    }
    static JSONArray discoveryTools(JSONArray fullTools)throws Exception{
        JSONArray out=new JSONArray();for(int i=0;i<fullTools.length();i++){JSONObject item=fullTools.getJSONObject(i);JSONObject function=item.optJSONObject("function");if(function!=null&&"capabilities".equals(function.optString("name"))){out.put(new JSONObject(item.toString()));break;}}
        if(out.length()!=1)throw new IllegalStateException("capabilities_tool_missing");return out;
    }
    private static JSONObject wireMessage(JSONObject src)throws Exception{JSONObject copy=new JSONObject();for(Iterator<String> keys=src.keys();keys.hasNext();){String key=keys.next();if("created_at".equals(key)||"internal".equals(key)||"provider".equals(key)||"hidden_reason".equals(key)||"guidance".equals(key)||"guidance_id".equals(key)||"protocol_recovered".equals(key))continue;copy.put(key,src.get(key));}return copy;}
    static JSONArray protocolToolCalls(String value)throws Exception{
        JSONArray calls=new JSONArray();if(value==null)return calls;String text=value.trim();
        if(text.startsWith("{")&&text.endsWith("}"))try{JSONObject root=new JSONObject(text);JSONObject one=root.optJSONObject("function");String name=root.optString("name",one==null?"":one.optString("name"));Object arguments=root.has("arguments")?root.opt("arguments"):one==null?null:one.opt("arguments");if(name.matches("[A-Za-z][A-Za-z0-9_]{1,80}"))calls.put(recoveredCall(name,arguments));return calls;}catch(Exception ignored){}
        String lower=text.toLowerCase(java.util.Locale.ROOT);int cursor=0;Pattern opening=Pattern.compile("(?i)invoke\\s+name\\s*=\\s*[\"']([A-Za-z][A-Za-z0-9_]{1,80})[\"']");
        while(cursor<text.length()&&calls.length()<16){Matcher invoke=opening.matcher(text);if(!invoke.find(cursor))break;int bodyStart=text.indexOf('>',invoke.end());if(bodyStart<0)break;int closeEnd=lower.indexOf("invoke>",bodyStart+1);if(closeEnd<0)break;int closeStart=lower.lastIndexOf("</",closeEnd);if(closeStart<bodyStart)break;String body=text.substring(bodyStart+1,closeStart).trim();JSONObject args=new JSONObject();Matcher parameter=Pattern.compile("(?i)parameter\\s+name\\s*=\\s*[\"']([^\"']{1,80})[\"']").matcher(body);boolean found=false;int parameterCursor=0;String bodyLower=body.toLowerCase(java.util.Locale.ROOT);while(parameter.find(parameterCursor)){int valueStart=body.indexOf('>',parameter.end());if(valueStart<0)break;int parameterEnd=bodyLower.indexOf("parameter>",valueStart+1);if(parameterEnd<0)break;int valueEnd=bodyLower.lastIndexOf("</",parameterEnd);if(valueEnd<valueStart)break;found=true;String raw=entities(body.substring(valueStart+1,valueEnd).replaceAll("(?is)<[^>]+>","").trim());args.put(parameter.group(1),protocolValue(raw));parameterCursor=parameterEnd+10;}if(!found&&body.startsWith("{")&&body.endsWith("}"))try{args=new JSONObject(entities(body));}catch(Exception ignored){}calls.put(recoveredCall(invoke.group(1),args));cursor=closeEnd+7;}
        return calls;
    }
    private static JSONObject recoveredCall(String name,Object arguments)throws Exception{JSONObject args=arguments instanceof JSONObject?(JSONObject)arguments:arguments instanceof String?new JSONObject((String)arguments):new JSONObject();return new JSONObject().put("id","recovered-"+UUID.randomUUID()).put("type","function").put("function",new JSONObject().put("name",name).put("arguments",args.toString()));}
    private static Object protocolValue(String raw){String value=raw==null?"":raw.trim();boolean json=value.startsWith("{")||value.startsWith("[")||value.startsWith("\"")||"true".equals(value)||"false".equals(value)||"null".equals(value)||value.matches("-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?");if(json)try{return new org.json.JSONTokener(value).nextValue();}catch(Exception ignored){}return value;}
    private static String entities(String value){return value.replace("&quot;","\"").replace("&#39;","'").replace("&lt;","<").replace("&gt;",">").replace("&amp;","&");}
    static boolean looksLikeProtocol(String value){if(value==null)return false;String s=value.trim().toLowerCase(java.util.Locale.ROOT);if(s.isEmpty())return false;return s.contains("<｜dsml｜")||s.contains("<|dsml|")||s.contains("<invoke")||s.contains("</invoke")||s.contains("<tool_call")||s.contains("\"tool_calls\"")||(s.startsWith("{")&&s.endsWith("}")&&s.contains("\"arguments\"")&&s.contains("\"name\""));}
    static JSONObject sanitize(JSONObject input) throws Exception {return (JSONObject)sanitizeValue(input,"",0);}
    private static Object sanitizeValue(Object value,String key,int depth) throws Exception {if(depth>10)return "[nested value omitted]";if(value instanceof JSONObject){JSONObject src=(JSONObject)value,dst=new JSONObject();for(Iterator<String> it=src.keys();it.hasNext();){String k=it.next();Object v=src.get(k);boolean binary=k.toLowerCase().contains("base64")&&v instanceof String&&!((String)v).isEmpty();boolean largeData="data".equalsIgnoreCase(k)&&v instanceof String&&((String)v).length()>4096;if(binary||largeData)dst.put(k,"[binary image omitted; use structured UI evidence]");else dst.put(k,sanitizeValue(v,k,depth+1));}return dst;}if(value instanceof JSONArray){JSONArray src=(JSONArray)value,dst=new JSONArray();for(int i=0;i<src.length();i++)dst.put(sanitizeValue(src.get(i),key,depth+1));return dst;}if(value instanceof String&&((String)value).length()>131072)return ((String)value).substring(0,131072)+"[truncated]";return value;}
}
