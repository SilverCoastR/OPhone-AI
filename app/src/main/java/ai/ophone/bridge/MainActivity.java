package ai.ophone.bridge;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(base); UiKit.applyTheme(this); }
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = UiKit.page(this);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(UiKit.title(this, getString(R.string.dashboard_title)));
        TextView subtitle = UiKit.subtitle(this,
                getString(R.string.dashboard_subtitle)
                        + " · v" + BuildConfig.VERSION_NAME);
        root.addView(subtitle);
        root.addView(UiKit.spacer(this, 18));

        LinearLayout statusCard = UiKit.card(this);
        status = UiKit.body(this, "");
        status.setTextSize(15f);
        statusCard.addView(status);
        root.addView(statusCard, UiKit.cardParams(this));

        addAction(root, R.string.open_chatgpt, () ->
        { PlatformSession.switchTo(this, PlatformSession.CHATGPT);
                startActivity(new Intent(this, ChatActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)); });

        addAction(root, R.string.open_deepseek, () ->
        { PlatformSession.switchTo(this, ModelProvider.isApiPlatform(PlatformPreference.get(this)) ? PlatformPreference.get(this) : PlatformSession.DEEPSEEK);
                startActivity(new Intent(this, ApiChatActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)); });

        addAction(root, R.string.open_settings, () ->
                startActivity(new Intent(this, SettingsActivity.class)));

        addAction(root, R.string.open_accessibility, () ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        addAction(root, R.string.start_service, () -> {
            startBridgeService();
            updateStatus();
        });

        setContentView(scroll);
        startBridgeService();
    }

    private void addAction(LinearLayout root, int textRes, Runnable action) {
        LinearLayout card = UiKit.card(this);
        Button button = UiKit.actionButton(this, getString(textRes));
        button.setOnClickListener(v -> action.run());
        card.addView(button, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(card, UiKit.cardParams(this));
    }

    private void startBridgeService() {
        Intent service = new Intent(this, BridgeService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(service);
        } else {
            startService(service);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void updateStatus() {
        if (status == null) return;

        boolean a11y = DeviceAutomationService.getInstance() != null;
        status.setText(
                getString(R.string.dashboard_service) + ": "
                        + LocalServerConfig.bind(this)
                        + "\n"
                        + getString(R.string.dashboard_accessibility) + ": "
                        + getString(a11y
                        ? R.string.status_connected : R.string.status_not_connected)
                        + "\n"
                        + getString(R.string.dashboard_background) + ": "
                        + getString(R.string.dashboard_notification)
                        + "\n"
                        + getString(R.string.dashboard_risk) + ": "
                        + getString(RiskPolicy.packageChangesEnabled(this)
                        ? R.string.status_enabled : R.string.status_disabled)
                        + "\nDeepSeek API: " + getString(new ModelApiStore(this).hasKey()
                        ? R.string.model_api_key_saved : R.string.model_api_key_required));
    }
}
