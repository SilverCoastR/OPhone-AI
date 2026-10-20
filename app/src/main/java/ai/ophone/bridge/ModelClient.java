package ai.ophone.bridge;

import org.json.JSONArray;

/** One streaming model transport. Messages and tools use the OpenAI Chat Completions shape stored locally. */
interface ModelClient {
    DeepSeekClient.Result stream(String apiKey,ModelApiStore.Config config,JSONArray messages,JSONArray tools,DeepSeekClient.StreamListener listener) throws Exception;
    String testConnection(String apiKey,ModelApiStore.Config config) throws Exception;
    /** Model IDs the key can use, for the settings picker. */
    java.util.List<String> listModels(String apiKey,ModelApiStore.Config config) throws Exception;
    void cancel();
}
