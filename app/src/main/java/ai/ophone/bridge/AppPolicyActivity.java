package ai.ophone.bridge;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class AppPolicyActivity extends Activity {
    @Override protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(base); UiKit.applyTheme(this); }
    private final List<Switch> appRows = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout page = UiKit.page(this);

        page.addView(UiKit.title(this, getString(R.string.app_policy_title)));
        page.addView(UiKit.subtitle(this, getString(R.string.app_policy_note)));
        page.addView(UiKit.spacer(this, 14));

        LinearLayout searchCard = UiKit.card(this);
        EditText search = new EditText(this);
        search.setHint(R.string.app_policy_search);
        search.setSingleLine(true);
        search.setTextColor(UiKit.primaryTextColor(this));
        search.setHintTextColor(UiKit.secondaryTextColor(this));
        searchCard.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(searchCard, UiKit.cardParams(this));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout listCard = UiKit.card(this);
        listCard.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 6),
                UiKit.dp(this, 12), UiKit.dp(this, 6));
        scroll.addView(listCard, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        PackageManager pm = getPackageManager();
        List<PackageInfo> packages = new ArrayList<>(pm.getInstalledPackages(0));
        packages.sort(Comparator
                .comparing((PackageInfo p) -> isSystem(p))
                .thenComparing(p -> {
                    if (p.applicationInfo == null) return p.packageName;
                    return String.valueOf(pm.getApplicationLabel(p.applicationInfo))
                            .toLowerCase(Locale.ROOT);
                }));

        for (PackageInfo pi : packages) {
            if (pi.applicationInfo == null) continue;

            final String pkg = pi.packageName;
            String label = String.valueOf(pm.getApplicationLabel(pi.applicationInfo));
            boolean system = isSystem(pi);

            Switch sw = new Switch(this);
            String suffix = system
                    ? "  · " + getString(R.string.system_app_suffix)
                    : "";
            sw.setText(label + suffix + "\n" + pkg);
            sw.setTextSize(14f);
            sw.setTextColor(UiKit.primaryTextColor(this));
            sw.setTag((label + " " + pkg).toLowerCase(Locale.ROOT));
            sw.setChecked(AppControlPolicy.isAllowed(this, pkg));
            sw.setMinHeight(UiKit.dp(this, 64));
            sw.setPadding(UiKit.dp(this, 4), UiKit.dp(this, 6),
                    UiKit.dp(this, 4), UiKit.dp(this, 6));

            if (getPackageName().equals(pkg)) {
                sw.setChecked(true);
                sw.setEnabled(false);
            } else {
                sw.setOnCheckedChangeListener((button, checked) ->
                        AppControlPolicy.setAllowed(this, pkg, checked));
            }

            appRows.add(sw);
            listCard.addView(sw, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            View divider = new View(this);
            divider.setBackgroundColor(UiKit.isDark(this)
                    ? Color.parseColor("#30343D")
                    : Color.parseColor("#ECEEF3"));
            listCard.addView(divider, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 1)));
        }

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(
                    CharSequence s, int start, int count, int after) {}

            @Override public void onTextChanged(
                    CharSequence s, int start, int before, int count) {
                String query = String.valueOf(s)
                        .trim()
                        .toLowerCase(Locale.ROOT);
                for (Switch row : appRows) {
                    String value = String.valueOf(row.getTag());
                    row.setVisibility(query.isEmpty() || value.contains(query)
                            ? View.VISIBLE : View.GONE);
                }
            }

            @Override public void afterTextChanged(Editable s) {}
        });

        setContentView(page);
    }

    private static boolean isSystem(PackageInfo info) {
        return info.applicationInfo != null
                && (info.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
    }
}
