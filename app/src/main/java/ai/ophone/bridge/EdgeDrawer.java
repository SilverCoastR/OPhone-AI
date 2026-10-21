package ai.ophone.bridge;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Build;
import android.view.View;
import android.webkit.WebView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Chat pages: a finger pulled in from the left or right screen edge drags the drawer out from that side, and Back
 * opens or closes the drawer instead of leaving the chat.
 *  - Android 10+: a band of each edge is excluded from the system back gesture, so the page's own edge drag gets
 *    the touches there (Android allows at most 200 dp per edge).
 *  - Android 14+: everywhere else on the edge, the system back gesture's progress and edge drive the drawer
 *    (predictive back, opted in per activity in the manifest; reached by reflection, as the app compiles for 33).
 */
final class EdgeDrawer {
    interface Host {
        WebView chat();
        /** The chat page is showing and can take a drawer drag (not the ChatGPT page, not loading). */
        boolean chatActive();
        /** Plain Back (button, or a gesture that did not drag): the activity's own handling. */
        void back();
    }
    private EdgeDrawer() {}

    static void attach(Activity activity, Host host) {
        WebView chat = host.chat();
        if (chat != null && Build.VERSION.SDK_INT >= 29)
            chat.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> exclude(v));
        if (Build.VERSION.SDK_INT >= 34) predictive(activity, host);
    }

    private static void exclude(View v) {
        if (Build.VERSION.SDK_INT < 29) return;
        int w = v.getWidth(), h = v.getHeight();
        if (w <= 0 || h <= 0) return;
        int band = Math.min(h, UiKit.dp(v.getContext(), 200)), edge = UiKit.dp(v.getContext(), 28);
        // Where a thumb naturally reaches: a little below the middle.
        int top = Math.max(0, Math.min(h - band, (int) (h * 0.6f) - band / 2));
        List<Rect> rects = new ArrayList<>();
        rects.add(new Rect(0, top, edge, top + band));
        rects.add(new Rect(w - edge, top, w, top + band));
        v.setSystemGestureExclusionRects(rects);
    }

    private static void predictive(Activity activity, Host host) {
        try {
            Class<?> animated = Class.forName("android.window.OnBackAnimationCallback");
            final boolean[] dragging = {false};
            Object callback = Proxy.newProxyInstance(animated.getClassLoader(), new Class<?>[]{animated}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "onBackStarted":
                        dragging[0] = host.chatActive();
                        if (dragging[0]) js(host, "start", side(args[0]), 0f);
                        return null;
                    case "onBackProgressed":
                        if (dragging[0]) js(host, "move", side(args[0]), progress(args[0]));
                        return null;
                    case "onBackInvoked":
                        if (dragging[0]) { dragging[0] = false; js(host, "end", "left", 1f); } else host.back();
                        return null;
                    case "onBackCancelled":
                        if (dragging[0]) { dragging[0] = false; js(host, "cancel", "left", 0f); }
                        return null;
                    case "hashCode": return System.identityHashCode(proxy);
                    case "equals": return proxy == args[0];
                    case "toString": return "EdgeDrawer";
                    default: return null;
                }
            });
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, (OnBackInvokedCallback) callback);
        } catch (Throwable e) {
            DetailedDiagnostics.event(activity, "warning", "ui", "", "", "edge_drawer_back_unavailable", "Predictive back for the drawer is unavailable; Back still opens the drawer.", e);
        }
    }

    private static String side(Object backEvent) {
        try { return ((Integer) backEvent.getClass().getMethod("getSwipeEdge").invoke(backEvent)) == 1 ? "right" : "left"; } catch (Exception e) { return "left"; }
    }
    private static float progress(Object backEvent) {
        try { return (Float) backEvent.getClass().getMethod("getProgress").invoke(backEvent); } catch (Exception e) { return 0f; }
    }
    private static void js(Host host, String phase, String side, float progress) {
        WebView chat = host.chat();
        if (chat != null) chat.evaluateJavascript(String.format(Locale.ROOT,
                "window.OPhoneChatApp&&window.OPhoneChatApp.edgeDrag&&window.OPhoneChatApp.edgeDrag('%s','%s',%.4f)", phase, side, progress), null);
    }
}
