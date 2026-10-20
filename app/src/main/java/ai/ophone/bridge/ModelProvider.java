package ai.ophone.bridge;

import android.content.Context;

/**
 * The direct-API model platforms. All of them share one local conversation store and the same native tool kernel,
 * so a conversation (and its open task) can continue on another API platform after the user agrees to the handoff.
 */
final class ModelProvider {
    static final ModelProvider DEEPSEEK=new ModelProvider("deepseek",PlatformSession.DEEPSEEK,"DeepSeek","DeepSeek","https://api.deepseek.com",
            new String[]{ModelApiStore.MODEL_FLASH,ModelApiStore.MODEL_PRO},true,false,new String[]{"low","high","max"},"model_api_v1","ophoneai_model_api_key_v1");
    static final ModelProvider OPENAI=new ModelProvider("openai",PlatformSession.OPENAI,"OpenAI","OpenAI","https://api.openai.com/v1",
            new String[]{},false,true,new String[]{"low","medium","high"},"model_api_openai_v1","ophoneai_model_api_key_openai_v1");
    static final ModelProvider CLAUDE=new ModelProvider("claude",PlatformSession.CLAUDE,"Claude","Anthropic","https://api.anthropic.com/v1",
            new String[]{"claude-sonnet-5-5","claude-opus-5-5","claude-haiku-5-5"},false,false,new String[]{},"model_api_claude_v1","ophoneai_model_api_key_claude_v1");
    static final ModelProvider[] ALL={DEEPSEEK,OPENAI,CLAUDE};

    final String id,platform,label,company,defaultBaseUrl,prefs,keyAlias;
    /** DeepSeek: a fixed model list. Others: free model ID, with suggestions or the service's /models list. */
    final String[] models;final boolean fixedModels,editableBaseUrl;final String[] efforts;

    private ModelProvider(String id,String platform,String label,String company,String baseUrl,String[] models,boolean fixedModels,boolean editableBaseUrl,String[] efforts,String prefs,String keyAlias){
        this.id=id;this.platform=platform;this.label=label;this.company=company;this.defaultBaseUrl=baseUrl;this.models=models;this.fixedModels=fixedModels;this.editableBaseUrl=editableBaseUrl;this.efforts=efforts;this.prefs=prefs;this.keyAlias=keyAlias;
    }

    static ModelProvider of(String id){for(ModelProvider p:ALL)if(p.id.equals(id))return p;return null;}
    static ModelProvider forPlatform(String platform){for(ModelProvider p:ALL)if(p.platform.equals(platform))return p;return null;}
    /** The API platform the user selected; DeepSeek when the selection is not an API platform. */
    static ModelProvider current(Context context){ModelProvider p=forPlatform(PlatformPreference.get(context));return p==null?DEEPSEEK:p;}
    static boolean isApiPlatform(String platform){return forPlatform(platform)!=null;}

    boolean supportsThinking(){return efforts.length>0;}
    ModelApiStore store(Context context){return new ModelApiStore(context,this);}
    ModelClient client(){return this==CLAUDE?new ClaudeClient():new DeepSeekClient();}
}
