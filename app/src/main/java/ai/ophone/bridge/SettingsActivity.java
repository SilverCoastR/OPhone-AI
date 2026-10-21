package ai.ophone.bridge;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

public class SettingsActivity extends Activity {
    @Override protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(base); UiKit.applyTheme(this); }
    private static final int REQUEST_NOTIFICATIONS = 1401;

    private LinearLayout root;
    private TextView status;
    private Switch rootSwitch;private TextView rootStatus;private boolean rootUiBusy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        root = UiKit.page(this);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(UiKit.title(this, getString(R.string.settings_title)));
        root.addView(UiKit.subtitle(this, getString(R.string.settings_intro)));
        root.addView(UiKit.spacer(this, 16));

        root.addView(UiKit.section(this, getString(R.string.section_platform)));
        addPlatformCard();

        addActionCard(R.string.schedule_title,
                () -> startActivity(new Intent(this, ScheduleActivity.class)));

        root.addView(UiKit.section(this, getString(R.string.section_appearance)));
        addThemeCard();

        root.addView(UiKit.section(this, getString(R.string.section_core)));
        addActionCard(R.string.model_api_button,
                () -> startActivity(new Intent(this, ModelApiSettingsActivity.class)));
        addSettingCard(
                R.string.automation_title,
                R.string.automation_desc,
                RiskPolicy.KEY_AUTOMATION,
                RiskPolicy.automationEnabled(this),
                false);
        addBudgetCard();
        addSettingCard(
                R.string.downloads_title,
                R.string.downloads_desc,
                RiskPolicy.KEY_DOWNLOADS,
                RiskPolicy.downloadsEnabled(this),
                false);
        addSettingCard(
                R.string.boot_title,
                R.string.boot_desc,
                RiskPolicy.KEY_BOOT,
                RiskPolicy.bootEnabled(this),
                false);
        addSettingCard(
                R.string.pip_title,
                R.string.pip_desc,
                RiskPolicy.KEY_PIP,
                RiskPolicy.pipEnabled(this),
                false);

        root.addView(UiKit.section(this, getString(R.string.section_high_risk)));
        addRootSetting();
        addSettingCard(
                R.string.package_changes_title,
                R.string.package_changes_desc,
                RiskPolicy.KEY_PACKAGE_CHANGES,
                RiskPolicy.packageChangesEnabled(this),
                true);

        root.addView(UiKit.section(this, getString(R.string.section_system)));
        addSettingCard(
                R.string.overlay_title,
                R.string.overlay_desc,
                RiskPolicy.KEY_OVERLAY,
                RiskPolicy.overlayEnabled(this),
                false);

        addActionCard(R.string.app_policy_button,
                () -> startActivity(new Intent(this, AppPolicyActivity.class)));
        addActionCard(R.string.accessibility_button,
                () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        addActionCard(R.string.unknown_sources_button, this::openUnknownSources);
        addActionCard(R.string.notification_button, this::openNotificationSettings);
        addActionCard(R.string.battery_button, () -> {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        });

        root.addView(UiKit.section(this, getString(R.string.about_section_app)));
        addActionCard(R.string.about_title,
                () -> startActivity(new Intent(this, AboutActivity.class)));
        addActionCard(R.string.feedback_title,
                () -> startActivity(new Intent(this, FeedbackActivity.class)));

        root.addView(UiKit.section(this, getString(R.string.section_transparency)));
        addActionCard(R.string.audit_button,
                () -> startActivity(new Intent(this, AuditActivity.class)));
        addActionCard(R.string.memory_button,
                () -> startActivity(new Intent(this, MemoryActivity.class)));

        LinearLayout statusCard = UiKit.card(this);
        status = UiKit.body(this, "");
        status.setTextSize(13f);
        statusCard.addView(status);
        root.addView(statusCard, UiKit.cardParams(this));

        Button chat = UiKit.actionButton(this, getString(R.string.return_chat_button));
        chat.setOnClickListener(v -> finish());
        root.addView(chat, UiKit.cardParams(this));

        setContentView(scroll);
    }

    private void addPlatformCard(){
        LinearLayout card=UiKit.card(this);TextView selected=UiKit.body(this,"");String current=PlatformPreference.get(this);
        selected.setText(getString(R.string.selected_platform,current.isEmpty()?getString(R.string.platform_none):providerLabel(current)));card.addView(selected);
        for(ModelProvider p:ModelProvider.ALL){if(p.platform.equals(current))continue;Button api=UiKit.actionButton(this,getString(R.string.switch_to_platform,p.label+" API"));api.setOnClickListener(v->switchPlatform(p.platform));card.addView(api);}
        Button chatgpt=UiKit.actionButton(this,getString(R.string.switch_chatgpt));chatgpt.setOnClickListener(v->switchPlatform(PlatformSession.CHATGPT));card.addView(chatgpt);
        Button choose=UiKit.actionButton(this,getString(R.string.choose_platform));choose.setOnClickListener(v->{PlatformSession.leave(this,PlatformPreference.get(this));PlatformPreference.clear(this);startActivity(new Intent(this,PlatformActivity.class).putExtra(PlatformPreference.EXTRA_SHOW_CHOOSER,true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));finish();});card.addView(choose);root.addView(card,UiKit.cardParams(this));
    }
    private String providerLabel(String provider){ModelProvider p=ModelProvider.forPlatform(provider);return p==null?getString(R.string.platform_chatgpt):p.label+" API";}
    private void switchPlatform(String provider){PlatformSession.switchTo(this,provider);startActivity(new Intent(this,PlatformPreference.target(this,provider).activity).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));finish();}

    private void addRootSetting() {
        LinearLayout card=UiKit.card(this);rootSwitch=new Switch(this);rootSwitch.setId(0x534a0037);rootSwitch.setText(R.string.root_shell_title);rootSwitch.setTextSize(16);rootSwitch.setTextColor(UiKit.primaryTextColor(this));rootSwitch.setChecked(RiskPolicy.rootShellEnabled(this));rootSwitch.setMinHeight(UiKit.dp(this,48));card.addView(rootSwitch);
        card.addView(UiKit.body(this,getString(R.string.root_shell_desc)));rootStatus=UiKit.body(this,"");rootStatus.setTextSize(13);card.addView(rootStatus);
        Button check=UiKit.actionButton(this,getString(R.string.root_check));check.setOnClickListener(v->checkRoot());card.addView(check);root.addView(card,UiKit.cardParams(this));
        rootSwitch.setOnCheckedChangeListener((button,checked)->{if(rootUiBusy)return;
            if(checked){setRootSwitch(false);new AlertDialog.Builder(this).setTitle(R.string.root_risk_title).setMessage(R.string.root_risk_message).setNegativeButton(R.string.cancel,null).setPositiveButton(R.string.root_enable,(dialog,which)->{try{RootAccess.setEnabled(this,true);setRootSwitch(true);AuditLog.record(this,"root_policy",getPackageName(),true,"enabled_by_settings_user");checkRoot();}catch(Exception e){android.widget.Toast.makeText(this,e.getMessage(),android.widget.Toast.LENGTH_LONG).show();}}).show();}
            else try{RootAccess.setEnabled(this,false);AuditLog.record(this,"root_policy",getPackageName(),true,"disabled_by_settings_user");updateRootStatus();}catch(Exception e){setRootSwitch(RiskPolicy.rootShellEnabled(this));android.widget.Toast.makeText(this,e.getMessage(),android.widget.Toast.LENGTH_LONG).show();}
        });updateRootStatus();
    }
    private void setRootSwitch(boolean checked){rootUiBusy=true;rootSwitch.setChecked(checked);rootUiBusy=false;}
    private void updateRootStatus(){if(rootStatus==null)return;try{org.json.JSONObject value=RootAccess.status(this);String state=value.optString("state");int label="disabled".equals(state)?R.string.root_state_disabled:"su_not_found".equals(state)?R.string.root_state_missing:"granted".equals(state)?R.string.root_state_granted:"not_checked".equals(state)?R.string.root_state_unchecked:R.string.root_state_denied;rootStatus.setText(getString(label));}catch(Exception e){rootStatus.setText(e.getMessage());}}
    private void checkRoot(){if(!RiskPolicy.rootShellEnabled(this)){updateRootStatus();android.widget.Toast.makeText(this,R.string.root_enable_first,android.widget.Toast.LENGTH_LONG).show();return;}rootStatus.setText(R.string.root_state_checking);new Thread(()->{try{org.json.JSONObject value=RootAccess.probe(this);AuditLog.record(this,"root_probe",getPackageName(),value.optBoolean("available"),value.optString("state"));}catch(Exception ignored){}runOnUiThread(()->{if(!isFinishing()&&!isDestroyed())updateRootStatus();});},"OPhoneAI-root-check").start();}

    private void addSettingCard(int titleRes, int descRes, String key,
                                boolean initialValue, boolean highRisk) {
        LinearLayout card = UiKit.card(this);

        Switch sw = new Switch(this);
        sw.setText(getString(titleRes));
        sw.setTextSize(16f);
        sw.setTextColor(UiKit.primaryTextColor(this));
        sw.setChecked(initialValue);
        sw.setMinHeight(UiKit.dp(this, 48));
        card.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView desc = UiKit.body(this, getString(descRes));
        desc.setPadding(0, UiKit.dp(this, 2), 0, 0);
        card.addView(desc);

        sw.setOnCheckedChangeListener((button, checked) -> {
            if (highRisk
                    && checked
                    && !RiskPolicy.packageChangesEnabled(this)) {
                button.setChecked(false);
                new AlertDialog.Builder(this)
                        .setTitle(R.string.risk_dialog_title)
                        .setMessage(R.string.risk_dialog_message)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.enable_risk, (dialog, which) -> {
                            RiskPolicy.set(this, key, true);
                            button.setChecked(true);
                            refreshRuntimeFeatures();
                            updateStatus();
                        })
                        .show();
                return;
            }

            RiskPolicy.set(this, key, checked);
            if (RiskPolicy.KEY_OVERLAY.equals(key) && checked) {
                ensureOverlayPermission();
            }
            refreshRuntimeFeatures();
            updateStatus();
        });

        root.addView(card, UiKit.cardParams(this));
    }

    /** Night mode: follow the system (default), always light or always dark; open screens are rebuilt in the new mode. */
    private void addThemeCard() {
        LinearLayout card = UiKit.card(this);
        String[] modes = {UiKit.THEME_SYSTEM, UiKit.THEME_LIGHT, UiKit.THEME_DARK};
        int[] labels = {R.string.theme_system, R.string.theme_light, R.string.theme_dark};
        int current = java.util.Arrays.asList(modes).indexOf(UiKit.themeMode(this));
        Button button = UiKit.actionButton(this, getString(R.string.theme_title) + "\n" + getString(labels[Math.max(0, current)]));
        button.setOnClickListener(v -> {
            String[] items = new String[labels.length];
            for (int i = 0; i < labels.length; i++) items[i] = getString(labels[i]);
            new android.app.AlertDialog.Builder(this).setTitle(R.string.theme_title)
                    .setSingleChoiceItems(items, Math.max(0, java.util.Arrays.asList(modes).indexOf(UiKit.themeMode(this))), (dialog, which) -> {
                        dialog.dismiss();
                        if (modes[which].equals(UiKit.themeMode(this))) return;
                        UiKit.setThemeMode(this, modes[which]);
                        recreate();
                    }).show();
        });
        card.addView(button, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView desc = UiKit.body(this, getString(R.string.theme_desc));
        desc.setTextSize(13f);
        desc.setPadding(UiKit.dp(this, 14), 0, UiKit.dp(this, 14), UiKit.dp(this, 6));
        card.addView(desc);
        root.addView(card, UiKit.cardParams(this));
    }

    private void addBudgetCard() {
        LinearLayout card = UiKit.card(this);
        int[] labels = {R.string.task_budget_level_0, R.string.task_budget_level_1, R.string.task_budget_level_2};
        Button button = UiKit.actionButton(this, "");
        Runnable refresh = () -> button.setText(getString(R.string.task_budget_title) + "\n" + getString(labels[RiskPolicy.budgetLevel(this)]));
        refresh.run();
        button.setOnClickListener(v -> {
            String[] items = new String[labels.length];
            for (int i = 0; i < labels.length; i++) items[i] = getString(labels[i]);
            new android.app.AlertDialog.Builder(this).setTitle(R.string.task_budget_title)
                    .setSingleChoiceItems(items, RiskPolicy.budgetLevel(this), (dialog, which) -> {
                        RiskPolicy.setBudgetLevel(this, which); refresh.run(); dialog.dismiss();
                    }).show();
        });
        card.addView(button, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView desc = UiKit.body(this, getString(R.string.task_budget_desc));
        desc.setTextSize(13f);
        desc.setPadding(UiKit.dp(this, 14), 0, UiKit.dp(this, 14), UiKit.dp(this, 6));
        card.addView(desc);
        root.addView(card, UiKit.cardParams(this));
    }

    private void addActionCard(int titleRes, Runnable action) {
        LinearLayout card = UiKit.card(this);
        Button button = UiKit.actionButton(this, getString(titleRes));
        button.setOnClickListener(v -> action.run());
        card.addView(button, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(card, UiKit.cardParams(this));
    }

    private void ensureOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(this)) {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        }
    }

    private void openUnknownSources() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
        } else {
            startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
        }
    }

    private void openNotificationSettings() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
            return;
        }

        try {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(i);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void refreshRuntimeFeatures() {
        if (RiskPolicy.overlayEnabled(this)
                && (Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || Settings.canDrawOverlays(this))) {
            startService(new Intent(this, OverlayService.class));
        } else {
            stopService(new Intent(this, OverlayService.class));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshRuntimeFeatures();
        if(rootSwitch!=null)setRootSwitch(RiskPolicy.rootShellEnabled(this));updateRootStatus();
        updateStatus();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        updateStatus();
    }

    private void updateStatus() {
        if (status == null) return;

        boolean a11y = DeviceAutomationService.getInstance() != null;
        boolean overlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || Settings.canDrawOverlays(this);
        boolean installs = Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || getPackageManager().canRequestPackageInstalls();
        boolean notifications = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;

        status.setText(
                getString(R.string.status_accessibility) + ": "
                        + getString(a11y
                        ? R.string.status_connected : R.string.status_not_connected)
                        + "\n"
                        + getString(R.string.status_notifications) + ": "
                        + getString(notifications
                        ? R.string.status_allowed : R.string.status_not_allowed)
                        + "\n"
                        + getString(R.string.status_overlay) + ": "
                        + getString(overlay
                        ? R.string.status_allowed : R.string.status_not_allowed)
                        + "\n"
                        + getString(R.string.status_unknown_sources) + ": "
                        + getString(installs
                        ? R.string.status_allowed : R.string.status_not_allowed)
                        + "\n"
                        + getString(R.string.status_package_changes) + ": "
                        + getString(RiskPolicy.packageChangesEnabled(this)
                        ? R.string.status_enabled : R.string.status_disabled)
                        + "\n"
                        + getString(R.string.status_local_server) + ": "
                        + LocalServerConfig.bind(this));
    }
}
