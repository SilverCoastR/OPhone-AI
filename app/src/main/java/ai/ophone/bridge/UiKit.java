package ai.ophone.bridge;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class UiKit {
    private UiKit() {}

    public static int dp(Context context, float value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                context.getResources().getDisplayMetrics()));
    }

    /** The phone's language as one of the app's UI languages: zh, ja, ko, otherwise en. */
    public static String language() {
        String l = java.util.Locale.getDefault().getLanguage();
        return "zh".equals(l) || "ja".equals(l) || "ko".equals(l) ? l : "en";
    }

    /* ---------- night mode: follow the system (default), or always light / always dark ---------- */

    public static final String THEME_SYSTEM = "system", THEME_LIGHT = "light", THEME_DARK = "dark";
    private static volatile String themeMode;

    /** Read again from storage (activities can live in other processes, such as the built-in browser). */
    @SuppressWarnings("deprecation")
    public static String reloadThemeMode(Context context) {
        String mode = context.getApplicationContext().getSharedPreferences("ui_theme", Context.MODE_MULTI_PROCESS).getString("mode", THEME_SYSTEM);
        themeMode = THEME_LIGHT.equals(mode) || THEME_DARK.equals(mode) ? mode : THEME_SYSTEM;
        return themeMode;
    }

    public static String themeMode(Context context) {
        String mode = themeMode;
        return mode != null ? mode : reloadThemeMode(context);
    }

    public static void setThemeMode(Context context, String mode) {
        String value = THEME_LIGHT.equals(mode) || THEME_DARK.equals(mode) ? mode : THEME_SYSTEM;
        context.getApplicationContext().getSharedPreferences("ui_theme", Context.MODE_PRIVATE).edit().putString("mode", value).commit();
        themeMode = value;
    }

    /**
     * Called from every activity's attachBaseContext: a forced light or dark mode overrides only the night bit of
     * the configuration, so themes, values-night resources and WebView pages all follow it; everything else (font
     * size, orientation, locale) still comes from the system.
     */
    public static void applyTheme(android.app.Activity activity) {
        String mode = reloadThemeMode(activity);
        if (THEME_SYSTEM.equals(mode)) return;
        Configuration override = new Configuration();
        override.fontScale = 0;
        override.uiMode = (activity.getBaseContext().getResources().getConfiguration().uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (THEME_DARK.equals(mode) ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        try { activity.applyOverrideConfiguration(override); } catch (Exception ignored) {}
    }

    public static boolean isDark(Context context) {
        String forced = themeMode(context);
        if (THEME_DARK.equals(forced)) return true;
        if (THEME_LIGHT.equals(forced)) return false;
        int mode = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    public static int pageColor(Context context) {
        return Color.parseColor(isDark(context) ? "#161618" : "#F2F2F7");
    }

    /** Plain content canvas (white / dark gray), used behind full-screen web content. */
    public static int canvasColor(Context context) {
        return isDark(context) ? Color.parseColor("#161618") : Color.WHITE;
    }

    /** Matches status and navigation bars to a full-screen canvas. */
    /**
     * Landscape on phones with a cutout: by default Android leaves the cutout side as a black strip. The window is
     * laid out over the whole screen instead and the content uses that area too (the cutout is not avoided).
     */
    public static void fitCutout(android.app.Activity activity) {
        android.view.Window w = activity.getWindow();
        try {
            // The window itself is never inset for bars or the cutout; immersive() pads the content for the keyboard only.
            if (android.os.Build.VERSION.SDK_INT >= 30) w.setDecorFitsSystemWindows(false);
        } catch (RuntimeException ignored) {}
        if (android.os.Build.VERSION.SDK_INT < 28) return;
        android.view.WindowManager.LayoutParams lp = w.getAttributes();
        lp.layoutInDisplayCutoutMode = android.os.Build.VERSION.SDK_INT >= 30
                ? android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                : android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        w.setAttributes(lp);
    }

    /**
     * Replaces the framework's system-window padding of the content root: no space for the status bar, the
     * navigation bar or the cutout (that area shows content, also in landscape); only the keyboard pushes the
     * content up, unless the screen pans for the keyboard instead.
     */
    @SuppressWarnings("deprecation")
    private static void keyboardOnlyInsets(android.app.Activity activity, View decor) {
        View content = decor.findViewById(android.R.id.content);
        if (content == null) return;
        View root = content.getParent() instanceof View ? (View) content.getParent() : content;
        if (root.getTag(R.id.ophone_insets) != null) return;
        root.setTag(R.id.ophone_insets, Boolean.TRUE);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int adjust = activity.getWindow().getAttributes().softInputMode & android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST;
            boolean pans = adjust == android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
                    || adjust == android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
            int bottom = pans ? 0 : android.os.Build.VERSION.SDK_INT >= 30
                    ? insets.getInsets(android.view.WindowInsets.Type.ime()).bottom
                    : insets.getSystemWindowInsetBottom();
            if (v.getPaddingLeft() != 0 || v.getPaddingTop() != 0 || v.getPaddingRight() != 0 || v.getPaddingBottom() != bottom)
                v.setPadding(0, 0, 0, bottom);
            return android.os.Build.VERSION.SDK_INT >= 30 ? android.view.WindowInsets.CONSUMED : insets.consumeSystemWindowInsets();
        });
        root.requestApplyInsets();
    }

    /**
     * Full screen in portrait and landscape: status and navigation bars are hidden; a swipe from the edge shows them
     * briefly. Window insets still resize the content for the keyboard.
     */
    @SuppressWarnings("deprecation")
    public static void immersive(android.app.Activity activity) {
        try {
            // Window.getInsetsController() throws on Android 11 before the decor exists; the decor's controller does not.
            View decor = activity.getWindow().getDecorView();
            keyboardOnlyInsets(activity, decor);
            if (android.os.Build.VERSION.SDK_INT < 30) decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.view.WindowInsetsController c = decor.getWindowInsetsController();
                if (c == null) return;
                c.hide(android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else {
                decor.setSystemUiVisibility(decor.getSystemUiVisibility() | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
        } catch (RuntimeException ignored) {
            // Full screen is cosmetic: never let it stop a screen from opening.
        }
    }

    public static void edgeColors(android.app.Activity activity, int color) {
        android.view.Window w = activity.getWindow();
        // Any edge the content does not cover shows the window background: keep it the page color.
        w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(color));
        w.setStatusBarColor(color);
        w.setNavigationBarColor(color);
        if (!isDark(activity)) {
            int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (android.os.Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            w.getDecorView().setSystemUiVisibility(flags);
        }
        immersive(activity);
    }

    public static int cardColor(Context context) {
        return Color.parseColor(isDark(context) ? "#232326" : "#FFFFFF");
    }

    public static int primaryTextColor(Context context) {
        return Color.parseColor(isDark(context) ? "#E3E3E6" : "#000000");
    }

    public static int secondaryTextColor(Context context) {
        return Color.parseColor(isDark(context) ? "#8CE3E3E8" : "#993C3C43");
    }

    public static int accentColor(Context context) {
        return Color.parseColor(isDark(context) ? "#0A84FF" : "#007AFF");
    }

    public static int dangerColor(Context context) {
        return Color.parseColor(isDark(context) ? "#FF453A" : "#FF3B30");
    }

    public static LinearLayout page(Context context) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(context, 16), dp(context, 20),
                dp(context, 16), dp(context, 40));
        root.setBackgroundColor(pageColor(context));
        return root;
    }

    public static LinearLayout card(Context context) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 16), dp(context, 14),
                dp(context, 16), dp(context, 14));

        GradientDrawable background = new GradientDrawable();
        background.setColor(cardColor(context));
        background.setCornerRadius(dp(context, 22));
        card.setBackground(background);
        card.setElevation(0f);
        return card;
    }

    public static LinearLayout.LayoutParams cardParams(Context context) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(context, 14));
        return lp;
    }

    public static TextView title(Context context, CharSequence text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(34f);
        view.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));
        view.setLetterSpacing(-0.02f);
        view.setTextColor(primaryTextColor(context));
        view.setGravity(Gravity.START);
        return view;
    }

    public static TextView subtitle(Context context, CharSequence text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(14f);
        view.setTextColor(secondaryTextColor(context));
        view.setPadding(0, dp(context, 6), 0, 0);
        return view;
    }

    public static TextView section(Context context, CharSequence text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(13f);
        view.setAllCaps(true);
        view.setLetterSpacing(0.03f);
        view.setTextColor(secondaryTextColor(context));
        view.setPadding(dp(context, 16), dp(context, 18),
                dp(context, 16), dp(context, 7));
        return view;
    }

    public static TextView body(Context context, CharSequence text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(14f);
        view.setTextColor(secondaryTextColor(context));
        view.setLineSpacing(0f, 1.12f);
        return view;
    }

    public static Button actionButton(Context context, CharSequence text) {
        Button button = new Button(context);
        button.setText(text);
        button.setAllCaps(false);
        button.setMinHeight(dp(context, 48));
        button.setMinWidth(0);
        button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        button.setPadding(dp(context, 14), 0, dp(context, 14), 0);
        button.setTextColor(primaryTextColor(context));
        button.setTextSize(17f);
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, new Chevron(context), null);
        button.setStateListAnimator(null);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Color.TRANSPARENT);
        shape.setCornerRadius(dp(context, 12));
        button.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(isDark(context) ? 0x33FFFFFF : 0x1A000000), shape, shape));
        return button;
    }

    public static View spacer(Context context, int dp) {
        View spacer = new View(context);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(1, dp(context, dp)));
        return spacer;
    }

    /** Destructive or plain action row: centered text, no disclosure chevron. */
    public static Button plainButton(Context context, CharSequence text, boolean destructive) {
        Button b = actionButton(context, text);
        b.setCompoundDrawablesRelative(null, null, null, null);
        b.setGravity(Gravity.CENTER);
        b.setTextColor(destructive ? dangerColor(context) : accentColor(context));
        return b;
    }

    /** iOS-style disclosure chevron for navigation rows. */
    static final class Chevron extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final int size;
        Chevron(Context c) {
            size = dp(c, 14);
            paint.setColor(isDark(c) ? 0x4DEBEBF5 : 0x4D3C3C43);
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(dp(c, 2.2f));
            paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
        }
        @Override public void draw(android.graphics.Canvas canvas) {
            android.graphics.Rect b = getBounds();
            float w = b.width(), h = b.height();
            android.graphics.Path p = new android.graphics.Path();
            p.moveTo(b.left + w * .35f, b.top + h * .15f);
            p.lineTo(b.left + w * .7f, b.top + h * .5f);
            p.lineTo(b.left + w * .35f, b.top + h * .85f);
            canvas.drawPath(p, paint);
        }
        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /** Floating "glass" capsule for native chrome: translucent fill with a bright hairline edge. */
    public static GradientDrawable glass(Context context, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(isDark(context) ? 0xE62C2C30 : 0xEBFFFFFF);
        d.setCornerRadius(dp(context, radiusDp));
        d.setStroke(Math.max(1, dp(context, 0.6f)), isDark(context) ? 0x29FFFFFF : 0x14000000);
        return d;
    }
}
