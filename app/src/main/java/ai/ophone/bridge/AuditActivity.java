package ai.ophone.bridge;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class AuditActivity extends Activity {
    @Override protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(base); UiKit.applyTheme(this); }
    private TextView log;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout page = UiKit.page(this);

        page.addView(UiKit.title(this, getString(R.string.audit_title)));
        page.addView(UiKit.subtitle(this, getString(R.string.audit_note)));
        page.addView(UiKit.spacer(this, 14));

        LinearLayout controls = UiKit.card(this);
        Button clear = UiKit.plainButton(this, getString(R.string.audit_clear), true);
        clear.setOnClickListener(v -> {
            AuditLog.clear(this);
            refresh();
        });
        controls.addView(clear, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(controls, UiKit.cardParams(this));

        LinearLayout logCard = UiKit.card(this);
        ScrollView scroll = new ScrollView(this);
        log = new TextView(this);
        log.setTextSize(12f);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextColor(UiKit.primaryTextColor(this));
        log.setTextIsSelectable(true);
        log.setLineSpacing(0f, 1.12f);
        scroll.addView(log, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        logCard.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        page.addView(logCard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(page);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        if (log != null) log.setText(AuditLog.readRecent(this, 200));
    }
}
