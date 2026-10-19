package ai.ophone.bridge;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Rational;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;
import org.json.JSONTokener;
import java.util.concurrent.atomic.AtomicReference;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ChatActivity extends Activity {
    private static final String UI_PREFS = "ui";
    private static final String KEY_MODE = "chat_mode";
    private static final String KEY_NOTIFICATION_PROMPTED = "notification_prompted";
    private static final String MODE_CHAT = "chat";
    private static final String MODE_WORK = "work";
    private static WeakReference<ChatActivity> currentActivity =
            new WeakReference<>(null);

    /** Same-conversation transport repair only. Never replays an Android operation. */
    public static boolean reconnectProvider(String conversation,String taskId) {
        ChatActivity host=currentActivity.get();
        if(host==null||host.webView==null||!host.bridgeAllowed||conversation.startsWith("c:WEB:"))return false;
        return host.webView.post(()->{
            if(!host.pageReady||!host.bridgeAllowed||host.isFinishing())return;
            String url=host.webView.getUrl();
            if(!host.isTrustedChatUrl(url)||!("/c/"+conversation.substring(2)).equals(Uri.parse(url).getPath()))return;
            String check="(function(){var r=window.__OPhoneAIRuntime,s=r&&r.state;return !!(s&&s.nativeTask&&s.nativeTask.id==="
                    +JSONObject.quote(taskId)+"&&s.activeTask&&!s.toolRunning&&!s.pendingResult&&!s.sending&&(!r.health().model_generating||r.health().model_transport_error));})()";
            host.webView.evaluateJavascript(check,value->{
                if(!"true".equals(value)||!host.pageReady||!host.bridgeAllowed)return;
                if(!TaskController.get(host).providerReloaded(taskId))return;
                host.status.setText(host.getString(R.string.runtime_recovering));
                host.pageReady=false;host.webView.reload();
            });
        });
    }

    private WebView webView;
    private LinearLayout topBar;
    private TextView status;
    private Button chatButton;
    private Button workButton;
    private String preferredMode = MODE_CHAT;
    private boolean pageReady;
    private long lastRuntimeInstall;
    private long lastSupervisionReply;
    private long supervisionRequestedAt;
    private int nativeRepairAttempts;

    private volatile boolean bridgeAllowed;
    private volatile String bridgePageNonce = "";

    private final Runnable modeSync = new Runnable() {
        @Override
        public void run() {
            syncModeFromPage();
            syncRuntimeStatus();
            if (webView != null) {
                webView.postDelayed(this, 1500L);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        currentActivity = new WeakReference<>(this);
        pruneDuplicateChatTasks();
        startBridgeService();

        preferredMode = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .getString(KEY_MODE, MODE_CHAT);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.pageColor(this));

        LinearLayout bar = new LinearLayout(this);
        topBar = bar;
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 7),
                UiKit.dp(this, 8), UiKit.dp(this, 7));
        bar.setBackgroundColor(UiKit.cardColor(this));
        bar.setElevation(UiKit.dp(this, 2));

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.setGravity(Gravity.CENTER_VERTICAL);

        TextView brandTitle = new TextView(this);
        brandTitle.setText(getString(R.string.app_name));
        brandTitle.setTextSize(14f);
        brandTitle.setTypeface(Typeface.DEFAULT_BOLD);
        brandTitle.setTextColor(UiKit.primaryTextColor(this));
        brand.addView(brandTitle);

        status = new TextView(this);
        status.setText(getString(R.string.tools_preparing));
        status.setTextSize(10.5f);
        status.setTextColor(UiKit.secondaryTextColor(this));
        brand.addView(status);

        bar.addView(brand, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        chatButton = compactButton(getString(R.string.chat));
        chatButton.setOnClickListener(v -> switchMode(MODE_CHAT, true));
        bar.addView(chatButton, compactButtonParams());

        workButton = compactButton(getString(R.string.work));
        workButton.setOnClickListener(v -> switchMode(MODE_WORK, true));
        bar.addView(workButton, compactButtonParams());

        Button browserButton = new Button(this);
        browserButton.setText(R.string.browser);styleModeButton(browserButton, false);browserButton.setTextSize(12);browserButton.setMinWidth(0);browserButton.setMinimumWidth(0);browserButton.setPadding(UiKit.dp(this,8),0,UiKit.dp(this,8),0);
        browserButton.setOnClickListener(v -> {try{WorkbenchClient.get(this).showBrowser();}catch(Exception e){android.widget.Toast.makeText(this,e.getMessage(),android.widget.Toast.LENGTH_LONG).show();}});
        bar.addView(browserButton, compactButtonParams());
        Button exitButton = compactButton(getString(R.string.exit_platform));
        exitButton.setOnClickListener(v -> exitPlatform());
        bar.addView(exitButton, compactButtonParams());
        Button settingsButton = compactButton(getString(R.string.settings));
        settingsButton.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        bar.addView(settingsButton, compactButtonParams());

        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        webView = new WebView(this);
        webView.addJavascriptInterface(new LocalJsBridge(), "OPhoneAINative");

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUserAgentString(buildDesktopUserAgent());
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                pageReady = false;
                nativeRepairAttempts = 0;
                bridgeAllowed = false;
                bridgePageNonce = "";
                status.setText(getString(R.string.tools_preparing));
            }

            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view, WebResourceRequest request) {
                return handleAppUrl(request.getUrl().toString());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleAppUrl(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                pageReady = true;

                bridgeAllowed = isTrustedChatUrl(url);
                if (bridgeAllowed) {
                    bridgePageNonce =
                            UUID.randomUUID().toString().replace("-", "");
                    installLocalBridge();
                    view.postDelayed(ChatActivity.this::installConversationRuntime, 800L);
                    view.postDelayed(ChatActivity.this::installConversationRuntime, 2200L);
                } else {
                    bridgePageNonce = "";
                }

                updateModeButtons();
                view.removeCallbacks(modeSync);
                view.postDelayed(() -> applyPreferredMode(false), 700L);
                view.postDelayed(modeSync, 1200L);
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(android.webkit.ConsoleMessage message) {
                if(message.messageLevel()==android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                    DetailedDiagnostics.event(getApplicationContext(),"error","webview_js","","","javascript_error",
                            message.message()+" at "+message.sourceId()+":"+message.lineNumber(),null);
                }
                return super.onConsoleMessage(message);
            }
        });

        root.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        updateModeButtons();
        maybeRequestNotificationPermission();

        String initialUrl = trustedInitialChatUrl(
                getIntent().getStringExtra("url"));
        webView.loadUrl(initialUrl);
    }

    public static boolean prepareForExternalAutomation(Context context) {
        if (context == null
                || Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || !RiskPolicy.pipEnabled(context)) {
            return false;
        }

        ChatActivity activity = currentActivity.get();
        if (activity == null
                || activity.isFinishing()
                || activity.isDestroyed()) {
            return false;
        }
        if (activity.isInPictureInPictureMode()) return true;

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean entered = new AtomicBoolean(false);

        activity.runOnUiThread(() -> {
            try {
                if (activity.webView != null) {
                    activity.webView.clearFocus();
                }
                PictureInPictureParams params =
                        new PictureInPictureParams.Builder()
                                .setAspectRatio(new Rational(16, 9))
                                .build();
                entered.set(activity.enterPictureInPictureMode(params));
            } catch (Exception ignored) {
                entered.set(false);
            } finally {
                latch.countDown();
            }
        });

        try {
            latch.await(1200L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return entered.get();
    }

    public static boolean isAutomationInPictureInPicture() {
        ChatActivity activity = currentActivity.get();
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && activity != null && !activity.isDestroyed()
                && activity.isInPictureInPictureMode();
    }

    private void pruneDuplicateChatTasks() {
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (manager == null) return;
        for (ActivityManager.AppTask task : manager.getAppTasks()) {
            try {
                ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                if (info.id == getTaskId()) continue;
                if ((info.baseActivity != null && getClass().getName().equals(info.baseActivity.getClassName()))
                        || (info.topActivity != null && getClass().getName().equals(info.topActivity.getClassName()))) {
                    task.finishAndRemoveTask();
                }
            } catch (Exception ignored) {}
        }
    }

    private void installLocalBridge() {
        if (webView == null || !bridgeAllowed || bridgePageNonce.isEmpty()) {
            return;
        }

        final String nonceLiteral = JSONObject.quote(bridgePageNonce);
        final String script =
                "(function(){"
                        + "try{"
                        + "var n=" + nonceLiteral + ";"
                        + "var nativeBridge=window.OPhoneAINative;"
                        + "if(!nativeBridge)return 'native_missing';"
                        + "var pending=new Map();"
                        + "var seq=0;"
                        + "window.__OPhoneAIDeliver=function(id,raw){"
                        + "var p=pending.get(id);"
                        + "if(!p)return;"
                        + "pending.delete(id);"
                        + "try{var v=JSON.parse(raw);"
                        + "if(v&&v.error)p.reject(new Error(v.error.message||'native_rpc_error'));else p.resolve(v);}"
                        + "catch(e){p.reject(e);}"
                        + "};"
                        + "function mcp(req){"
                        + "return new Promise(function(resolve,reject){"
                        + "var cb='sj-'+Date.now().toString(36)+'-'+(++seq).toString(36);"
                        + "var timeout=setTimeout(function(){pending.delete(cb);reject(new Error('native_callback_timeout'));},125000);"
                        + "pending.set(cb,{resolve:function(v){clearTimeout(timeout);resolve(v);},reject:function(e){clearTimeout(timeout);reject(e);}});"
                        + "try{nativeBridge.request(n,JSON.stringify(req),cb);}"
                        + "catch(e){pending.delete(cb);reject(e);}"
                        + "});"
                        + "}"
                        + "function rpcId(prefix){"
                        + "return prefix+'-'+Date.now().toString(36)+'-'+(++seq).toString(36);"
                        + "}"
                        + "var api={"
                        + "version:'1.0.0',"
                        + "mcp:mcp,"
                        + "listTools:function(){"
                        + "return mcp({jsonrpc:'2.0',id:rpcId('tools'),method:'tools/list',params:{}})"
                        + ".then(function(r){return r&&r.result&&r.result.tools?r.result.tools:[];});"
                        + "},"
                        + "task:function(action,args){return mcp({jsonrpc:'2.0',id:rpcId('task'),method:'agent/task',params:{action:action,arguments:args||{}}}).then(function(r){return r.result;});},"
                        + "receipt:function(scope){return mcp({jsonrpc:'2.0',id:rpcId('receipt'),method:'runtime/receipt',ophoneai_context:scope}).then(function(r){return r.result;});},"
                        + "trace:function(code,call,tool){try{nativeBridge.trace(n,String(code),String(call||''),String(tool||''));}catch(e){}},"
                        + "reportError:function(code,message,stack,task,call){try{nativeBridge.reportError(n,String(code),String(message),String(stack||''),String(task||''),String(call||''));}catch(e){}},"
                        + "call:function(name,args,scope){"
                        + "return mcp({jsonrpc:'2.0',id:rpcId('call'),method:'tools/call',"
                        + "params:{name:String(name),arguments:args||{}},ophoneai_context:scope||null})"
                        + ".then(function(r){return r?r.result:null;});"
                        + "}"
                        + "};"
                        + "Object.defineProperty(window,'OPhoneAI',"
                        + "{configurable:true,value:Object.freeze(api)});"
                        + "return 'ok';"
                        + "}catch(e){return 'error:'+String(e);}"
                        + "})();";

        webView.evaluateJavascript(script, value -> {
            if (value != null && value.contains("ok")) {
                installConversationRuntime();
            }
        });
    }

    private void installConversationRuntime() {
        lastRuntimeInstall = System.currentTimeMillis();
        if (webView == null || !bridgeAllowed) return;
        try {
            InputStream in = getAssets().open("web/ophone-ai-runtime.js");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
            in.close();
            String runtime = new String(out.toByteArray(), StandardCharsets.UTF_8);
            webView.evaluateJavascript(runtime, null);
        } catch (Exception e) {
            if (status != null) {
                status.setText(getString(R.string.conversation_runtime_failed));
            }
        }
    }

    private final class LocalJsBridge {
        @JavascriptInterface
        public void reportError(String nonce,String code,String message,String stack,String taskId,String callId) {
            if(!isBridgeRequestAllowed(nonce)||code==null||!code.matches("[A-Za-z0-9._-]{1,80}"))return;
            if(message==null||message.length()>16000||stack==null||stack.length()>24000)return;
            try {
                AgentStore.get(getApplicationContext()).addLog("error","runtime_js",taskId,callId,code,DetailedDiagnostics.clean(message),DetailedDiagnostics.clean(stack));
                if(taskId!=null&&!taskId.isEmpty())TaskController.get(getApplicationContext()).reportRuntimeFault(taskId,code,message);
            } catch(Exception ignored) {}
        }
        @JavascriptInterface
        public void trace(String nonce, String code, String callId, String tool) {
            if (isBridgeRequestAllowed(nonce)) RuntimeDiagnostics.record(getApplicationContext(), code, callId, tool);
        }

        @JavascriptInterface
        public void request(
                String nonce,
                String requestJson,
                String callbackId) {

            if (!isBridgeRequestAllowed(nonce)
                    || !isValidCallbackId(callbackId)) {
                deliverBridgeResult(
                        callbackId,
                        jsonRpcError("bridge_request_rejected"));
                return;
            }

            if (requestJson == null || requestJson.length() > 128 * 1024) {
                deliverBridgeResult(
                        callbackId,
                        jsonRpcError("request_too_large"));
                return;
            }

            try {
                JSONObject request = new JSONObject(requestJson);
                String method = request.optString("method", "");
                if (!isAllowedMcpMethod(method)) {
                    deliverBridgeResult(
                            callbackId,
                            jsonRpcError("method_not_allowed"));
                    return;
                }
            } catch (Exception e) {
                deliverBridgeResult(
                        callbackId,
                        jsonRpcError("invalid_json_rpc"));
                return;
            }

            final String requestNonce = nonce;
            new Thread(() -> {
                String response;
                JSONObject scope = null;
                boolean ownsExecution = false;
                try {
                    JSONObject request = new JSONObject(requestJson);
                    scope = ExecutionJournal.context(request);
                    if ("agent/task".equals(request.optString("method"))) {
                        JSONObject params=request.getJSONObject("params");
                        JSONObject result=TaskController.get(getApplicationContext()).bridge(params.optString("action"),params.getJSONObject("arguments"));
                        response=new JSONObject().put("jsonrpc","2.0").put("id",request.opt("id")).put("result",result).toString();
                    } else if ("runtime/receipt".equals(request.optString("method"))) {
                        if (scope == null) throw new IllegalArgumentException("execution_context_required");
                        response = new JSONObject().put("jsonrpc", "2.0").put("id", request.opt("id"))
                                .put("result", ExecutionJournal.receipt(getApplicationContext(), scope)).toString();
                    } else {
                        JSONObject previous = null;
                        if (scope != null && "tools/call".equals(request.optString("method"))) {
                            previous = ExecutionJournal.begin(getApplicationContext(), scope, request.getJSONObject("params"));
                            ownsExecution = previous == null;
                        }
                        if (previous != null) {
                            response = "done".equals(previous.optString("state"))
                                    ? previous.getJSONObject("response").toString()
                                    : jsonRpcError("action_outcome_not_confirmed_do_not_replay");
                        } else {
                            try { response = LocalMcpClient.post(getApplicationContext(), requestJson); }
                            catch (Exception unavailable) {
                                DetailedDiagnostics.event(getApplicationContext(),"error","bridge",scope==null?"":scope.optString("task_id"),scope==null?"":scope.optString("call_id"),"mcp_transport_error","Local tool transport failed; action outcome is uncertain.",unavailable);
                                // A transport timeout cannot prove the Android action did not run.
                                response = jsonRpcError("action_outcome_unknown_inspect_ui_before_retry");
                            }
                            if (ownsExecution) ExecutionJournal.finish(getApplicationContext(), scope, response);
                        }
                    }
                } catch (Exception e) {
                    response = jsonRpcError("native_request_failed: "+DetailedDiagnostics.clean(e.getMessage()));
                    DetailedDiagnostics.event(getApplicationContext(),"error","bridge",scope==null?"":scope.optString("task_id"),scope==null?"":scope.optString("call_id"),"native_request_failed",e.getClass().getName()+": "+e.getMessage(),e);
                    RuntimeDiagnostics.record(getApplicationContext(), "receipt_error", "", "");
                }
                // A navigation/reload must never deliver an old callback to a different page.
                if (isBridgeRequestAllowed(requestNonce)) deliverBridgeResult(callbackId, response, requestNonce);
            }, "OPhoneAI-JS-MCP").start();
        }
    }

    private boolean isAllowedMcpMethod(String method) {
        return "agent/task".equals(method)
                || "runtime/receipt".equals(method)
                || "initialize".equals(method)
                || "ping".equals(method)
                || "tools/list".equals(method)
                || "tools/call".equals(method);
    }

    private boolean isBridgeRequestAllowed(String nonce) {
        if (!bridgeAllowed || nonce == null) return false;

        String expected = bridgePageNonce;
        if (expected == null || expected.length() != nonce.length()) {
            return false;
        }

        int diff = 0;
        for (int i = 0; i < expected.length(); i++) {
            diff |= expected.charAt(i) ^ nonce.charAt(i);
        }
        return diff == 0;
    }

    private boolean isValidCallbackId(String callbackId) {
        if (callbackId == null
                || callbackId.length() < 1
                || callbackId.length() > 80) {
            return false;
        }
        return callbackId.matches("[A-Za-z0-9._-]+");
    }

    private void deliverBridgeResult(String callbackId, String responseJson) {
        deliverBridgeResult(callbackId, responseJson, bridgePageNonce);
    }

    private void deliverBridgeResult(String callbackId, String responseJson, String requestNonce) {
        if (webView == null || !bridgeAllowed
                || !isValidCallbackId(callbackId)) {
            return;
        }

        final String script =
                "window.__OPhoneAIDeliver&&window.__OPhoneAIDeliver("
                        + JSONObject.quote(callbackId) + ","
                        + JSONObject.quote(responseJson == null ? "{}" : responseJson)
                        + ");";

        webView.post(() -> {
            if (webView != null && isBridgeRequestAllowed(requestNonce)) {
                webView.evaluateJavascript(script, null);
            }
        });
    }

    private String jsonRpcError(String message) {
        try {
            JSONObject root = new JSONObject();
            root.put("jsonrpc", "2.0");
            root.put("id", JSONObject.NULL);
            JSONObject error = new JSONObject();
            error.put("code", -32000);
            error.put("message", message);
            root.put("error", error);
            return root.toString();
        } catch (Exception e) {
            return "{\"jsonrpc\":\"2.0\",\"id\":null,"
                    + "\"error\":{\"code\":-32000,"
                    + "\"message\":\"bridge_error\"}}";
        }
    }

    private boolean isTrustedChatUrl(String url) {
        try {
            Uri uri = Uri.parse(url == null ? "" : url);
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme())
                    && host != null
                    && "chatgpt.com".equalsIgnoreCase(host);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean handleAppUrl(String url) {
        if (url != null && url.startsWith("ophoneai://")) {
            Toast.makeText(
                    this,
                    getString(R.string.local_only_notice),
                    Toast.LENGTH_SHORT).show();
            return true;
        }
        return false;
    }

    private String trustedInitialChatUrl(String candidate) {
        return isTrustedChatUrl(candidate)
                ? candidate
                : "https://chatgpt.com/";
    }

    private String buildDesktopUserAgent() {
        String ua = WebSettings.getDefaultUserAgent(this);
        if (ua == null || ua.trim().isEmpty()) {
            return "Mozilla/5.0 AppleWebKit/537.36 Chrome/120 Safari/537.36"
                    + " OPhoneAI/" + BuildConfig.VERSION_NAME;
        }

        ua = ua.replace("; wv", "")
                .replace(" Version/4.0", "")
                .replace(" Mobile", "");
        return ua + " OPhoneAI/" + BuildConfig.VERSION_NAME;
    }

    private void maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }

        SharedPreferences prefs =
                getSharedPreferences(UI_PREFS, MODE_PRIVATE);
        if (prefs.getBoolean(KEY_NOTIFICATION_PROMPTED, false)) return;

        prefs.edit().putBoolean(KEY_NOTIFICATION_PROMPTED, true).apply();
        requestPermissions(
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                1401);
    }

    private Button compactButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(11.5f);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinHeight(UiKit.dp(this, 38));
        button.setPadding(UiKit.dp(this, 9), 0,
                UiKit.dp(this, 9), 0);
        styleModeButton(button, false);
        return button;
    }

    private void styleModeButton(Button button, boolean selected) {
        if (button == null) return;
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(UiKit.dp(this, 13));
        background.setColor(selected
                ? UiKit.accentColor(this)
                : Color.TRANSPARENT);
        button.setBackground(background);
        button.setTextColor(selected
                ? (UiKit.isDark(this)
                ? Color.parseColor("#111318") : Color.WHITE)
                : UiKit.primaryTextColor(this));
        button.setElevation(0f);
    }

    private LinearLayout.LayoutParams compactButtonParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(UiKit.dp(this, 2), 0, 0, 0);
        return lp;
    }

    @Override
    protected void onResume() {
        super.onResume();
        currentActivity = new WeakReference<>(this);
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);
        if (topBar != null) {
            topBar.setVisibility(isInPictureInPictureMode ? View.GONE : View.VISIBLE);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        currentActivity = new WeakReference<>(this);
        pruneDuplicateChatTasks();
        startBridgeService();

        String url = intent.getStringExtra("url");
        if (webView != null && url != null && !url.isEmpty()) {
            if (!handleAppUrl(url) && isTrustedChatUrl(url)) {
                pageReady = false;
                webView.loadUrl(url);
            }
        }
    }

    private void switchMode(String mode, boolean userInitiated) {
        if (!MODE_CHAT.equals(mode) && !MODE_WORK.equals(mode)) return;

        preferredMode = mode;
        getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .edit()
                .putString(KEY_MODE, mode)
                .apply();

        updateModeButtons();

        if (!pageReady || webView == null) return;
        applyPreferredMode(userInitiated);
    }

    private void applyPreferredMode(boolean userInitiated) {
        if (webView == null || !pageReady) return;

        final boolean work = MODE_WORK.equals(preferredMode);
        final String script =
                "(function(){"
                        + "var target=" + (work ? "'work'" : "'chatgpt'") + ";"
                        + "var e=document.querySelector('[role=radio][data-tpp-toggle-value='+target+']');"
                        + "if(!e){"
                        + "var labels=" + (work
                        ? "['Work','工作']"
                        : "['Chat','聊天']") + ";"
                        + "var nodes=Array.from(document.querySelectorAll('button,[role=button]'));"
                        + "e=nodes.find(function(n){"
                        + "var t=(n.innerText||n.textContent||'').trim();"
                        + "return labels.indexOf(t)>=0;"
                        + "});"
                        + "}"
                        + "if(!e)return 'missing';"
                        + "if(e.getAttribute('aria-checked')==='true'"
                        + "||e.getAttribute('aria-pressed')==='true')return 'already';"
                        + "var r=e.getBoundingClientRect();"
                        + "var x=r.left+r.width/2,y=r.top+r.height/2;"
                        + "var common={bubbles:true,cancelable:true,composed:true,"
                        + "clientX:x,clientY:y,screenX:x,screenY:y,"
                        + "button:0,buttons:1,view:window};"
                        + "e.dispatchEvent(new PointerEvent('pointerdown',"
                        + "Object.assign({pointerId:1,pointerType:'mouse',isPrimary:true},common)));"
                        + "e.dispatchEvent(new MouseEvent('mousedown',common));"
                        + "e.dispatchEvent(new PointerEvent('pointerup',"
                        + "Object.assign({},common,{pointerId:1,pointerType:'mouse',"
                        + "isPrimary:true,buttons:0})));"
                        + "e.dispatchEvent(new MouseEvent('mouseup',"
                        + "Object.assign({},common,{buttons:0})));"
                        + "e.dispatchEvent(new MouseEvent('click',"
                        + "Object.assign({},common,{buttons:0})));"
                        + "return 'sent';"
                        + "})();";

        webView.evaluateJavascript(script, value -> {
            String result = value == null ? "" : value.replace("\"", "");
            if ("missing".equals(result) && userInitiated) {
                pageReady = false;
                webView.loadUrl("https://chatgpt.com/");
            } else if ("sent".equals(result)) {
                webView.postDelayed(this::syncModeFromPage, 450L);
            }
        });
    }

    private void syncModeFromPage() {
        if (webView == null || !pageReady) return;

        final String script =
                "(function(){"
                        + "var c=document.querySelector('[role=radio][data-tpp-toggle-value=chatgpt]');"
                        + "var w=document.querySelector('[role=radio][data-tpp-toggle-value=work]');"
                        + "if(c&&c.getAttribute('aria-checked')==='true')return 'chat';"
                        + "if(w&&w.getAttribute('aria-checked')==='true')return 'work';"
                        + "var bs=Array.from(document.querySelectorAll('button,[role=button]'));"
                        + "c=bs.find(function(e){var t=(e.innerText||e.textContent||'').trim();return t==='Chat'||t==='聊天';});"
                        + "w=bs.find(function(e){var t=(e.innerText||e.textContent||'').trim();return t==='Work'||t==='工作';});"
                        + "if(c&&c.getAttribute('aria-pressed')==='true')return 'chat';"
                        + "if(w&&w.getAttribute('aria-pressed')==='true')return 'work';"
                        + "return '';"
                        + "})();";

        webView.evaluateJavascript(script, value -> {
            String mode = value == null ? "" : value.replace("\"", "");
            if (!MODE_CHAT.equals(mode) && !MODE_WORK.equals(mode)) return;
            if (mode.equals(preferredMode)) return;

            preferredMode = mode;
            SharedPreferences.Editor editor =
                    getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit();
            editor.putString(KEY_MODE, mode);
            editor.apply();

            updateModeButtons();
        });
    }

    public static boolean hasRuntimeHost() {
        ChatActivity host = currentActivity.get();
        return host != null && !host.isFinishing() && host.bridgeAllowed && host.pageReady;
    }

    public static JSONObject recoverRuntime(String mode) throws Exception {
        if (!"auto".equals(mode) && !"retry_delivery".equals(mode) && !"restart_observers".equals(mode)) {
            throw new IllegalArgumentException("invalid_recovery_mode");
        }
        ChatActivity host = currentActivity.get();
        if (host == null || !hasRuntimeHost()) return new JSONObject().put("ok", false).put("error", "chat_runtime_unavailable");
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<JSONObject> result = new AtomicReference<>();
        host.runOnUiThread(() -> {
            if (host.webView == null || !host.bridgeAllowed) { done.countDown(); return; }
            host.webView.evaluateJavascript("(function(){var r=window.__OPhoneAIRuntime;return r&&r.recover?r.recover("
                    + JSONObject.quote(mode) + "):({ok:false,error:'runtime_missing'});})()", value -> {
                try { Object parsed = new JSONTokener(value).nextValue();
                    if (parsed instanceof JSONObject) result.set((JSONObject) parsed);
                } catch (Exception ignored) {}
                done.countDown();
            });
        });
        done.await(3, TimeUnit.SECONDS);
        RuntimeDiagnostics.record(host.getApplicationContext(), "model_recovery_requested", "", "runtime_recover");
        return result.get() != null ? result.get() : new JSONObject().put("ok", false).put("error", "runtime_not_responding");
    }

    private void syncRuntimeStatus() {
        if (webView == null || !pageReady || !bridgeAllowed) return;
        android.os.PowerManager power = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        if (power != null && !power.isInteractive()) return;
        final long now = System.currentTimeMillis();
        // Android's timer is independent of the page MutationObserver and JS interval.
        if (supervisionRequestedAt > lastSupervisionReply && now - supervisionRequestedAt < 6000) return;
        if (supervisionRequestedAt > lastSupervisionReply && status != null) {
            status.setText(getString(R.string.runtime_stalled));
        }
        supervisionRequestedAt = now;
        final String requestedNonce = bridgePageNonce;
        webView.evaluateJavascript(
                "(function(){var r=window.__OPhoneAIRuntime;"
                        + "if(!window.OPhoneAI)return {missing:'bridge'};"
                        + "if(!r||!r.supervise)return {missing:'runtime'};"
                        + "return r.supervise();})()",
                value -> {
                    if (status == null || !pageReady || !isBridgeRequestAllowed(requestedNonce)) return;
                    lastSupervisionReply = System.currentTimeMillis();
                    try {
                        Object parsed = new JSONTokener(value == null ? "null" : value).nextValue();
                        if (!(parsed instanceof JSONObject)) return;
                        JSONObject health = (JSONObject) parsed;
                        if (health.has("missing")) {
                            if (nativeRepairAttempts >= 3) {
                                status.setText(getString(R.string.runtime_stalled));
                                RuntimeDiagnostics.observe(health.put("exhausted", true));
                                return;
                            }
                            status.setText(getString(R.string.tools_preparing));
                            if (System.currentTimeMillis() - lastRuntimeInstall > 5000) {
                                nativeRepairAttempts++;
                                RuntimeDiagnostics.record(getApplicationContext(), "runtime_reinstall", "", "");
                                if ("bridge".equals(health.optString("missing"))) installLocalBridge();
                                else installConversationRuntime();
                            }
                            return;
                        }
                        RuntimeDiagnostics.observe(health);
                        String stage = health.optString("stage");
                        int label = "failed".equals(health.optString("phase")) || health.optBoolean("exhausted")
                                ? R.string.runtime_stalled
                                : "failed".equals(stage) ? R.string.agent_task_attention
                                : "recovering".equals(stage) ? R.string.runtime_recovering
                                : health.optBoolean("active_task") ? R.string.agent_task_running
                                : health.optBoolean("ready") ? R.string.local_ready : R.string.tools_preparing;
                        status.setText(getString(label));
                    } catch (Exception ignored) {}
                });
    }

    private void updateModeButtons() {
        if (chatButton == null || workButton == null) return;

        boolean work = MODE_WORK.equals(preferredMode);
        chatButton.setEnabled(true);
        workButton.setEnabled(true);
        String chat = getString(R.string.chat);
        String workLabel = getString(R.string.work);
        chatButton.setText(chat);
        workButton.setText(workLabel);
        styleModeButton(chatButton, !work);
        styleModeButton(workButton, work);
    }

    private void startBridgeService() {
        startService(new Intent(this, BridgeService.class));
    }

    private void exitPlatform() {
        bridgeAllowed = false;
        if (webView != null) {
            webView.evaluateJavascript("(function(){try{var b=document.querySelector('[data-testid=stop-button],button[aria-label*=Stop],button[aria-label*=停止]');if(b)b.click();if(window.__OPhoneAIRuntime&&window.__OPhoneAIRuntime.stop)window.__OPhoneAIRuntime.stop();}catch(e){}return true;})()", null);
            webView.stopLoading();
            webView.loadUrl("about:blank");
        }
        PlatformSession.cancelProvider(this, PlatformSession.CHATGPT);
        PlatformPreference.clear(this);
        startActivity(new Intent(this, PlatformActivity.class)
                .putExtra(PlatformPreference.EXTRA_SHOW_CHOOSER, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            exitPlatform();
        }
    }

    @Override
    protected void onDestroy() {
        ChatActivity current = currentActivity.get();
        if (current == this) currentActivity.clear();
        bridgeAllowed = false;
        bridgePageNonce = "";

        if (webView != null) {
            webView.removeCallbacks(modeSync);
            webView.stopLoading();
            webView.destroy();
        }

        super.onDestroy();
    }
}
