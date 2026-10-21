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
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ChatGPT web platform. chatgpt.com (the engine) runs with the injected runtime underneath OPhone AI's own chat page,
 * which shows only what matters: the conversation read from the page, the AI's steps, and a composer whose messages
 * the runtime sends when the page is ready. The page itself stays one tap away ("ChatGPT page" in the drawer), with a
 * slim bar for its tool status; when the local view cannot help (sign-in, verification), the banner leads there.
 */
public class ChatActivity extends Activity {
    @Override protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(base); UiKit.applyTheme(this); }
    private static final String UI_PREFS = "ui";
    private static final String KEY_NOTIFICATION_PROMPTED = "notification_prompted";
    private static final String KEY_VIEW = "chatgpt_view";
    private static final String HOME_URL = "https://chatgpt.com/";
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
                host.setStatus(R.string.runtime_recovering,"");
                host.pageReady=false;host.webView.reload();
            });
        });
    }

    private WebView webView;
    private LinearLayout topBar;
    private TextView status;
    private View menuDot;
    private boolean pageReady;
    private long lastRuntimeInstall;
    private long lastSupervisionReply;
    private long supervisionRequestedAt;
    private int nativeRepairAttempts;
    private int statusRes = R.string.tools_preparing;
    private String statusDetail = "";
    private ChatUpdate updates;
    // Local chat page on top of the engine page.
    private WebView chatView;
    private TextView doneButton;
    private boolean webShown;
    private volatile boolean chatReady;
    private volatile String snapshotJson = "{\"items\":[]}";
    private volatile String conversationsJson = "[]";
    private volatile boolean running;
    private volatile JSONObject engineState = new JSONObject();
    private volatile boolean pageAttention;
    private long conversationsAt = -1;
    private long noComposerSince;
    private boolean snapshotBusy;
    private final List<String> pendingSubmits = new ArrayList<>();
    private static final long SNAPSHOT_INTERVAL_MS = 450L;
    private static final long PAGE_ATTENTION_MS = 15000L;
    private final Runnable snapshotSync = new Runnable() {
        @Override public void run() { pollSnapshot(); if (webView != null) webView.postDelayed(this, SNAPSHOT_INTERVAL_MS); }
    };
    private boolean resumed;
    private boolean failedWhileAway;
    private String pageConversation = "";

    // Main-frame load failure of chatgpt.com (for example net::ERR_TIMED_OUT) and its bounded automatic retries.
    private String loadError = "";
    private int loadRetries;
    private boolean retryPending;
    private static final long[] RETRY_DELAYS_MS = {5000L, 15000L, 30000L};
    private final Runnable retryLoad = () -> { retryPending = false; if (!loadError.isEmpty()) reloadPage(false); };

    private volatile boolean bridgeAllowed;
    private volatile String bridgePageNonce = "";

    private final Runnable modeSync = new Runnable() {
        @Override
        public void run() {
            syncRuntimeStatus();
            enforceViewLayers();
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
        updates = new ChatUpdate(this, () -> { renderUpdateDot(); emitState(); });
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.canvasColor(this));
        UiKit.edgeColors(this, UiKit.canvasColor(this));

        // Slim bar: menu (with update dot), tool status (tap to fix), new chat.
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(UiKit.dp(this, 8), UiKit.dp(this, 4), UiKit.dp(this, 8), UiKit.dp(this, 4));
        topBar.setBackgroundColor(UiKit.canvasColor(this));

        FrameLayout menu = new FrameLayout(this);
        TextView menuIcon = iconButton("☰");
        menuIcon.setOnClickListener(this::showMenu);
        menu.addView(menuIcon, new FrameLayout.LayoutParams(UiKit.dp(this, 40), UiKit.dp(this, 40)));
        menuDot = new View(this);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(UiKit.accentColor(this));
        dot.setStroke(UiKit.dp(this, 2), UiKit.canvasColor(this));
        menuDot.setBackground(dot);
        menuDot.setVisibility(View.GONE);
        FrameLayout.LayoutParams dotParams = new FrameLayout.LayoutParams(UiKit.dp(this, 11), UiKit.dp(this, 11), Gravity.TOP | Gravity.END);
        dotParams.setMargins(0, UiKit.dp(this, 6), UiKit.dp(this, 5), 0);
        menu.addView(menuDot, dotParams);
        topBar.addView(menu, new LinearLayout.LayoutParams(UiKit.dp(this, 40), UiKit.dp(this, 40)));

        status = new TextView(this);
        status.setTextSize(13f);
        status.setGravity(Gravity.CENTER);
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 6), UiKit.dp(this, 12), UiKit.dp(this, 6));
        status.setOnClickListener(v -> fixFromStatus());
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        statusParams.setMargins(UiKit.dp(this, 6), 0, UiKit.dp(this, 6), 0);
        topBar.addView(status, statusParams);

        doneButton = new TextView(this);
        doneButton.setText(R.string.chatgpt_view_local);
        doneButton.setTextSize(15f);
        doneButton.setTypeface(Typeface.DEFAULT_BOLD);
        doneButton.setTextColor(UiKit.accentColor(this));
        doneButton.setGravity(Gravity.CENTER);
        doneButton.setPadding(UiKit.dp(this, 10), 0, UiKit.dp(this, 6), 0);
        doneButton.setOnClickListener(v -> showWeb(false, true));
        topBar.addView(doneButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(this, 40)));

        TextView newChat = iconButton("✎");
        newChat.setContentDescription(getString(R.string.conversation_new_title));
        newChat.setOnClickListener(v -> loadPage(HOME_URL));
        topBar.addView(newChat, new LinearLayout.LayoutParams(UiKit.dp(this, 40), UiKit.dp(this, 40)));

        root.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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
                if (loadError.isEmpty()) setStatus(R.string.tools_preparing, "");
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
            public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame()) onLoadFailed(String.valueOf(error.getDescription()));
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                super.onReceivedHttpError(view, request, response);
                // 403/429 protection pages are still pages the user can act on.
                if (request.isForMainFrame() && response.getStatusCode() >= 500) onLoadFailed("HTTP " + response.getStatusCode());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                pageReady = true;

                bridgeAllowed = isTrustedChatUrl(url) && loadError.isEmpty();
                if (bridgeAllowed) {
                    bridgePageNonce =
                            UUID.randomUUID().toString().replace("-", "");
                    installLocalBridge();
                    view.postDelayed(ChatActivity.this::installConversationRuntime, 800L);
                    view.postDelayed(ChatActivity.this::installConversationRuntime, 2200L);
                } else {
                    bridgePageNonce = "";
                }

                view.removeCallbacks(modeSync);
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
        // OPhone AI's chat page (the same one the API platforms use) covers the engine page.
        FrameLayout stack = new FrameLayout(this);
        stack.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        chatView = new WebView(this);
        chatView.setBackgroundColor(UiKit.canvasColor(this));
        WebSettings chat = chatView.getSettings();
        chat.setJavaScriptEnabled(true);
        chat.setDomStorageEnabled(true);
        chat.setAllowFileAccess(false);
        chat.setAllowContentAccess(false);
        chat.setSupportZoom(false);
        chat.setTextZoom(100);
        chatView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { openExternal(request.getUrl().toString()); return true; }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) { return ApiChatActivity.serve(ChatActivity.this, request.getUrl()); }
            @Override public void onPageFinished(WebView view, String url) { chatReady = true; emitState(); }
        });
        chatView.addJavascriptInterface(new ChatBridge(), "OPhoneChat");
        conversationsJson = mergedConversations();
        stack.addView(chatView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        EdgeDrawer.attach(this, new EdgeDrawer.Host() {
            @Override public WebView chat() { return chatView; }
            @Override public boolean chatActive() { return !webShown && chatReady && chatView != null && !isInPictureInPictureMode(); }
            @Override public void back() { onBackPressed(); }
        });
        setContentView(stack);
        chatView.loadUrl(ApiChatActivity.ORIGIN + "/chat/index.html?provider=web&lang="
                + UiKit.language() + "&dark=" + (UiKit.isDark(this) ? 1 : 0));
        topBar.setVisibility(View.GONE);
        doneButton.setVisibility(View.GONE);
        // Open in the view the user chose last time; both views drive the same page and tools.
        if (prefersWeb()) showWeb(true, false);
        setStatus(R.string.tools_preparing, "");

        maybeRequestNotificationPermission();

        String initialUrl = trustedInitialChatUrl(
                getIntent().getStringExtra("url"));
        webView.loadUrl(initialUrl);
        acceptScheduledRun(getIntent());
    }

    private TextView iconButton(String glyph) {
        TextView b = new TextView(this);
        b.setText(glyph);
        b.setTextSize(19f);
        b.setGravity(Gravity.CENTER);
        b.setTextColor(UiKit.primaryTextColor(this));
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(UiKit.isDark(this) ? 0x3D767680 : 0x1F767680);
        b.setBackground(bg);
        return b;
    }

    /** Status pill: green when tools are ready, blue while working, red with the error code when something needs a tap. */
    private void setStatus(int res, String detail) {
        if (res == R.string.runtime_stalled && (statusRes != res || !String.valueOf(detail).equals(statusDetail)))
            DetailedDiagnostics.event(getApplicationContext(), "warning", "provider", "", "", "runtime_stalled",
                    "Executor stalled: " + (detail == null || detail.isEmpty() ? "unknown" : detail) + "; page stage=" + engineState.optString("activity")
                            + ", task_status=" + engineState.optString("task_status") + ", running=" + running, null);
        statusRes = res;
        statusDetail = detail == null ? "" : detail;
        if (status == null) return;
        boolean failed = res == R.string.chatgpt_tools_failed || res == R.string.chatgpt_page_failed
                || res == R.string.runtime_stalled || res == R.string.agent_task_attention || res == R.string.conversation_runtime_failed;
        boolean busy = res == R.string.agent_task_running || res == R.string.runtime_recovering || res == R.string.tools_preparing;
        String text = res == R.string.chatgpt_tools_failed || res == R.string.chatgpt_page_failed
                ? getString(res, statusDetail.isEmpty() ? "?" : statusDetail) : getString(res);
        if (res == R.string.runtime_stalled || res == R.string.agent_task_attention) text += " · " + getString(R.string.chatgpt_tap_retry);
        status.setText((failed ? "⚠ " : busy ? "● " : "✓ ") + text);
        int color = failed ? 0xFFFF3B30 : busy ? UiKit.accentColor(this) : 0xFF34C759;
        status.setTextColor(color);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(UiKit.dp(this, 16));
        bg.setColor((color & 0x00FFFFFF) | 0x1F000000);
        status.setBackground(bg);
        emitState();
    }

    /** Tapping the status fixes what it reports: reload an unreachable page, or restart the tool handshake. */
    private void fixFromStatus() {
        if (webView == null) return;
        if (!loadError.isEmpty() || statusRes == R.string.chatgpt_page_failed) { reloadPage(true); return; }
        if (statusRes == R.string.chatgpt_tools_failed || statusRes == R.string.runtime_stalled || statusRes == R.string.agent_task_attention || statusRes == R.string.conversation_runtime_failed) {
            nativeRepairAttempts = 0;
            setStatus(R.string.tools_preparing, "");
            if (bridgeAllowed) {
                webView.evaluateJavascript("(function(){var r=window.__OPhoneAIRuntime;if(!r){return 'missing';}if(r.userRetry)r.userRetry();else r.retryProtocol();return 'ok';})()", value -> {
                    if (value == null || value.contains("missing")) installConversationRuntime();
                });
            } else reloadPage(true);
            return;
        }
        Toast.makeText(this, status.getText(), Toast.LENGTH_SHORT).show();
    }

    private void onLoadFailed(String description) {
        loadError = description == null || description.isEmpty() ? "net::ERR_FAILED" : description;
        bridgeAllowed = false;
        DetailedDiagnostics.event(getApplicationContext(), "warning", "provider", "", "", "chatgpt_page_load_failed",
                "chatgpt.com did not load: " + loadError, null);
        setStatus(R.string.chatgpt_page_failed, loadError);
        if (webView != null) {
            webView.removeCallbacks(retryLoad);
            retryPending = loadRetries < RETRY_DELAYS_MS.length;
            if (retryPending) webView.postDelayed(retryLoad, RETRY_DELAYS_MS[loadRetries++]);
        }
    }

    /** Loads the current conversation again; a manual retry also restarts the automatic ones. */
    private void reloadPage(boolean manual) {
        if (webView == null) return;
        if (manual) { loadRetries = 0; retryPending = false; webView.removeCallbacks(retryLoad); }
        String url = webView.getUrl();
        loadError = "";
        pageReady = false;
        setStatus(R.string.tools_preparing, "");
        webView.loadUrl(isTrustedChatUrl(url) ? url : HOME_URL);
    }

    private void loadPage(String url) {
        if (webView == null || !isTrustedChatUrl(url)) return;
        loadError = "";
        pageReady = false;
        pendingSubmits.clear();
        try { engineState = new JSONObject().put("conversation", url.contains("/c/") ? "c:" + url.substring(url.lastIndexOf('/') + 1) : ""); } catch (Exception ignored) {}
        snapshotJson = "{\"items\":[]}";
        running = false;
        noComposerSince = 0;
        webView.loadUrl(url);
        emit("reload");
    }

    /* ---------- local chat page <-> engine page ---------- */

    /** Calls from the chat page arrive on a binder thread; anything touching views hops to the UI thread. */
    private final class ChatBridge {
        @JavascriptInterface public String state() {
            try {
                JSONObject s = engineState;
                String attention = s.optString("attention"), lastError = s.optString("last_error");
                if (attention.isEmpty() && (statusRes == R.string.runtime_stalled || statusRes == R.string.chatgpt_tools_failed)) { attention = "runtime_stalled"; if (lastError.isEmpty()) lastError = statusDetail; }
                return new JSONObject().put("conversation", s.optString("conversation"))
                        .put("running", running).put("has_key", true).put("model", "ChatGPT")
                        .put("logged_in", s.has("logged_in") ? s.opt("logged_in") : JSONObject.NULL)
                        .put("attention", attention).put("last_error", lastError).put("can_retry", s.optBoolean("can_retry") || !attention.isEmpty())
                        .put("page_attention", pageAttention && loadError.isEmpty()).put("loading", !s.has("runtime") && loadError.isEmpty())
                        .put("setup", s.has("setup") ? s.opt("setup") : JSONObject.NULL)
                        .put("load_error", loadError).put("load_retrying", !loadError.isEmpty() && retryPending)
                        .put("update", updates.json()).toString();
            } catch (Exception e) { return "{}"; }
        }
        @JavascriptInterface public String history() { return snapshotJson; }
        @JavascriptInterface public String conversations() {
            // At most every 15 s: showing the list must not start a read-show-read loop.
            long now = System.currentTimeMillis();
            if (now - lastListRefresh > 15000) { lastListRefresh = now; ui(ChatActivity.this::refreshConversations); }
            return conversationsJson;
        }
        @JavascriptInterface public String miniapps() { try { return MiniAppStore.list(ChatActivity.this).getJSONArray("apps").toString(); } catch (Exception e) { return "[]"; } }
        @JavascriptInterface public void openMiniapp(String name) {
            ui(() -> { try { MiniAppStore.meta(ChatActivity.this, MiniAppStore.name(name)); startActivity(MiniAppActivity.intent(ChatActivity.this, name)); }
                catch (Exception e) { Toast.makeText(ChatActivity.this, String.valueOf(e.getMessage()), Toast.LENGTH_SHORT).show(); } });
        }
        @JavascriptInterface public String send(String text) {
            String t = text == null ? "" : text.trim();
            if (t.isEmpty()) return "empty";
            boolean guided = running;
            ui(() -> submitToEngine(t));
            return guided ? "guided" : "sent";
        }
        @JavascriptInterface public void stop() { ui(ChatActivity.this::stopTask); }
        @JavascriptInterface public String newChat() {
            if (running) return toast(R.string.api_stop_first);
            ui(() -> loadPage(HOME_URL));
            return "ok";
        }
        @JavascriptInterface public String select(String id) {
            if (id == null || !id.matches("c:[A-Za-z0-9-]{1,80}")) return "missing";
            if (running && !id.equals(engineState.optString("conversation"))) return toast(R.string.api_stop_first);
            ui(() -> loadPage(HOME_URL + "c/" + id.substring(2)));
            return "ok";
        }
        /** Pull at the top of the chat page: chatgpt.com loads older turns; they arrive with the next snapshots. */
        @JavascriptInterface public void loadOlder() { ui(() -> runtimeCall("loadOlder")); }
        /** End of the drawer list: the next page of conversations from chatgpt.com. */
        @JavascriptInterface public void moreConversations() { ui(() -> { lastListRefresh = System.currentTimeMillis(); runtimeCall("moreConversations"); }); }
        /** The page's model or reasoning-level picker: list its choices (answered in the snapshot as model_menu). */
        @JavascriptInterface public void modelMenu(String kind, String id) {
            if (!"model".equals(kind) && !"effort".equals(kind) || id == null || !id.matches("[A-Za-z0-9-]{1,40}")) return;
            ui(() -> runtimeInvoke("modelOptions", new JSONArray().put(kind).put(id)));
        }
        /** Choose on the page itself; the label the page then shows is what the chat view displays. */
        @JavascriptInterface public void chooseModel(String kind, String label, String group) {
            if (!"model".equals(kind) && !"effort".equals(kind) || label == null || label.isEmpty() || label.length() > 120) return;
            String parent = group == null || group.length() > 120 ? "" : group;
            ui(() -> runtimeInvoke("chooseModel", new JSONArray().put(kind).put(label).put(parent)));
        }
        @JavascriptInterface public void delete(String id) { toast(R.string.chatgpt_delete_in_page); }
        @JavascriptInterface public void copy(String text) {
            ui(() -> { ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("OPhone AI", text));
                Toast.makeText(ChatActivity.this, R.string.copied, Toast.LENGTH_SHORT).show(); });
        }
        @JavascriptInterface public void retry() {
            ui(() -> {
                if (!loadError.isEmpty() || !bridgeAllowed) { reloadPage(true); return; }
                nativeRepairAttempts = 0;
                webView.evaluateJavascript("(function(){var r=window.__OPhoneAIRuntime;if(!r)return 'missing';if(r.userRetry)r.userRetry();else r.retryProtocol();return 'ok';})()",
                        value -> { if (value == null || value.contains("missing")) installConversationRuntime(); else pollSnapshot(); });
            });
        }
        @JavascriptInterface public void reload() { ui(() -> reloadPage(true)); }
        @JavascriptInterface public void update() { ui(updates::act); }
        @JavascriptInterface public void open(String target) {
            ui(() -> {
                if ("settings".equals(target)) startActivity(new Intent(ChatActivity.this, SettingsActivity.class));
                else if ("model".equals(target) || "web".equals(target)) showWeb(true, true);
                else if ("about".equals(target)) startActivity(new Intent(ChatActivity.this, AboutActivity.class));
                else if ("schedules".equals(target)) startActivity(new Intent(ChatActivity.this, ScheduleActivity.class));
                else if ("feedback".equals(target)) startActivity(new Intent(ChatActivity.this, FeedbackActivity.class));
                else if ("platform".equals(target)) startActivity(new Intent(ChatActivity.this, PlatformActivity.class)
                        .putExtra(PlatformPreference.EXTRA_SHOW_CHOOSER, true).putExtra(PlatformActivity.EXTRA_SWITCH_FROM, PlatformSession.CHATGPT));
                else if ("tools".equals(target)) startActivity(new Intent(ChatActivity.this, ToolsActivity.class));
                else if ("share".equals(target)) startActivity(new Intent(ChatActivity.this, ShareActivity.class));
                else if ("browser".equals(target)) {
                    try { WorkbenchClient.get(ChatActivity.this).showBrowser(); }
                    catch (Exception e) { Toast.makeText(ChatActivity.this, e.getMessage(), Toast.LENGTH_LONG).show(); }
                }
            });
        }
        @JavascriptInterface public void openLink(String url) { ui(() -> openExternal(url)); }
        private String toast(int res) { ui(() -> Toast.makeText(ChatActivity.this, res, Toast.LENGTH_SHORT).show()); return "busy"; }
    }

    private void emit(String type) {
        if (!chatReady || chatView == null) return;
        try { emit(new JSONObject().put("type", type)); } catch (Exception ignored) {}
    }
    private void emit(JSONObject event) {
        if (chatReady && chatView != null)
            chatView.evaluateJavascript("window.OPhoneChatApp&&window.OPhoneChatApp.event(" + JSONObject.quote(event.toString()) + ")", null);
    }
    private void emitState() { emit("state"); }

    /** Messages wait here until the engine page has its runtime; the runtime then queues them until the page is ready to send. */
    private void submitToEngine(String text) {
        if (webView == null) return;
        if (!bridgeAllowed || !pageReady) { pendingSubmits.add(text); return; }
        webView.evaluateJavascript("(function(){var r=window.__OPhoneAIRuntime;return r&&r.submit?r.submit(" + JSONObject.quote(text) + "):'missing';})()", value -> {
            if (value == null || value.contains("missing")) pendingSubmits.add(text); else pollSnapshot();
        });
    }

    private void stopTask() {
        running = false;
        if (webView == null || !bridgeAllowed) return;
        webView.evaluateJavascript("(function(){var r=window.__OPhoneAIRuntime;return r&&r.userStop?r.userStop():null;})()", value -> {
            String conversation = engineState.optString("conversation");
            try {
                Object parsed = new JSONTokener(value == null ? "null" : value).nextValue();
                if (parsed instanceof JSONObject && !((JSONObject) parsed).optString("conversation").isEmpty()) conversation = ((JSONObject) parsed).optString("conversation");
            } catch (Exception ignored) {}
            if (!conversation.isEmpty()) {
                String target = conversation;
                new Thread(() -> PlatformSession.cancelConversation(getApplicationContext(), target, "User stopped this task in the chat."), "OPhoneAI-Stop").start();
            }
            pollSnapshot();
        });
    }

    private void refreshConversations() {
        if (webView != null && bridgeAllowed)
            webView.evaluateJavascript("window.__OPhoneAIRuntime&&window.__OPhoneAIRuntime.refreshConversations&&window.__OPhoneAIRuntime.refreshConversations()", null);
    }

    /** Runs a parameterless runtime function on the page (fixed names only), then reads the page again soon. */
    private void runtimeCall(String name) {
        if (webView == null || !bridgeAllowed || !name.matches("[A-Za-z]{1,40}")) return;
        webView.evaluateJavascript("(function(){var r=window.__OPhoneAIRuntime;return r&&r." + name + "?JSON.stringify(r." + name + "()):'missing';})()", value -> {
            webView.postDelayed(this::pollSnapshot, 1200);
            webView.postDelayed(this::pollSnapshot, 3500);
        });
    }

    /** Runs a runtime function with JSON arguments (fixed names only) and reads the page a few times while it works. */
    private void runtimeInvoke(String name, JSONArray args) {
        if (webView == null || !bridgeAllowed || !name.matches("[A-Za-z]{1,40}")) return;
        webView.evaluateJavascript("(function(){var r=window.__OPhoneAIRuntime;return r&&r." + name + "?JSON.stringify(r." + name + ".apply(r," + args + ")):'missing';})()", value -> {
            for (int delay : new int[]{500, 1500, 3000, 5000, 8000}) webView.postDelayed(this::pollSnapshot, delay);
        });
    }

    /** Reads the engine page into render items and forwards changes to the chat page. */
    private void pollSnapshot() {
        if (webView == null || snapshotBusy) return;
        if (!bridgeAllowed || !pageReady) { updatePageAttention(false); return; }
        snapshotBusy = true;
        final String nonce = bridgePageNonce;
        webView.evaluateJavascript("(function(){var r=window.__OPhoneAIRuntime;return r&&r.snapshot?JSON.stringify(r.snapshot()):null;})()", value -> {
            snapshotBusy = false;
            if (!isBridgeRequestAllowed(nonce)) return;
            JSONObject snap = null;
            try { Object parsed = new JSONTokener(value == null ? "null" : value).nextValue(); if (parsed instanceof String) snap = new JSONObject((String) parsed); } catch (Exception ignored) {}
            if (snap == null) { updatePageAttention(false); return; }
            if (!pendingSubmits.isEmpty()) { List<String> queued = new ArrayList<>(pendingSubmits); pendingSubmits.clear(); for (String text : queued) submitToEngine(text); }
            updatePageAttention(snap.optBoolean("ready") || snap.optBoolean("composer"));
            JSONObject list = snap.optJSONObject("conversations");
            long listAt = snap.optLong("conversations_at");
            snap.remove("conversations");
            snap.remove("conversations_at");
            boolean stateChanged = false;
            try {
                JSONObject state = new JSONObject(snap.toString());
                state.remove("items");
                if (list != null) state.put("logged_in", list.optBoolean("logged_in", true));
                else if (engineState.has("logged_in")) state.put("logged_in", engineState.opt("logged_in"));
                JSONObject before = new JSONObject(engineState.toString());
                engineState = state;
                stateChanged = !before.toString().equals(state.toString());
            } catch (Exception ignored) {}
            running = snap.optBoolean("running");
            advanceScheduledRun(snap);
            rememberModelChoices(snap.optJSONObject("model_menu"));
            boolean remembered = rememberConversation(snap);
            if (list != null && listAt != conversationsAt) {
                conversationsAt = listAt;
                pageConversations = list.optJSONArray("items") == null ? new JSONArray() : list.optJSONArray("items");
                keepListed(pageConversations);
                String error = list.optString("error");
                if (!error.isEmpty() && !error.equals(lastListError))
                    DetailedDiagnostics.event(getApplicationContext(), "warning", "provider", "", "", "chatgpt_conversation_list_failed",
                            "ChatGPT conversation list could not be read (" + error + "); showing links on the page and conversations used here.", null);
                lastListError = error;
                remembered = true;
            }
            if (remembered) { conversationsJson = mergedConversations(); emit("conversations"); }
            String text = snap.toString();
            if (text.equals(snapshotJson)) { if (stateChanged) emitState(); return; }
            String previousConversation = conversationOf(snapshotJson);
            snapshotJson = text;
            try { emit(new JSONObject().put("type", "snapshot").put("snapshot", snap)); } catch (Exception ignored) {}
            if (!snap.optString("conversation").equals(previousConversation)) refreshConversations();
        });
    }
    /** The page's model and reasoning-level choices seen so far, offered when a schedule is edited. */
    private String lastModelMenu = "";
    private void rememberModelChoices(JSONObject menu) {
        if (menu == null || menu.optBoolean("busy") || menu.optJSONArray("items") == null || menu.optJSONArray("items").length() == 0) return;
        String key = menu.optString("id");
        if (key.equals(lastModelMenu)) return;
        lastModelMenu = key;
        JSONArray labels = new JSONArray(), items = menu.optJSONArray("items");
        for (int i = 0; i < items.length(); i++) { JSONObject item = items.optJSONObject(i); if (item != null) labels.put(item.optString("label")); }
        getSharedPreferences(MODEL_CHOICES, MODE_PRIVATE).edit().putString(menu.optString("kind", "model"), labels.toString()).apply();
    }
    static final String MODEL_CHOICES = "chatgpt_model_choices";
    /** Conversations known from chatgpt.com and used here, for choosing a schedule's target. */
    static JSONArray knownConversations(android.content.Context c) {
        try { return new JSONArray(c.getSharedPreferences(CONVERSATION_PREFS, MODE_PRIVATE).getString("items", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    /* ---------- conversation list: what chatgpt.com reports, plus every conversation used here ---------- */

    private static final String CONVERSATION_PREFS = "chatgpt_conversations";
    private JSONArray pageConversations = new JSONArray();
    private String lastListError = "";
    private volatile long lastListRefresh;

    /** Everything the page has ever listed is kept: a later read that fails or comes back short cannot empty the list. */
    private void keepListed(JSONArray items) {
        if (items == null || items.length() == 0) return;
        try {
            java.util.LinkedHashMap<String, JSONObject> byId = new java.util.LinkedHashMap<>();
            JSONArray known = knownConversations();
            for (int i = 0; i < known.length(); i++) { JSONObject c = known.optJSONObject(i); if (c != null) byId.put(c.optString("id"), c); }
            for (int i = 0; i < items.length(); i++) {
                JSONObject c = items.optJSONObject(i);
                if (c == null || !c.optString("id").matches("c:[A-Za-z0-9-]{8,80}")) continue;
                JSONObject have = byId.get(c.optString("id"));
                if (have == null) { byId.put(c.optString("id"), new JSONObject().put("id", c.optString("id")).put("title", c.optString("title")).put("updated_at", c.optLong("updated_at"))); continue; }
                if (!c.optString("title").isEmpty() && !have.optBoolean("local_title")) have.put("title", c.optString("title"));
                have.put("updated_at", Math.max(have.optLong("updated_at"), c.optLong("updated_at")));
            }
            List<JSONObject> all = new ArrayList<>(byId.values());
            java.util.Collections.sort(all, (a, b) -> Long.compare(b.optLong("updated_at"), a.optLong("updated_at")));
            JSONArray out = new JSONArray();
            for (JSONObject c : all) { if (out.length() >= 300) break; out.put(c); }
            getSharedPreferences(CONVERSATION_PREFS, MODE_PRIVATE).edit().putString("items", out.toString()).apply();
        } catch (Exception ignored) {}
    }

    private JSONArray knownConversations() {
        try { return new JSONArray(getSharedPreferences(CONVERSATION_PREFS, MODE_PRIVATE).getString("items", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    /** Keeps the open conversation (ID, title, last use) on the phone so the list never depends on the page alone. */
    private boolean rememberConversation(JSONObject snap) {
        String id = snap.optString("conversation");
        if (!id.matches("c:[A-Za-z0-9-]{8,80}")) return false;
        // The user's first real message names the chat (the page title of a connected chat is the setup's).
        String title = "";
        JSONArray items = snap.optJSONArray("items");
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && "user".equals(item.optString("type")) && !item.optBoolean("guidance") && !item.optBoolean("queued")) { title = item.optString("text").replaceAll("\\s+", " ").trim(); break; }
        }
        boolean fromUser = !title.isEmpty();
        if (!fromUser) title = snap.optString("title").replaceAll("\\s*[|\\-–]\\s*ChatGPT\\s*$", "").trim();
        if ("ChatGPT".equalsIgnoreCase(title) || title.startsWith("[[OPHONEAI")) title = "";
        if (title.length() > 40) title = title.substring(0, 40);
        try {
            JSONArray known = knownConversations(), next = new JSONArray();
            JSONObject current = null;
            for (int i = 0; i < known.length(); i++) { JSONObject c = known.getJSONObject(i); if (id.equals(c.optString("id"))) current = c; else next.put(c); }
            long now = System.currentTimeMillis();
            boolean changed = current == null || (!title.isEmpty() && !title.equals(current.optString("title"))) || now - current.optLong("updated_at") > 60000
                    || (fromUser && !current.optBoolean("local_title"));
            if (!changed) return false;
            boolean keepOld = current != null && (title.isEmpty() || (!fromUser && current.optBoolean("local_title")));
            JSONObject entry = new JSONObject().put("id", id).put("title", keepOld ? current.optString("title") : title).put("updated_at", now)
                    .put("local_title", fromUser || (keepOld && current.optBoolean("local_title")));
            JSONArray sorted = new JSONArray().put(entry);
            for (int i = 0; i < next.length() && sorted.length() < 200; i++) sorted.put(next.get(i));
            getSharedPreferences(CONVERSATION_PREFS, MODE_PRIVATE).edit().putString("items", sorted.toString()).apply();
            return true;
        } catch (Exception e) { return false; }
    }

    /** chatgpt.com's list merged with the conversations used here, newest first. */
    private String mergedConversations() {
        java.util.LinkedHashMap<String, JSONObject> byId = new java.util.LinkedHashMap<>();
        try {
            JSONArray known = knownConversations();
            for (JSONArray source : new JSONArray[]{pageConversations, known})
                for (int i = 0; i < source.length(); i++) {
                    JSONObject c = source.optJSONObject(i);
                    if (c == null || c.optString("id").isEmpty()) continue;
                    JSONObject have = byId.get(c.optString("id"));
                    if (have == null) { byId.put(c.optString("id"), new JSONObject(c.toString())); continue; }
                    if (have.optString("title").isEmpty() || (c.optBoolean("local_title") && !c.optString("title").isEmpty())) have.put("title", c.optString("title"));
                    have.put("updated_at", Math.max(have.optLong("updated_at"), c.optLong("updated_at")));
                }
            List<JSONObject> all = new ArrayList<>(byId.values());
            java.util.Collections.sort(all, (a, b) -> Long.compare(b.optLong("updated_at"), a.optLong("updated_at")));
            JSONArray out = new JSONArray();
            for (JSONObject c : all) out.put(c);
            return out.toString();
        } catch (Exception e) { return knownConversations().toString(); }
    }

    private static String conversationOf(String json) { try { return new JSONObject(json).optString("conversation"); } catch (Exception e) { return ""; } }

    /** No composer and no tools for a while usually means a sign-in page, a verification challenge or an error page. */
    private void updatePageAttention(boolean healthy) {
        long now = System.currentTimeMillis();
        boolean value;
        if (healthy) { noComposerSince = 0; value = false; }
        else { if (noComposerSince == 0) noComposerSince = now; value = now - noComposerSince > PAGE_ATTENTION_MS; }
        if (pageAttention == value) return;
        pageAttention = value;
        emitState();
    }

    /**
     * Self-check of the visible layer: in the page view the bar with "Local view" must be shown (outside PiP), in the
     * local view the chat page. Repairs any path that left them inconsistent (rotation, PiP, process restore).
     */
    private void enforceViewLayers() {
        if (chatView == null || topBar == null || doneButton == null) return;
        boolean pip = isInPictureInPictureMode();
        int chat = webShown ? View.GONE : View.VISIBLE, bar = webShown && !pip ? View.VISIBLE : View.GONE, done = webShown ? View.VISIBLE : View.GONE;
        if (chatView.getVisibility() != chat) chatView.setVisibility(chat);
        if (topBar.getVisibility() != bar) topBar.setVisibility(bar);
        if (doneButton.getVisibility() != done) doneButton.setVisibility(done);
    }

    private boolean prefersWeb() { return "web".equals(getSharedPreferences(UI_PREFS, MODE_PRIVATE).getString(KEY_VIEW, "local")); }

    /**
     * Switches between the local chat view and the chatgpt.com page. Only the visible layer changes: the page, its
     * runtime, a running task and queued messages are the same object underneath, so nothing is interrupted.
     */
    private void showWeb(boolean show, boolean remember) {
        if (remember) getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit().putString(KEY_VIEW, show ? "web" : "local").apply();
        webShown = show;
        chatView.setVisibility(show ? View.GONE : View.VISIBLE);
        topBar.setVisibility(show && !isInPictureInPictureMode() ? View.VISIBLE : View.GONE);
        doneButton.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) { refreshConversations(); pollSnapshot(); emitState(); }
    }

    private void openExternal(String url) {
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://")) || url.startsWith(ApiChatActivity.ORIGIN)) return;
        WebPageActivity.open(this, url);
    }

    private void renderUpdateDot() {
        if (menuDot != null) menuDot.setVisibility(updates.json().optBoolean("available") ? View.VISIBLE : View.GONE);
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
                if (activity.webView != null) activity.webView.clearFocus();
                if (activity.chatView != null) activity.chatView.clearFocus();
                PictureInPictureParams params =
                        new PictureInPictureParams.Builder()
                                .setAspectRatio(new Rational(9, 16))
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

    private void ui(Runnable r) { runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) r.run(); }); }

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
                        + "lazySetup:true,"
                        + "mcp:mcp,"
                        + "listTools:function(){"
                        + "return mcp({jsonrpc:'2.0',id:rpcId('tools'),method:'tools/list',params:{}})"
                        + ".then(function(r){return r&&r.result&&r.result.tools?r.result.tools:[];});"
                        + "},"
                        + "task:function(action,args){return mcp({jsonrpc:'2.0',id:rpcId('task'),method:'agent/task',params:{action:action,arguments:args||{}}}).then(function(r){return r.result;});},"
                        + "receipt:function(scope){return mcp({jsonrpc:'2.0',id:rpcId('receipt'),method:'runtime/receipt',ophoneai_context:scope}).then(function(r){return r.result;});},"
                        + "trace:function(code,call,tool){try{nativeBridge.trace(n,String(code),String(call||''),String(tool||''));}catch(e){}},"
                        + "focus:function(on){try{nativeBridge.focus(n,!!on);}catch(e){}},"
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
            String katex = katexForPage();
            if (!katex.isEmpty()) webView.evaluateJavascript(katex, null);
            webView.evaluateJavascript(runtime, null);
        } catch (Exception e) {
            setStatus(R.string.conversation_runtime_failed, "");
        }
    }

    /**
     * The chat view's own KaTeX, loaded into the ChatGPT page as a private object (no global "katex", no AMD), so
     * the runtime can tell which of the page's formulas the chat view could not render and send those as rendered.
     */
    private static String katexScript;
    private String katexForPage() {
        if (katexScript != null) return katexScript;
        try (InputStream in = getAssets().open("chat/vendor/katex/katex.min.js")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[16384]; int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
            katexScript = "(function(){if(window.__OPhoneAIKatex)return;try{window.__OPhoneAIKatex=(function(){var module={exports:{}},exports=module.exports,define=undefined;\n"
                    + new String(out.toByteArray(), StandardCharsets.UTF_8) + "\n;return module.exports;})();}catch(e){}})();";
        } catch (Exception e) { katexScript = ""; }
        return katexScript;
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
        /** The provider editor accepts programmatic text reliably only while its WebView holds input focus. */
        @JavascriptInterface
        public void focus(String nonce, boolean on) {
            if (!isBridgeRequestAllowed(nonce) || !on) return;
            ui(() -> { if (webView != null && !webView.hasFocus()) webView.requestFocus(); });
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
                : HOME_URL;
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

    private void showMenu(View anchor) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, anchor);
        JSONObject update = updates.json();
        boolean available = update.optBoolean("available");
        String updateState = update.optString("state");
        menu.getMenu().add(0, 1, 0, R.string.conversation_new_title);
        if (available) {
            String label = "downloaded".equals(updateState) ? getString(R.string.chatgpt_menu_install)
                    : "downloading".equals(updateState) ? getString(R.string.chatgpt_menu_downloading, Math.max(0, update.optInt("progress")))
                    : getString(R.string.chatgpt_menu_update);
            menu.getMenu().add(0, 2, 1, "● " + label);
        }
        menu.getMenu().add(0, 3, 2, R.string.browser);
        menu.getMenu().add(0, 4, 3, R.string.settings);
        menu.getMenu().add(0, 5, 4, available ? getString(R.string.about_title) + " ●" : getString(R.string.about_title));
        menu.getMenu().add(0, 6, 5, R.string.feedback_title);
        menu.getMenu().add(0, 7, 6, R.string.exit_platform);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: loadPage(HOME_URL); break;
                case 2: updates.act(); break;
                case 3:
                    try { WorkbenchClient.get(this).showBrowser(); }
                    catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); }
                    break;
                case 4: startActivity(new Intent(this, SettingsActivity.class)); break;
                case 5: startActivity(new Intent(this, AboutActivity.class)); break;
                case 6: startActivity(new Intent(this, FeedbackActivity.class)); break;
                default: exitPlatform();
            }
            return true;
        });
        menu.show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        String switchTo = PlatformSession.takeSwitchRequest();
        if (switchTo != null) { leaveTo(switchTo); return; }
        currentActivity = new WeakReference<>(this);
        enforceViewLayers();
        if (webView != null) { webView.removeCallbacks(modeSync); webView.postDelayed(modeSync, 1500L); }
        UpdateManager.maybeCheck(this);
        updates.attach();
        resumed = true;
        if (chatView != null) chatView.onResume();
        if (webView != null) { webView.removeCallbacks(snapshotSync); webView.post(snapshotSync); }
        emitState();
        // A delivery that failed while OPhone AI was in the background usually works once the page is visible again.
        if (failedWhileAway && webView != null && bridgeAllowed) {
            failedWhileAway = false;
            webView.postDelayed(() -> { if (bridgeAllowed) webView.evaluateJavascript("(function(){var r=window.__OPhoneAIRuntime;if(r&&r.userRetry)r.userRetry();})()", null); }, 800);
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        updates.detach();
        // The engine page keeps running; only the local view stops refreshing (PiP keeps it live).
        if (webView != null && !isInPictureInPictureMode()) webView.removeCallbacks(snapshotSync);
        super.onPause();
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);
        // The small window shows the local view; the chosen view comes back afterwards.
        if (isInPictureInPictureMode && webShown) showWeb(false, false);
        else if (!isInPictureInPictureMode && prefersWeb() && !webShown) showWeb(true, false);
        if (topBar != null) topBar.setVisibility(webShown && !isInPictureInPictureMode ? View.VISIBLE : View.GONE);
        if (webView != null && isInPictureInPictureMode) { webView.removeCallbacks(snapshotSync); webView.post(snapshotSync); }
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
            // Returning to the conversation that is already open must not reload it mid-answer.
            String shown = webView.getUrl();
            boolean same = shown != null && isTrustedChatUrl(shown) && Uri.parse(shown).getPath() != null
                    && Uri.parse(shown).getPath().equals(Uri.parse(url).getPath());
            if (!same && !handleAppUrl(url) && isTrustedChatUrl(url)) loadPage(url);
        }
        acceptScheduledRun(intent);
    }

    /* ---------- scheduled task (ScheduleRunner): open the conversation, set the page's model, then send ---------- */

    private JSONObject scheduled; private String scheduledStage = ""; private long scheduledStageAt, scheduledStartedAt;
    private final Runnable scheduledTick = new Runnable() { @Override public void run() {
        if (scheduled == null || webView == null) return; pollSnapshot(); webView.postDelayed(this, 1000); } };

    private void acceptScheduledRun(Intent intent) {
        JSONObject run = ScheduleStore.redeem(this, intent.getStringExtra(ScheduleRunner.EXTRA_ID), intent.getStringExtra(ScheduleRunner.EXTRA_TOKEN));
        intent.removeExtra(ScheduleRunner.EXTRA_TOKEN);
        if (run == null) return;
        if (scheduled != null || running) { ScheduleStore.record(this, run.optString("id"), "skipped", "chatgpt_busy"); return; }
        scheduled = run; scheduledStage = "load"; scheduledStageAt = scheduledStartedAt = System.currentTimeMillis();
        // Shown over the lock screen so the page keeps running; tasks that operate other apps still wait for an unlock.
        if (android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        if (webView != null) { webView.removeCallbacks(scheduledTick); webView.postDelayed(scheduledTick, 1500); }
    }

    private void scheduledNote(String note) { try { scheduled.put("note", note); } catch (Exception ignored) {} }

    private void endScheduledRun(String status, String detail) {
        if (scheduled == null) return;
        ScheduleStore.record(this, scheduled.optString("id"), status, detail);
        scheduled = null; scheduledStage = "";
        if (android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(false); setTurnScreenOn(false); }
    }

    private static boolean sameLabel(String shown, String wanted) {
        String a = shown == null ? "" : shown.trim().toLowerCase(Locale.ROOT), b = wanted == null ? "" : wanted.trim().toLowerCase(Locale.ROOT);
        return !b.isEmpty() && (a.equals(b) || a.endsWith(" " + b) || a.contains(b));
    }

    /** Steps the scheduled run along with each page snapshot; every step has a time limit. */
    private void advanceScheduledRun(JSONObject snap) {
        if (scheduled == null) return;
        long now = System.currentTimeMillis(), inStage = now - scheduledStageAt;
        JSONObject model = snap.optJSONObject("model"), menu = snap.optJSONObject("model_menu");
        String wantModel = scheduled.optString("model"), wantEffort = scheduled.optString("effort");
        switch (scheduledStage) {
            case "load":
                if (snap.optBoolean("composer") && !snap.optBoolean("running") && (model != null && model.optBoolean("can_choose") || inStage > 15000)) {
                    if (!wantModel.isEmpty() && model != null && !sameLabel(model.optString("label"), wantModel)) {
                        runtimeInvoke("chooseModel", new JSONArray().put("model").put(wantModel).put("")); scheduledStage = "model";
                    } else scheduledStage = "effort-check";
                    scheduledStageAt = now;
                } else if (inStage > 90000) endScheduledRun("failed", "chatgpt_page_not_ready");
                break;
            case "model":
                if (menu != null && menu.optString("id").startsWith("choose-") && !menu.optBoolean("busy") && menu.optLong("at") >= scheduledStageAt - 2000 || inStage > 20000) {
                    if (menu != null && !menu.optString("error").isEmpty()) scheduledNote("model_not_changed:" + menu.optString("error"));
                    scheduledStage = "effort-check"; scheduledStageAt = now;
                }
                break;
            case "effort-check":
                if (inStage < 1500) break;
                if (!wantEffort.isEmpty() && model != null && model.optBoolean("can_choose_effort") && !sameLabel(model.optString("effort"), wantEffort)) {
                    runtimeInvoke("chooseModel", new JSONArray().put("effort").put(wantEffort).put("")); scheduledStage = "effort";
                } else scheduledStage = "send";
                scheduledStageAt = now;
                break;
            case "effort":
                if (menu != null && menu.optString("id").startsWith("choose-") && !menu.optBoolean("busy") && menu.optLong("at") >= scheduledStageAt - 2000 || inStage > 20000) {
                    if (menu != null && !menu.optString("error").isEmpty()) scheduledNote("effort_not_changed:" + menu.optString("error"));
                    scheduledStage = "send"; scheduledStageAt = now;
                }
                break;
            case "send":
                if (inStage < 1200) break;
                submitToEngine(scheduled.optString("prompt"));
                scheduledStage = "sent"; scheduledStageAt = now;
                break;
            case "sent":
                // The new conversation's id appears once ChatGPT has created it; a thread schedule continues it next time.
                String conversation = snap.optString("conversation");
                if (!conversation.isEmpty() || inStage > 60000) {
                    String id = scheduled.optString("id");
                    if (!conversation.isEmpty() && ScheduleStore.CONV_THREAD.equals(scheduled.optString("conversation_mode")))
                        ScheduleStore.update(this, id, s -> s.put("thread", conversation));
                    String label = model == null ? "" : model.optString("label");
                    endScheduledRun("sent", "ChatGPT" + (label.isEmpty() ? "" : " · " + label) + (scheduled.optString("note").isEmpty() ? "" : " · " + scheduled.optString("note")));
                }
                break;
            default: break;
        }
    }

    /** An app event for the ChatGPT conversation: it reaches the model with the next tool result, or as a message. */
    public static void systemGuidance(String text) {
        ChatActivity host = currentActivity.get();
        if (host == null || host.webView == null) return;
        host.webView.post(() -> {
            if (host.bridgeAllowed && host.webView != null)
                host.webView.evaluateJavascript("(function(){var r=window.__OPhoneAIRuntime;if(r&&r.submit)r.submit(" + JSONObject.quote(text) + ");})()", null);
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
        if (webView == null || !pageReady || !bridgeAllowed || !loadError.isEmpty()) return;
        android.os.PowerManager power = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        if (power != null && !power.isInteractive()) return;
        final long now = System.currentTimeMillis();
        // Android's timer is independent of the page MutationObserver and JS interval.
        // A busy page (streaming reply, large DOM) can answer late; only a long silence counts as a stall.
        if (supervisionRequestedAt > lastSupervisionReply && now - supervisionRequestedAt < 15000) return;
        if (supervisionRequestedAt > lastSupervisionReply) {
            setStatus(R.string.runtime_stalled, "page_no_reply");
        }
        supervisionRequestedAt = now;
        final String requestedNonce = bridgePageNonce;
        webView.evaluateJavascript(
                "(function(){var r=window.__OPhoneAIRuntime;"
                        + "if(!window.OPhoneAI)return {missing:'bridge'};"
                        + "if(!r||!r.supervise)return {missing:'runtime'};"
                        + "return r.supervise();})()",
                value -> {
                    if (!pageReady || !isBridgeRequestAllowed(requestedNonce)) return;
                    lastSupervisionReply = System.currentTimeMillis();
                    try {
                        Object parsed = new JSONTokener(value == null ? "null" : value).nextValue();
                        if (!(parsed instanceof JSONObject)) return;
                        JSONObject health = (JSONObject) parsed;
                        if (health.has("missing")) {
                            if (nativeRepairAttempts >= 3) {
                                setStatus(R.string.chatgpt_tools_failed, health.optString("missing") + "_missing");
                                RuntimeDiagnostics.observe(health.put("exhausted", true));
                                return;
                            }
                            setStatus(R.string.tools_preparing, "");
                            if (System.currentTimeMillis() - lastRuntimeInstall > 5000) {
                                nativeRepairAttempts++;
                                RuntimeDiagnostics.record(getApplicationContext(), "runtime_reinstall", "", "");
                                if ("bridge".equals(health.optString("missing"))) installLocalBridge();
                                else installConversationRuntime();
                            }
                            return;
                        }
                        RuntimeDiagnostics.observe(health);
                        loadRetries = 0;
                        pageConversation = health.optString("conversation");
                        boolean attention = "failed".equals(health.optString("phase")) || health.optBoolean("exhausted")
                                || "failed".equals(health.optString("stage")) || health.optInt("delivery_attempts") >= 2;
                        // The page runtime gave up while the user was in another app: bring them back to this chat.
                        if (attention && !resumed && !failedWhileAway) {
                            failedWhileAway = true;
                            TaskProgress.runtimeGaveUp(ChatActivity.this, pageConversation);
                        }
                        String stage = health.optString("stage");
                        if ("failed".equals(health.optString("phase"))) setStatus(R.string.chatgpt_tools_failed, health.optString("last_error"));
                        else if (health.optBoolean("exhausted")) setStatus(R.string.runtime_stalled, health.optString("last_error"));
                        else if ("failed".equals(stage)) setStatus(R.string.agent_task_attention, health.optString("last_error"));
                        else if ("recovering".equals(stage)) setStatus(R.string.runtime_recovering, "");
                        else if (health.optBoolean("active_task")) setStatus(R.string.agent_task_running, "");
                        else if (health.optBoolean("standby")) setStatus(R.string.chatgpt_tools_standby, "");
                        else setStatus(health.optBoolean("ready") ? R.string.local_ready : R.string.tools_preparing, "");
                    } catch (Exception ignored) {}
                });
    }

    private void startBridgeService() {
        startService(new Intent(this, BridgeService.class));
    }

    /** Chosen in the platform chooser: the same cleanup as leaving, then straight into the other platform. */
    private void leaveTo(String provider) {
        stopPlatform();
        PlatformSession.switchTo(this, provider);
        startActivity(new Intent(this, PlatformPreference.target(this, provider).activity));
        finish();
    }

    private void stopPlatform() {
        bridgeAllowed = false;
        if (webView != null) {
            webView.evaluateJavascript("(function(){try{var b=document.querySelector('[data-testid=stop-button],button[aria-label*=Stop],button[aria-label*=停止]');if(b)b.click();if(window.__OPhoneAIRuntime&&window.__OPhoneAIRuntime.stop)window.__OPhoneAIRuntime.stop();}catch(e){}return true;})()", null);
            webView.stopLoading();
            webView.loadUrl("about:blank");
        }
        PlatformSession.cancelProvider(this, PlatformSession.CHATGPT);
        PlatformPreference.clear(this);
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
        if (webShown) { showWeb(false, true); return; }
        if (!chatReady || chatView == null) { exitPlatform(); return; }
        // The chat page answers Back itself (closes a sheet or the drawer, otherwise opens the drawer); leaving the
        // platform is in the drawer.
        chatView.evaluateJavascript("window.OPhoneChatApp?window.OPhoneChatApp.back():false", value -> { if (!"true".equals(value)) exitPlatform(); });
    }

    @Override
    protected void onDestroy() {
        ChatActivity current = currentActivity.get();
        if (current == this) currentActivity.clear();
        bridgeAllowed = false;
        bridgePageNonce = "";

        if (chatView != null) {
            chatView.removeJavascriptInterface("OPhoneChat");
            chatView.destroy();
        }
        if (webView != null) {
            webView.removeCallbacks(snapshotSync);
            webView.removeCallbacks(retryLoad);
            webView.removeCallbacks(modeSync);
            webView.stopLoading();
            webView.destroy();
        }

        super.onDestroy();
    }
}
