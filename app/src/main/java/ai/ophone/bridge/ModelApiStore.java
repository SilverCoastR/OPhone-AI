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

/** Device-local DeepSeek settings. API keys are encrypted by Android Keystore and never exported. */
public final class ModelApiStore {
    public static final String MODEL_FLASH="deepseek-flash";
    public static final String MODEL_PRO="deepseek-v4-pro";
    private static final String DEFAULT_PREFS="model_api_v1";
    private static final String DEFAULT_ALIAS="ophoneai_model_api_key_v1";
    private final Context context;private final SharedPreferences prefs;
    private final String alias,prefsName;

    public static final class Config {
        public final String model;
        public final boolean thinking;
        public final String reasoningEffort;
        public final int maxTokens;
        Config(String model,boolean thinking,String effort,int maxTokens){
            this.model=model;this.thinking=thinking;this.reasoningEffort=effort;this.maxTokens=maxTokens;
        }
        public JSONObject publicJson() throws Exception {
            return new JSONObject().put("provider","deepseek").put("model",model)
                    .put("thinking",thinking).put("reasoning_effort",reasoningEffort)
                    .put("max_tokens",maxTokens);
        }
    }

    public ModelApiStore(Context context){this(context,DEFAULT_PREFS,DEFAULT_ALIAS);}
    ModelApiStore(Context context,String prefsName,String keyAlias){
        this.context=context.getApplicationContext();this.prefsName=prefsName;prefs=this.context.getSharedPreferences(prefsName,Context.MODE_PRIVATE);alias=keyAlias;
    }
    public Config config(){
        String model=prefs.getString("model",MODEL_FLASH);if(!validModel(model))model=MODEL_FLASH;
        String effort=prefs.getString("reasoning_effort","high");if(!validEffort(effort))effort="high";
        int max=Math.max(256,Math.min(65536,prefs.getInt("max_tokens",8192)));
        return new Config(model,prefs.getBoolean("thinking",true),effort,max);
    }
    public void saveConfig(String model,boolean thinking,String effort,int maxTokens){
        if(!validModel(model)||!validEffort(effort)||maxTokens<256||maxTokens>65536)throw new IllegalArgumentException("invalid_model_api_config");
        prefs.edit().putString("model",model).putBoolean("thinking",thinking)
                .putString("reasoning_effort",effort).putInt("max_tokens",maxTokens).apply();
    }
    public static boolean validModel(String value){return MODEL_FLASH.equals(value)||MODEL_PRO.equals(value);}
    public static boolean validEffort(String value){return "low".equals(value)||"high".equals(value)||"max".equals(value);}
    public boolean hasKey(){return prefs.contains("key_cipher")&&prefs.contains("key_iv");}
    public void saveKey(String raw) throws Exception {
        String key=raw==null?"":raw.trim();if(key.length()<8||key.length()>4096)throw new IllegalArgumentException("invalid_api_key");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
        byte[] encrypted=cipher.doFinal(key.getBytes(StandardCharsets.UTF_8));
        prefs.edit().putString("key_iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP))
                .putString("key_cipher",Base64.encodeToString(encrypted,Base64.NO_WRAP)).commit();
    }
    String apiKey() throws Exception {
        if(!hasKey())throw new IllegalStateException("deepseek_api_key_missing");
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
