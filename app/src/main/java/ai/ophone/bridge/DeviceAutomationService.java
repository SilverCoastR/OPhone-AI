package ai.ophone.bridge;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class DeviceAutomationService extends AccessibilityService {
    private static volatile DeviceAutomationService instance;

    public static DeviceAutomationService getInstance() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        DetailedDiagnostics.event(this,"info","accessibility","","","accessibility_connected","Android accessibility service connected.",null);

        Intent service = new Intent(this, BridgeService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(service);
        } else {
            startService(service);
        }
    }

    @Override
    public void onDestroy() {
        instance = null;
        DetailedDiagnostics.event(this,"warn","accessibility","","","accessibility_disconnected","Android accessibility service disconnected.",null);
        super.onDestroy();
    }

    private final android.os.Handler eventHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable pendingWindowSignal;
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if(event!=null&&event.getEventType()==AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED&&!(event.getParcelableData() instanceof android.app.Notification)){recordToast(safe(event.getPackageName()),event.getText());return;}
        if(event==null||!(event.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED||event.getEventType()==AccessibilityEvent.TYPE_WINDOWS_CHANGED))return;
        final String sourcePackage=safe(event.getPackageName()),className=safe(event.getClassName());final long eventTime=event.getEventTime();
        if(pendingWindowSignal!=null)eventHandler.removeCallbacks(pendingWindowSignal);
        pendingWindowSignal=()->{String actual=currentPackageName();if(!actual.isEmpty())AgentEvents.get(this).foreground(actual,actual.equals(sourcePackage)?className:"",eventTime);};
        eventHandler.postDelayed(pendingWindowSignal,80);
    }

    // Toasts arrive as notification-state events without a Notification payload. Only toast text from
    // apps allowed by the user policy is kept, in a short in-memory ring.
    private final ArrayDeque<JSONObject> toasts = new ArrayDeque<>();
    private void recordToast(String pkg, java.util.List<CharSequence> parts) {
        if (pkg.isEmpty() || !AppControlPolicy.isAllowed(this, pkg) || parts == null || parts.isEmpty()) return;
        StringBuilder text = new StringBuilder();
        for (CharSequence part : parts) if (part != null) text.append(text.length() > 0 ? " " : "").append(part);
        if (text.length() == 0) return;
        try {
            JSONObject toast = new JSONObject().put("at", System.currentTimeMillis()).put("package", pkg)
                    .put("text", text.length() > 300 ? text.substring(0, 300) : text.toString());
            synchronized (toasts) { toasts.addLast(toast); while (toasts.size() > 20) toasts.removeFirst(); }
            AgentEvents.get(this).toast(pkg, toast);
        } catch (Exception ignored) {}
    }
    public JSONArray toastsSince(long since) {
        JSONArray out = new JSONArray();
        synchronized (toasts) { for (JSONObject t : toasts) if (t.optLong("at") >= since) out.put(t); }
        return out;
    }

    /** Cheap UI fingerprint: foreground package plus visible text/description strings. */
    public JSONObject uiSignature() throws Exception {
        JSONObject snap = snapshot();
        java.util.LinkedHashSet<String> texts = new java.util.LinkedHashSet<>();
        JSONArray nodes = snap.optJSONArray("nodes");
        for (int i = 0; nodes != null && i < nodes.length(); i++) {
            JSONObject n = nodes.getJSONObject(i);
            String label = n.optString("text").isEmpty() ? n.optString("desc") : n.optString("text");
            label = label.replaceAll("\\s+", " ").trim();
            if (!label.isEmpty()) texts.add(label.length() > 80 ? label.substring(0, 80) : label);
        }
        return new JSONObject().put("package", snap.optString("package")).put("nodes", nodes == null ? 0 : nodes.length())
                .put("texts", new JSONArray(texts));
    }
    private static java.util.Set<String> textSet(JSONObject sig) {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        JSONArray a = sig.optJSONArray("texts");
        for (int i = 0; a != null && i < a.length(); i++) set.add(a.optString(i));
        return set;
    }

    /** After an action: wait until the UI stops changing (bounded), then summarize what changed. */
    public JSONObject effect(JSONObject before, long actionAt, long maxWaitMs) throws Exception {
        long start = System.currentTimeMillis();
        JSONObject previous = null, current = uiSignature();
        boolean stable = false;
        while (System.currentTimeMillis() - start < maxWaitMs) {
            Thread.sleep(150);
            previous = current;
            current = uiSignature();
            if (previous.toString().equals(current.toString()) && System.currentTimeMillis() - start >= 300) { stable = true; break; }
        }
        java.util.Set<String> was = textSet(before), now = textSet(current);
        JSONArray added = new JSONArray(), removed = new JSONArray();
        for (String t : now) if (!was.contains(t) && added.length() < 12) added.put(t);
        for (String t : was) if (!now.contains(t) && removed.length() < 12) removed.put(t);
        boolean packageChanged = !before.optString("package").equals(current.optString("package"));
        JSONArray toastList = toastsSince(actionAt);
        boolean changed = packageChanged || added.length() > 0 || removed.length() > 0 || before.optInt("nodes") != current.optInt("nodes");
        JSONObject out = new JSONObject().put("changed", changed).put("stable", stable)
                .put("settle_ms", System.currentTimeMillis() - start)
                .put("package_before", before.optString("package")).put("package_after", current.optString("package"))
                .put("added_text", added).put("removed_text", removed).put("toasts", toastList);
        if (!changed && toastList.length() == 0) out.put("hint", "no_visible_change: the action may have missed its target; inspect get_ui before repeating");
        return out;
    }

    /** Poll the UI until a condition holds: text appears, text disappears, a package is foreground, or the UI is stable. */
    public JSONObject waitFor(String mode, String text, String pkg, long timeoutMs) throws Exception {
        long start = System.currentTimeMillis();
        String lastSig = "";
        long stableSince = start;
        while (true) {
            JSONObject snap = snapshot();
            String current = snap.optString("package");
            JSONArray matches = new JSONArray();
            JSONArray nodes = snap.optJSONArray("nodes");
            if (!text.isEmpty()) {
                String needle = text.toLowerCase(Locale.ROOT);
                for (int i = 0; nodes != null && i < nodes.length() && matches.length() < 5; i++) {
                    JSONObject n = nodes.getJSONObject(i);
                    if ((n.optString("text") + "\n" + n.optString("desc")).toLowerCase(Locale.ROOT).contains(needle))
                        matches.put(new JSONObject().put("index", n.optInt("index")).put("text", n.optString("text")).put("desc", n.optString("desc")).put("bounds", n.optString("bounds")).put("clickable", n.optBoolean("clickable")));
                }
            }
            String sig = current + "|" + (nodes == null ? "" : String.valueOf(nodes.toString().hashCode()));
            long now = System.currentTimeMillis();
            if (!sig.equals(lastSig)) { lastSig = sig; stableSince = now; }
            boolean done;
            if ("disappear".equals(mode)) done = matches.length() == 0;
            else if ("package".equals(mode)) done = pkg.equals(current);
            else if ("stable".equals(mode)) done = now - stableSince >= 600;
            else done = matches.length() > 0 && (pkg.isEmpty() || pkg.equals(current));
            if (done || now - start >= timeoutMs) {
                return new JSONObject().put("ok", true).put("matched", done).put("timed_out", !done).put("mode", mode)
                        .put("elapsed_ms", now - start).put("package", current).put("matches", matches);
            }
            Thread.sleep(200);
        }
    }

    @Override
    public void onInterrupt() {}

    public String currentPackageName() {
        AccessibilityNodeInfo root = automationRoot();
        return root == null ? "" : safe(root.getPackageName());
    }

    private AccessibilityNodeInfo automationRoot() {
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (!ChatActivity.isAutomationInPictureInPicture()) return active;
        if (active != null && !getPackageName().equals(safe(active.getPackageName()))) return active;

        // The chat composer can become Accessibility-active inside PiP while
        // the external app remains the full-screen application window.
        AccessibilityNodeInfo candidate = null;
        long bestScore = -1;
        for (AccessibilityWindowInfo window : getWindows()) {
            if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null || getPackageName().equals(safe(root.getPackageName()))) continue;
            android.graphics.Rect bounds = new android.graphics.Rect();
            window.getBoundsInScreen(bounds);
            long score = Math.max(0, bounds.width()) * (long) Math.max(0, bounds.height());
            if (window.isFocused()) score += 1L << 50;
            if (window.isActive()) score += 1L << 49;
            if (score > bestScore) { candidate = root; bestScore = score; }
        }
        // Refuse to treat the chat PiP as the external automation target.
        return candidate;
    }

    public JSONObject snapshot() throws Exception {
        JSONObject out = new JSONObject();
        AccessibilityNodeInfo root = automationRoot();
        if (root == null) {
            out.put("ok", false);
            out.put("error", "no_active_window");
            return out;
        }

        JSONArray nodes = new JSONArray();
        Queue<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        int index = 0;

        while (!q.isEmpty() && index < 500) {
            AccessibilityNodeInfo n = q.remove();
            JSONObject j = new JSONObject();
            j.put("index", index++);
            j.put("class", safe(n.getClassName()));
            j.put("text", safe(n.getText()));
            j.put("desc", safe(n.getContentDescription()));
            j.put("viewId", safe(n.getViewIdResourceName()));
            android.graphics.Rect r = new android.graphics.Rect();
            n.getBoundsInScreen(r);
            j.put("bounds", r.flattenToString());
            j.put("clickable", n.isClickable());
            j.put("editable", n.isEditable());
            j.put("focused", n.isFocused());
            j.put("accessibilityFocused", n.isAccessibilityFocused());
            j.put("enabled", n.isEnabled());
            nodes.put(j);

            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }

        out.put("ok", true);
        out.put("package", safe(root.getPackageName()));
        out.put("nodes", nodes);
        return out;
    }

    /** The task status pill is hidden while the screen is captured, so the AI sees only the phone's own UI. */
    public JSONObject screenshotPngBase64(long timeoutMs) throws Exception {
        boolean hid = TaskProgress.visible();
        if (hid) { TaskProgress.hideForCapture(true); Thread.sleep(150); }
        try { return capturePng(timeoutMs); }
        finally { if (hid) TaskProgress.hideForCapture(false); }
    }

    private JSONObject capturePng(long timeoutMs) throws Exception {
        JSONObject out = new JSONObject();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            out.put("ok", false);
            out.put("error", "screenshot_requires_android_11");
            return out;
        }

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<JSONObject> resultRef = new AtomicReference<>();
        final AtomicReference<Exception> errorRef = new AtomicReference<>();

        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult screenshot) {
                HardwareBuffer buffer = null;
                Bitmap copy = null;
                try {
                    buffer = screenshot.getHardwareBuffer();
                    Bitmap hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.getColorSpace());
                    if (hardwareBitmap == null) {
                        throw new IllegalStateException("wrap_hardware_buffer_failed");
                    }
                    copy = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false);
                    if (copy == null) {
                        throw new IllegalStateException("bitmap_copy_failed");
                    }

                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    if (!copy.compress(Bitmap.CompressFormat.PNG, 100, bytes)) {
                        throw new IllegalStateException("png_compress_failed");
                    }

                    byte[] png = bytes.toByteArray();
                    JSONObject result = new JSONObject();
                    result.put("ok", true);
                    result.put("mime_type", "image/png");
                    result.put("width", copy.getWidth());
                    result.put("height", copy.getHeight());
                    result.put("bytes", png.length);
                    result.put("image_base64", Base64.encodeToString(png, Base64.NO_WRAP));
                    resultRef.set(result);
                } catch (Exception e) {
                    errorRef.set(e);
                } finally {
                    if (copy != null) copy.recycle();
                    if (buffer != null) buffer.close();
                    latch.countDown();
                }
            }

            @Override
            public void onFailure(int errorCode) {
                try {
                    JSONObject result = new JSONObject();
                    result.put("ok", false);
                    result.put("error", "take_screenshot_failed");
                    result.put("error_code", errorCode);
                    resultRef.set(result);
                } catch (Exception e) {
                    errorRef.set(e);
                } finally {
                    latch.countDown();
                }
            }
        });

        long wait = Math.max(1000L, Math.min(10000L, timeoutMs));
        if (!latch.await(wait, TimeUnit.MILLISECONDS)) {
            out.put("ok", false);
            out.put("error", "screenshot_timeout");
            return out;
        }
        if (errorRef.get() != null) throw errorRef.get();
        JSONObject result = resultRef.get();
        if (result == null) {
            out.put("ok", false);
            out.put("error", "screenshot_no_result");
            return out;
        }
        return result;
    }

    public boolean tap(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(p, 0, 80);
        TaskProgress.passThrough(80);
        return dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
    }

    public boolean swipe(float x1, float y1, float x2, float y2, long durationMs) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(p, 0, Math.max(100, durationMs));
        TaskProgress.passThrough(Math.max(100, durationMs));
        return dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
    }

    public boolean global(String action) {
        int a;
        switch (action) {
            case "back": a = GLOBAL_ACTION_BACK; break;
            case "home": a = GLOBAL_ACTION_HOME; break;
            case "recents": a = GLOBAL_ACTION_RECENTS; break;
            case "notifications": a = GLOBAL_ACTION_NOTIFICATIONS; break;
            case "quick_settings": a = GLOBAL_ACTION_QUICK_SETTINGS; break;
            default: return false;
        }
        return performGlobalAction(a);
    }

    public boolean clickText(String query, boolean contains) {
        AccessibilityNodeInfo root = automationRoot();
        if (root == null || query == null || query.isEmpty()) return false;
        String needle = query.toLowerCase(Locale.ROOT);

        Queue<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        int visited = 0;
        while (!q.isEmpty() && visited++ < 1000) {
            AccessibilityNodeInfo node = q.remove();
            String text = safe(node.getText()).toLowerCase(Locale.ROOT);
            String desc = safe(node.getContentDescription()).toLowerCase(Locale.ROOT);
            boolean matched = contains
                    ? text.contains(needle) || desc.contains(needle)
                    : text.equals(needle) || desc.equals(needle);
            if (matched && clickNodeOrParent(node)) return true;

            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) q.add(child);
            }
        }
        return false;
    }

    private boolean clickNodeOrParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; i < 8 && current != null; i++) {
            if (current.isClickable() && current.isEnabled()) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            current = current.getParent();
        }
        return false;
    }

    public boolean launchPackage(String packageName) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null) return false;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launch);
        return true;
    }

    public boolean setText(String text) {
        AccessibilityNodeInfo root = automationRoot();
        if (root == null) return false;

        AccessibilityNodeInfo target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (target == null || !target.isEditable()) {
            target = findFocusedEditable(root);
        }
        if (target == null) {
            target = findSingleEditable(root);
        }
        if (target == null || !target.isEnabled()) return false;

        if (!target.isFocused()) {
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        }
        if (!target.isAccessibilityFocused()) {
            target.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
        }

        Bundle args = new Bundle();
        args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text);
        return target.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                args);
    }

    private AccessibilityNodeInfo findFocusedEditable(AccessibilityNodeInfo n) {
        if (n.isEditable() && (n.isFocused() || n.isAccessibilityFocused())) {
            return n;
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c != null) {
                AccessibilityNodeInfo hit = findFocusedEditable(c);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findSingleEditable(AccessibilityNodeInfo root) {
        Queue<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        AccessibilityNodeInfo only = null;
        int visited = 0;

        while (!q.isEmpty() && visited++ < 1000) {
            AccessibilityNodeInfo node = q.remove();
            if (node.isEditable() && node.isEnabled()) {
                if (only != null) return null;
                only = node;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) q.add(child);
            }
        }
        return only;
    }

    private String safe(CharSequence s) {
        return s == null ? "" : s.toString();
    }
}
