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

/** Owns the direct-API planning/tool loop (DeepSeek, OpenAI, Claude). Native TaskController remains the completion authority. */
public final class DirectModelAgent {
    public interface Ui {
        void onStatus(String value);void onResponseStart();void onText(String value);
        void onTool(String name,String arguments);void onQuestion(String question,String options);void onToolResult(String name,boolean ok,String summary);void onProgress(String value,String kind);void onFinished();void onError(String safeMessage);
    }
    private static final ConcurrentHashMap<String,DirectModelAgent> ACTIVE=new ConcurrentHashMap<>();
    private static final Ui HEADLESS=new Ui(){public void onStatus(String value){}public void onResponseStart(){}public void onText(String value){}public void onTool(String name,String arguments){}public void onQuestion(String question,String options){}public void onToolResult(String name,boolean ok,String summary){}public void onProgress(String value,String kind){}public void onFinished(){}public void onError(String value){}};
    private final Context context;private final ModelApiStore settings;private final ApiConversationStore history;
    private final LocalToolKernel kernel;private final ModelProvider provider;private final ModelClient client;private final ConcurrentLinkedQueue<JSONObject> guidance=new ConcurrentLinkedQueue<>();
    private volatile boolean running,cancelled;private volatile Thread heartbeat;private volatile Ui ui=HEADLESS;
    private volatile String ownedConversation="",taskId="",terminationReason="";
    private volatile String modelOverride="",effortOverride="";
    /** A scheduled run's own model and reasoning level, for this agent only; the platform settings stay as they are. */
    void useOverrides(String model,String effort){ModelApiStore store=settings;modelOverride=model!=null&&store.acceptsModel(model.trim())?model.trim():"";effortOverride=effort!=null&&provider.supportsThinking()&&store.validEffort(effort.trim())?effort.trim():"";}
    private ModelApiStore.Config withOverrides(ModelApiStore.Config c){if(modelOverride.isEmpty()&&effortOverride.isEmpty())return c;return new ModelApiStore.Config(c.provider,modelOverride.isEmpty()?c.model:modelOverride,c.thinking||!effortOverride.isEmpty(),effortOverride.isEmpty()?c.reasoningEffort:effortOverride,c.maxTokens,c.baseUrl);}

    DirectModelAgent(Context c){this(c,ModelProvider.current(c));}
    DirectModelAgent(Context c,ModelProvider p){context=c.getApplicationContext();provider=p;settings=p.store(c);client=p.client();history=new ApiConversationStore(c);kernel=new LocalToolKernel(c);}
    ModelProvider provider(){return provider;}
    public boolean isRunning(){String id=ownedConversation.isEmpty()?history.conversationId():ownedConversation;return running||ACTIVE.containsKey(id);}
    static boolean isConversationRunning(String conversation){return conversation!=null&&ACTIVE.containsKey(conversation);}
    public void attachUi(Ui next){Ui safe=next==null?HEADLESS:next;DirectModelAgent current=ACTIVE.get(history.conversationId());if(current!=null&&current!=this){current.ui=safe;return;}ui=safe;}
    public void cancel(){terminate();}
    public void terminate(){DirectModelAgent current=ACTIVE.get(history.conversationId());if(current!=null&&current!=this){current.terminateLocal(current.context.getString(R.string.agent_terminate_reason),true);return;}terminateLocal(context.getString(R.string.agent_terminate_reason),true);}
    private void terminateLocal(String reason,boolean cascade){cancelled=true;terminationReason=reason;client.cancel();stopHeartbeat();String conversation=ownedConversation;if(!conversation.isEmpty()){progress(context.getString(R.string.agent_terminating));if(cascade)new Thread(()->PlatformSession.cancelConversation(context,conversation,reason),"OPhoneAI-task-cancel").start();}}
    static void stopProvider(String provider){if(!ModelProvider.isApiPlatform(provider))return;for(DirectModelAgent agent:ACTIVE.values())if(agent.provider.platform.equals(provider))agent.terminateLocal(agent.context.getString(R.string.agent_provider_exit_reason),false);}
    /** Leaving for the platform chooser: stop this loop but keep open tasks parked, so another API platform can continue them with the user's consent. */
    public void exitPlatform(){for(DirectModelAgent agent:ACTIVE.values())agent.terminateLocal(context.getString(R.string.agent_provider_exit_reason),false);terminateLocal(context.getString(R.string.agent_provider_exit_reason),false);PlatformSession.parkApiTasks(context,provider.label);PlatformPreference.clear(context);}
    public JSONArray history(){return history.messages();}
    public void clear(){if(isRunning())throw new IllegalStateException("agent_is_running");history.create(provider.id);}

    /** An app event the AI must know at its next step (for example the user taking over the phone). */
    static void systemGuidance(String conversation,String text){DirectModelAgent agent=conversation==null?null:ACTIVE.get(conversation);if(agent!=null)try{agent.guideLocal(text);}catch(Exception ignored){}}
    public boolean guide(String input){String text=input==null?"":input.trim();if(text.isEmpty())return false;DirectModelAgent current=ACTIVE.get(history.conversationId());if(current!=null&&current!=this)return current.guideLocal(text);return guideLocal(text);}
    private boolean guideLocal(String text){if(!running||ownedConversation.isEmpty())return false;try{JSONObject message=new JSONObject().put("role","user").put("content",text).put("provider",provider.id).put("created_at",System.currentTimeMillis()).put("guidance",true).put("guidance_id",UUID.randomUUID().toString());history.append(ownedConversation,message);guidance.add(message);progress(context.getString(R.string.agent_guidance_received));return true;}catch(Exception error){throw new IllegalStateException(error);}}

    public void send(final String input,final Ui next){final String text=input==null?"":input.trim();if(text.isEmpty())throw new IllegalArgumentException("empty_message");if(isRunning())throw new IllegalStateException("agent_is_running");start(text,next,false);}
    private void start(String text,Ui next,boolean internal){String conversation=history.conversationId();if(ACTIVE.putIfAbsent(conversation,this)!=null)throw new IllegalStateException("agent_is_running");ownedConversation=conversation;ui=next==null?HEADLESS:next;running=true;cancelled=false;terminationReason="";new Thread(()->run(text,internal,conversation),"OPhoneAI-model-agent").start();}
    private void run(String input,boolean internalResume,String conversation){
        try {
            if(!settings.ready())throw new IllegalStateException(context.getString(R.string.model_api_not_ready,provider.label));
            history.setProvider(provider.id);history.append(conversation,new JSONObject().put("role","user").put("content",input).put("provider",provider.id).put("created_at",System.currentTimeMillis()).put("internal",internalResume));
            progress(context.getString(internalResume?R.string.agent_restoring:R.string.agent_request_received));
            String human="api-"+UUID.randomUUID().toString();JSONObject observed=TaskController.get(context).bridge("observe_request",new JSONObject().put("conversation",conversation).put("human_key",human).put("goal",input).put("guidance_if_active",true));
            JSONObject task=observed.optBoolean("managed")?observed.getJSONObject("task"):null;if(task!=null){taskId=task.getString("id");startHeartbeat(conversation,taskId);progress(observed.optBoolean("continued")?context.getString(R.string.agent_guidance_merged):context.getString(R.string.agent_task_created,task.optString("goal")));}
            JSONArray fullTools=openAiTools(kernel.mcpTools()),tools=task==null?discoveryTools(fullTools):fullTools,live=history.contextMessages(conversation);ModelApiStore.Config config=withOverrides(settings.config());int calls=0,guards=0,protocolRetries=0,turn=0;
            while(!cancelled){drainGuidance(live);boolean managed=task!=null;String planning=context.getString(managed?R.string.agent_planning_task:R.string.agent_deciding_capability);ui.onStatus(planning);progress("step",context.getString(R.string.agent_processing_step,++turn));ui.onResponseStart();final StringBuilder turnText=new StringBuilder();final boolean[] thinking={false};
                DeepSeekClient.Result result=client.stream(settings.apiKey(),config,requestMessages(live,managed,!managed,provider.id),tools,new DeepSeekClient.StreamListener(){
                    public void onText(String value){turnText.append(value);}public void onThinking(){if(!thinking[0]){thinking[0]=true;ui.onStatus(context.getString(R.string.agent_thinking));progress(context.getString(R.string.agent_analyzing));}}
                });
                if(cancelled)break;result.message.put("provider",provider.id).put("created_at",System.currentTimeMillis());history.append(conversation,result.message);live.put(new JSONObject(result.message.toString()));
                JSONArray toolCalls=result.message.optJSONArray("tool_calls");
                if((toolCalls==null||toolCalls.length()==0)&&looksLikeProtocol(result.message.optString("content"))){JSONArray recovered=protocolToolCalls(result.message.optString("content"));if(recovered.length()>0){result.message.put("content","").put("tool_calls",recovered).put("protocol_recovered",true);history.replaceLastAssistant(conversation,result.message);live.remove(live.length()-1);live.put(new JSONObject(result.message.toString()));toolCalls=recovered;progress(context.getString(R.string.agent_protocol_recovered));}}
                if(toolCalls!=null&&toolCalls.length()>0){
                    for(int i=0;i<toolCalls.length();i++){
                        if(cancelled)break;
                        if(++calls>RiskPolicy.budget(context)[1]&&task!=null){
                            JSONObject asked=TaskController.get(context).awaitUser(new JSONObject().put("conversation",conversation).put("task_id",task.getString("id")),new JSONObject().put("question",context.getString(R.string.budget_question,calls-1)).put("options",new JSONArray().put(context.getString(R.string.budget_continue)).put(context.getString(R.string.budget_stop))));
                            parkForUser(conversation,toolCalls,i,asked.optString("question"),asked.optJSONArray("options"),config.model);return;
                        }
                        JSONObject call=toolCalls.getJSONObject(i),fn=call.getJSONObject("function");String name=fn.optString("name"),providerId=call.optString("id","tool-"+UUID.randomUUID());
                        JSONObject args;try{args=new JSONObject(fn.optString("arguments","{}"));}catch(Exception malformed){args=new JSONObject();}ui.onTool(name,args.toString());progress("tool",context.getString(R.string.agent_executing_tool,name));
                        if(task==null&&!"capabilities".equals(name))throw new IllegalStateException("discovery_phase_only_allows_capabilities");
                        JSONObject scope=task==null?null:new JSONObject().put("conversation",conversation).put("task_id",task.getString("id")).put("call_id","api-"+UUID.randomUUID());JSONObject value;
                        try {value=kernel.call(name,args,scope);}catch(Throwable error){value=new JSONObject().put("ok",false).put("error",DetailedDiagnostics.clean(error.getClass().getSimpleName()+": "+error.getMessage()));}
                        ui.onToolResult(name,value.optBoolean("ok"),resultSummary(value));progress("tool",context.getString(value.optBoolean("ok")?R.string.agent_tool_completed:R.string.agent_tool_error,name));JSONObject persisted=toolMessage(providerId,name,value,config.model,false).put("provider",provider.id).put("created_at",System.currentTimeMillis()),wire=toolMessage(providerId,name,value,config.model,true);history.append(conversation,persisted);live.put(wire);
                        if(task==null&&"capabilities".equals(name)&&value.optBoolean("ok")){
                            task=TaskController.get(context).begin(conversation,human,input);taskId=task.getString("id");startHeartbeat(conversation,taskId);tools=fullTools;
                            progress(context.getString(R.string.agent_capability_attached));
                        }
                        boolean budgetPause=value.optString("error").contains(TaskController.BUDGET_PAUSE);
                        if(("ask_user".equals(name)&&value.optBoolean("waiting_for_user"))||budgetPause){
                            try{TaskController.get(context).bridge("feedback",new JSONObject().put("conversation",conversation).put("task_id",task.getString("id")).put("call_id",scope.getString("call_id")));}catch(Exception ignored){}
                            String question=budgetPause?context.getString(R.string.budget_question,RiskPolicy.budget(context)[0]):value.optString("question");
                            JSONArray options=budgetPause?new JSONArray().put(context.getString(R.string.budget_continue)).put(context.getString(R.string.budget_stop)):value.optJSONArray("options");
                            parkForUser(conversation,toolCalls,i+1,question,options,config.model);return;
                        }
                        if(task!=null)try{TaskController.get(context).bridge("feedback",new JSONObject().put("conversation",conversation).put("task_id",task.getString("id")).put("call_id",scope.getString("call_id")));}catch(Exception ignored){}
                    }
                    continue;
                }
                String finalText=turnText.length()>0?turnText.toString():result.message.optString("content");
                if(looksLikeProtocol(finalText)){result.message.put("internal",true).put("hidden_reason","protocol_markup_suppressed");history.replaceLastAssistant(conversation,result.message);if(++protocolRetries>2)throw new IllegalStateException(context.getString(R.string.agent_protocol_retry_error));JSONObject guard=new JSONObject().put("role","user").put("content","[OPhone AI protocol guard] Never print DSML, XML tool tags, tool-call JSON, or internal protocol. Use native function calls when tools are available; otherwise answer in ordinary language.").put("internal",true).put("provider",provider.id);history.append(conversation,guard);live.put(new JSONObject(guard.toString()));continue;}
                if(task!=null){JSONObject status=TaskController.get(context).status(new JSONObject().put("conversation",conversation).put("task_id",task.getString("id")),new JSONObject());task=status.getJSONObject("task");if("waiting_event".equals(task.optString("status"))){String notice=finalText.isEmpty()?context.getString(R.string.agent_waiting_notice):finalText;progress(context.getString(R.string.agent_waiting_progress));ui.onStatus(context.getString(R.string.agent_waiting_native_event));ui.onText(notice);ui.onFinished();return;}if(!TaskController.terminal(task.optString("status"))){if(++guards>3)throw new IllegalStateException(context.getString(R.string.agent_nonterminal_error));progress(context.getString(R.string.agent_task_incomplete));JSONObject guard=new JSONObject().put("role","user").put("content","[OPhone AI native guard] The original phone task is still nonterminal. Read task_status, continue autonomously, verify fresh evidence, and call task_finish. Do not give a final answer yet.").put("internal",true);history.append(conversation,guard);live.put(new JSONObject(guard.toString()));continue;}progress(context.getString(R.string.agent_task_status,task.optString("status")));}
                if(!finalText.isEmpty())ui.onText(finalText);ui.onFinished();return;
            }
            ui.onStatus(terminationReason.isEmpty()?context.getString(R.string.task_terminated):terminationReason);ui.onFinished();
        } catch(Throwable error){if(cancelled){ui.onStatus(terminationReason.isEmpty()?context.getString(R.string.task_terminated):terminationReason);ui.onFinished();}else ui.onError(DetailedDiagnostics.clean(error.getMessage()==null?error.getClass().getSimpleName():error.getMessage()));}
        finally {stopHeartbeat();running=false;taskId="";ACTIVE.remove(conversation,this);}
    }
    /** Ends the run while the task waits for the user: unanswered calls get a skip result, the question is persisted and shown. */
    private void parkForUser(String conversation,JSONArray toolCalls,int from,String question,JSONArray options,String model)throws Exception{
        for(int rest=from;rest<toolCalls.length();rest++){JSONObject skipped=toolCalls.getJSONObject(rest);JSONObject note=new JSONObject().put("ok",false).put("error","skipped_waiting_for_user_answer");history.append(conversation,toolMessage(skipped.optString("id"),skipped.getJSONObject("function").optString("name"),note,model,false).put("provider",provider.id).put("created_at",System.currentTimeMillis()));}
        JSONArray safe=options==null?new JSONArray():options;
        history.append(conversation,new JSONObject().put("role","assistant").put("content",question).put("ask_user",true).put("options",safe).put("provider",provider.id).put("created_at",System.currentTimeMillis()));
        ui.onStatus(context.getString(R.string.agent_waiting_user));ui.onQuestion(question,safe.toString());ui.onFinished();
    }
    private void drainGuidance(JSONArray live)throws Exception{JSONObject message;while((message=guidance.poll())!=null){String id=message.optString("guidance_id");boolean present=false;for(int i=0;i<live.length();i++)if(id.equals(live.optJSONObject(i)==null?"":live.optJSONObject(i).optString("guidance_id"))){present=true;break;}if(!present)live.put(new JSONObject(message.toString()));}}
    private void progress(String text){progress("",text);}
    /** kind "tool"/"step" marks lines the chat UI already shows as structured tool steps. */
    private void progress(String kind,String text){if(text==null||text.trim().isEmpty()||ownedConversation.isEmpty())return;String clean=text.trim();try{JSONObject line=new JSONObject().put("role","progress").put("content",clean).put("provider",provider.id).put("created_at",System.currentTimeMillis());if(!kind.isEmpty())line.put("kind",kind);history.append(ownedConversation,line);}catch(Exception ignored){}try{ui.onProgress(clean,kind);}catch(Exception ignored){}}
    /** Short user-facing result text: the error for failures, otherwise a trimmed sanitized JSON preview. */
    static String resultSummary(JSONObject value){try{if(!value.optBoolean("ok")){String error=value.optString("error","");return error.length()>600?error.substring(0,600)+"…":error;}JSONObject clean=sanitize(new JSONObject(value.toString()));clean.remove("ok");String text=clean.toString();return text.length()>600?text.substring(0,600)+"…":text;}catch(Exception e){return "";}}
    private void startHeartbeat(String conversation,String id){stopHeartbeat();heartbeat=new Thread(()->{while(!Thread.currentThread().isInterrupted()&&!cancelled){try{TaskController.get(context).bridge("heartbeat",new JSONObject().put("conversation",conversation).put("task_id",id));Thread.sleep(5000);}catch(InterruptedException done){Thread.currentThread().interrupt();break;}catch(Exception ignored){try{Thread.sleep(2000);}catch(InterruptedException done){Thread.currentThread().interrupt();break;}}}},"OPhoneAI-DeepSeek-heartbeat");heartbeat.start();}
    private void stopHeartbeat(){Thread thread=heartbeat;heartbeat=null;if(thread!=null)thread.interrupt();}
    public static void supervise(Context context){try{if(!ModelProvider.isApiPlatform(PlatformPreference.get(context)))return;ModelApiStore settings=ModelProvider.current(context).store(context);if(!settings.ready())return;ApiConversationStore history=new ApiConversationStore(context);String conversation=history.conversationId();if(ACTIVE.containsKey(conversation))return;DirectModelAgent agent=new DirectModelAgent(context);JSONObject task=TaskController.get(context).claimDirectResume(conversation);if(task==null)return;agent.start(agent.context.getString(R.string.agent_continue),HEADLESS,true);}catch(Exception error){DetailedDiagnostics.event(context,"error","provider","","","direct_model_auto_resume_failed","Unable to resume the persisted DeepSeek task.",error);}}
    static JSONArray openAiTools(JSONArray nativeTools) throws Exception {JSONArray out=new JSONArray();for(int i=0;i<nativeTools.length();i++){JSONObject n=nativeTools.getJSONObject(i);out.put(new JSONObject().put("type","function").put("function",new JSONObject().put("name",n.getString("name")).put("description",n.optString("description")).put("parameters",n.getJSONObject("inputSchema"))));}return out;}
    static JSONObject toolMessage(String providerId,String name,JSONObject value,String model,boolean includeImage) throws Exception {JSONObject clean=sanitize(new JSONObject(value.toString())),message=new JSONObject().put("role","tool").put("tool_call_id",providerId);String b64=value.optString("image_base64","");// Images in tool results: DeepSeek Flash and Claude accept them; Chat Completions tool messages are text-only elsewhere.
        boolean vision=includeImage&&"screenshot".equals(name)&&(ModelApiStore.MODEL_FLASH.equals(model)||model.startsWith("claude"))&&value.optInt("bytes",0)<=32*1024*1024&&!b64.isEmpty();if(vision){JSONArray content=new JSONArray().put(new JSONObject().put("type","text").put("text",clean.toString())).put(new JSONObject().put("type","image_url").put("image_url",new JSONObject().put("url","data:image/png;base64,"+b64).put("detail","original")));message.put("content",content);}else message.put("content",clean.toString());return message;}
    static JSONArray requestMessages(JSONArray stored,boolean toolsAvailable) throws Exception {return requestMessages(stored,toolsAvailable,false);}
    static JSONArray requestMessages(JSONArray stored,boolean toolsAvailable,boolean discoveryOnly) throws Exception {return requestMessages(stored,toolsAvailable,discoveryOnly,"");}
    /** provider: the platform about to receive the history; work done by other platforms reaches it as plain text. */
    static JSONArray requestMessages(JSONArray original,boolean toolsAvailable,boolean discoveryOnly,String provider) throws Exception {
        JSONArray stored=forProvider(ApiConversationStore.window(original),provider);
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
    private static JSONObject wireMessage(JSONObject src)throws Exception{JSONObject copy=new JSONObject();for(Iterator<String> keys=src.keys();keys.hasNext();){String key=keys.next();if(LOCAL_KEYS.contains(key))continue;copy.put(key,src.get(key));}
        if(copy.has("tool_call_id"))copy.put("tool_call_id",safeToolId(copy.optString("tool_call_id")));
        JSONArray calls=copy.optJSONArray("tool_calls");for(int i=0;calls!=null&&i<calls.length();i++){JSONObject call=calls.optJSONObject(i);if(call!=null)call.put("id",safeToolId(call.optString("id")));}
        return copy;}
    private static final java.util.Set<String> LOCAL_KEYS=new java.util.HashSet<>(java.util.Arrays.asList("id","created_at","internal","provider","hidden_reason","guidance","guidance_id","protocol_recovered","ask_user","options","kind","task_id","task_status"));
    /** Tool call IDs every provider accepts: [A-Za-z0-9_-], at most 40 characters, stable for the same input. */
    static String safeToolId(String id){String clean=id==null?"":id.replaceAll("[^A-Za-z0-9_-]","_");if(!clean.isEmpty()&&clean.length()<=40)return clean;
        try{byte[] h=java.security.MessageDigest.getInstance("SHA-256").digest(String.valueOf(id).getBytes(java.nio.charset.StandardCharsets.UTF_8));StringBuilder b=new StringBuilder("call_");for(int i=0;i<16;i++)b.append(String.format("%02x",h[i]&255));return b.toString();}catch(Exception e){return "call_"+Integer.toHexString(String.valueOf(id).hashCode());}}

    /**
     * Platform handoff. Messages produced by another API platform keep their visible text, but its reasoning traces are
     * never forwarded and its tool exchanges become a plain-text summary, because tool call formats and reasoning
     * replay rules differ between providers.
     */
    static JSONArray forProvider(JSONArray stored,String provider) throws Exception {
        if(provider==null||provider.isEmpty())return stored;JSONArray out=new JSONArray();
        for(int i=0;i<stored.length();i++){JSONObject m=stored.getJSONObject(i);String from=m.optString("provider");ModelProvider origin=ModelProvider.of(from);
            if(origin==null||from.equals(provider)){out.put(m);continue;}
            String role=m.optString("role");JSONArray calls=m.optJSONArray("tool_calls");
            if("tool".equals(role))continue;
            if("assistant".equals(role)&&calls!=null&&calls.length()>0){StringBuilder text=new StringBuilder("[Earlier work by "+origin.label+"]");String said=m.optString("content").trim();if(!said.isEmpty())text.append(' ').append(clip(said,600));
                java.util.Map<String,String> names=new java.util.HashMap<>();
                for(int c=0;c<calls.length();c++){JSONObject call=calls.optJSONObject(c),fn=call==null?null:call.optJSONObject("function");if(fn==null)continue;names.put(call.optString("id"),fn.optString("name"));text.append("\n- called ").append(fn.optString("name")).append(' ').append(clip(fn.optString("arguments"),300));}
                int cursor=i+1;for(;cursor<stored.length();cursor++){JSONObject next=stored.getJSONObject(cursor);if(!"tool".equals(next.optString("role")))break;String name=names.get(next.optString("tool_call_id"));Object content=next.opt("content");text.append("\n- result").append(name==null?"":" of "+name).append(": ").append(clip(content instanceof JSONArray?"(structured result)":String.valueOf(content),400));}
                i=cursor-1;out.put(new JSONObject().put("role","assistant").put("content",text.toString()).put("provider",provider));continue;}
            JSONObject copy=new JSONObject(m.toString());copy.remove("reasoning_content");out.put(copy);}
        return out;}
    private static String clip(String s,int max){String t=s==null?"":s.replaceAll("\\s+"," ").trim();return t.length()>max?t.substring(0,max)+"…":t;}

    /** The API platform that last answered in this conversation, when it differs from the one about to continue it. */
    static ModelProvider previousProvider(JSONArray messages,ModelProvider next){for(int i=messages.length()-1;i>=0;i--){JSONObject m=messages.optJSONObject(i);if(m==null||!"assistant".equals(m.optString("role")))continue;ModelProvider p=ModelProvider.of(m.optString("provider"));if(p!=null)return p==next?null:p;}return null;}
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
