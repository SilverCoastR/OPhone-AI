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

    public static boolean isDark(Context context) {
        int mode = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    public static int pageColor(Context context) {
        return Color.parseColor(isDark(context) ? "#111318" : "#F6F7FB");
    }

    public static int cardColor(Context context) {
        return Color.parseColor(isDark(context) ? "#1B1E24" : "#FFFFFF");
    }

    public static int primaryTextColor(Context context) {
        return Color.parseColor(isDark(context) ? "#F2F4F8" : "#16181D");
    }

    public static int secondaryTextColor(Context context) {
        return Color.parseColor(isDark(context) ? "#AEB5C0" : "#626975");
    }

    public static int accentColor(Context context) {
        return Color.parseColor(isDark(context) ? "#91A0FF" : "#4F67FF");
    }

    public static int dangerColor(Context context) {
        return Color.parseColor(isDark(context) ? "#FF9A9A" : "#B3261E");
    }

    public static LinearLayout page(Context context) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(context, 20), dp(context, 24),
                dp(context, 20), dp(context, 32));
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
        background.setCornerRadius(dp(context, 18));
        card.setBackground(background);
        card.setElevation(dp(context, 1.5f));
        return card;
    }

    public static LinearLayout.LayoutParams cardParams(Context context) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(context, 12));
        return lp;
    }

    public static TextView title(Context context, CharSequence text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(28f);
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
        view.setAllCaps(false);
        view.setTextColor(accentColor(context));
        view.setPadding(dp(context, 4), dp(context, 10),
                dp(context, 4), dp(context, 8));
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
        return button;
    }

    public static View spacer(Context context, int dp) {
        View spacer = new View(context);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(1, dp(context, dp)));
        return spacer;
    }
}
