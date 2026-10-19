package ai.ophone.bridge;

import org.json.JSONArray;
import org.json.JSONObject;

public class LocalMcpProtocol {
    private final LocalToolKernel kernel;

    public LocalMcpProtocol(LocalToolKernel kernel) {
        this.kernel = kernel;
    }

    public JSONObject handle(JSONObject request) throws Exception {
        Object id = request.has("id") ? request.get("id") : JSONObject.NULL;
        String method = request.optString("method", "");
        JSONObject response = baseResponse(id);

        try {
            if ("initialize".equals(method)) {
                JSONObject result = new JSONObject();
                result.put("protocolVersion", "2025-06-18");

                JSONObject capabilities = new JSONObject();
                capabilities.put("tools", new JSONObject());
                result.put("capabilities", capabilities);

                JSONObject serverInfo = new JSONObject();
                serverInfo.put("name", "OPhone AI Local MCP");
                serverInfo.put("version", "1.0.0");
                result.put("serverInfo", serverInfo);

                response.put("result", result);
                return response;
            }

            if ("ping".equals(method)) {
                response.put("result", new JSONObject());
                return response;
            }

            if ("tools/list".equals(method)) {
                JSONObject result = new JSONObject();
                result.put("tools", kernel.mcpTools());
                response.put("result", result);
                return response;
            }

            if ("tools/call".equals(method)) {
                JSONObject params = request.optJSONObject("params");
                if (params == null) throw new IllegalArgumentException("missing_params");

                String name = params.optString("name", "");
                JSONObject args = params.optJSONObject("arguments");
                if (args == null) args = new JSONObject();

                JSONObject toolResult = new JSONObject();
                JSONArray content = new JSONArray();

                try {
                    JSONObject value = kernel.call(name, args, request.optJSONObject("ophoneai_context"));

                    if (("screenshot".equals(name)||"browser_screenshot".equals(name))
                            && value.has("image_base64")
                            && value.optString("image_base64").length() > 0) {
                        JSONObject image = new JSONObject();
                        image.put("type", "image");
                        image.put("data", value.optString("image_base64"));
                        image.put("mimeType", value.optString("mime_type", "image/png"));
                        content.put(image);

                        JSONObject meta = new JSONObject(value.toString());
                        meta.remove("image_base64");
                        JSONObject text = new JSONObject();
                        text.put("type", "text");
                        text.put("text", meta.toString());
                        content.put(text);
                    } else {
                        JSONObject text = new JSONObject();
                        text.put("type", "text");
                        text.put("text", value.toString());
                        content.put(text);
                    }

                    toolResult.put("content", content);
                    toolResult.put("isError", value.has("ok") && !value.optBoolean("ok"));
                } catch (Exception e) {
                    JSONObject text = new JSONObject();
                    text.put("type", "text");
                    JSONObject err = new JSONObject();
                    err.put("ok", false);
                    err.put("error", e.getClass().getSimpleName() + ": "
                            + String.valueOf(e.getMessage()));
                    text.put("text", err.toString());
                    content.put(text);
                    toolResult.put("content", content);
                    toolResult.put("isError", true);
                }

                response.put("result", toolResult);
                return response;
            }

            return errorResponse(id, -32601, "Method not found: " + method);
        } catch (Exception e) {
            return errorResponse(id, -32602,
                    e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
        }
    }

    private JSONObject baseResponse(Object id) throws Exception {
        JSONObject response = new JSONObject();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        return response;
    }

    private JSONObject errorResponse(Object id, int code, String message) throws Exception {
        JSONObject response = baseResponse(id);
        JSONObject error = new JSONObject();
        error.put("code", code);
        error.put("message", message);
        response.put("error", error);
        return response;
    }
}
