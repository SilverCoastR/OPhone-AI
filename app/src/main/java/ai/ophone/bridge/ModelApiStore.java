package ai.ophone.bridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Device-local settings of one API platform. API keys are encrypted by Android Keystore, kept per platform and never exported. */
public final class ModelApiStore {
    public static final String MODEL_FLASH="deepseek-flash";
    public static final String MODEL_PRO="deepseek-v4-pro";
    private final Context context;private final SharedPreferences prefs;
    private final String alias,prefsName;final ModelProvider provider;

    public static final class Config {
        public final String provider,model,reasoningEffort,baseUrl;
        public final boolean thinking;
        public final int maxTokens;
        Config(String provider,String model,boolean thinking,String effort,int maxTokens,String baseUrl){
            this.provider=provider;this.model=model;this.thinking=thinking;this.reasoningEffort=effort;this.maxTokens=maxTokens;this.baseUrl=baseUrl;
        }
        /** Compatibility constructor for DeepSeek-only callers. */
        Config(String model,boolean thinking,String effort,int maxTokens){this("deepseek",model,thinking,effort,maxTokens,ModelProvider.DEEPSEEK.defaultBaseUrl);}
        public JSONObject publicJson() throws Exception {
            return new JSONObject().put("provider",provider).put("model",model)
                    .put("thinking",thinking).put("reasoning_effort",reasoningEffort)
                    .put("max_tokens",maxTokens).put("base_url",baseUrl);
        }
        String chatUrl(){return "deepseek".equals(provider)?DeepSeekClient.CHAT_URL:"claude".equals(provider)?baseUrl+"/messages":baseUrl+"/chat/completions";}
        String modelsUrl(){return "deepseek".equals(provider)?DeepSeekClient.MODELS_URL:baseUrl+"/models";}
    }

    public ModelApiStore(Context context){this(context,ModelProvider.DEEPSEEK);}
    ModelApiStore(Context context,ModelProvider provider){this(context,provider.prefs,provider.keyAlias,provider);}
    ModelApiStore(Context context,String prefsName,String keyAlias){this(context,prefsName,keyAlias,ModelProvider.DEEPSEEK);}
    private ModelApiStore(Context context,String prefsName,String keyAlias,ModelProvider provider){
        this.context=context.getApplicationContext();this.prefsName=prefsName;this.provider=provider;prefs=this.context.getSharedPreferences(prefsName,Context.MODE_PRIVATE);alias=keyAlias;
    }
    public Config config(){
        String fallback=provider.fixedModels||provider.models.length>0?provider.models[0]:"";
        String model=prefs.getString("model",fallback);if(!acceptsModel(model))model=fallback;
        String effort=prefs.getString("reasoning_effort","high");if(!validEffort(effort))effort=provider.efforts.length>0?provider.efforts[provider.efforts.length>1?1:0]:"high";
        int max=Math.max(256,Math.min(65536,prefs.getInt("max_tokens",8192)));
        String base=provider.editableBaseUrl?prefs.getString("base_url",provider.defaultBaseUrl):provider.defaultBaseUrl;
        return new Config(provider.id,model,provider.supportsThinking()&&prefs.getBoolean("thinking",provider==ModelProvider.DEEPSEEK),effort,max,base);
    }
    public void saveConfig(String model,boolean thinking,String effort,int maxTokens){
        if(!acceptsModel(model)||!validEffort(effort)||maxTokens<256||maxTokens>65536)throw new IllegalArgumentException("invalid_model_api_config");
        prefs.edit().putString("model",model).putBoolean("thinking",thinking)
                .putString("reasoning_effort",effort).putInt("max_tokens",maxTokens).apply();
    }
    /** OpenAI-compatible services only: an HTTPS base URL such as https://api.openai.com/v1. */
    public void saveBaseUrl(String raw){
        if(!provider.editableBaseUrl)return;String url=raw==null?"":raw.trim().replaceAll("/+$","");
        if(url.isEmpty())url=provider.defaultBaseUrl;
        android.net.Uri uri=android.net.Uri.parse(url);if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getHost().isEmpty())throw new IllegalArgumentException("base_url_must_be_https");
        prefs.edit().putString("base_url",url).apply();
    }
    boolean acceptsModel(String value){if(provider.fixedModels)return validModel(value);return value!=null&&value.matches("[A-Za-z0-9._:/@-]{1,120}");}
    public static boolean validModel(String value){return MODEL_FLASH.equals(value)||MODEL_PRO.equals(value);}
    boolean validEffort(String value){if(!provider.supportsThinking())return value!=null;for(String e:provider.efforts)if(e.equals(value))return true;return false;}
    public boolean hasKey(){return prefs.contains("key_cipher")&&prefs.contains("key_iv");}
    /** Ready to talk to the model: a key and, where the model is free text, a model ID. */
    public boolean ready(){return hasKey()&&!config().model.isEmpty();}
    public void saveKey(String raw) throws Exception {
        String key=raw==null?"":raw.trim();if(key.length()<8||key.length()>4096)throw new IllegalArgumentException("invalid_api_key");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
        byte[] encrypted=cipher.doFinal(key.getBytes(StandardCharsets.UTF_8));
        prefs.edit().putString("key_iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP))
                .putString("key_cipher",Base64.encodeToString(encrypted,Base64.NO_WRAP)).commit();
    }
    String apiKey() throws Exception {
        if(!hasKey())throw new IllegalStateException(provider.id+"_api_key_missing");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(prefs.getString("key_iv",""),Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(prefs.getString("key_cipher",""),Base64.NO_WRAP)),StandardCharsets.UTF_8);
    }
    public void clearKey(){prefs.edit().remove("key_iv").remove("key_cipher").commit();}
    void clearTestData() throws Exception {clearKey();prefs.edit().clear().commit();context.deleteSharedPreferences(prefsName);KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);if(ks.containsAlias(alias))ks.deleteEntry(alias);}
    private SecretKey key() throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        if(!ks.containsAlias(alias)){
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey)ks.getKey(alias,null);
    }
}
