package ai.ophone.bridge;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Share OPhone AI: the GitHub project page or the always-latest APK link, to an installed app (WeChat, QQ, Weibo,
 * Telegram, WhatsApp, X, Facebook, LINE, KakaoTalk), mail, SMS, the system share sheet, or the clipboard. Where an
 * app is not installed but has a public web share page, that page opens in the built-in browser; otherwise the
 * button is grey.
 */
public final class ShareActivity extends Activity {
    @Override protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(base); UiKit.applyTheme(this); }
    static final String DOWNLOAD_URL = UpdateManager.REPO_URL + "/releases/latest/download/OPhone-AI.apk";
    private RadioGroup what;
    private TextView preview;
    private GridLayout grid;

    /** {label, package, web share URL with %u (link) and %t (text), or ""} */
    private static final String[][] TARGETS = {
            {"WeChat", "com.tencent.mm", ""},
            {"QQ", "com.tencent.mobileqq", ""},
            {"Weibo", "com.sina.weibo", "https://service.weibo.com/share/share.php?url=%u&title=%t"},
            {"Telegram", "org.telegram.messenger", "https://t.me/share/url?url=%u&text=%t"},
            {"WhatsApp", "com.whatsapp", "https://wa.me/?text=%t%20%u"},
            {"X", "com.twitter.android", "https://twitter.com/intent/tweet?url=%u&text=%t"},
            {"Facebook", "com.facebook.katana", "https://www.facebook.com/sharer/sharer.php?u=%u"},
            {"LINE", "jp.naver.line.android", "https://social-plugins.line.me/lineit/share?url=%u"},
            {"KakaoTalk", "com.kakao.talk", ""},
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = UiKit.page(this);
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        page.addView(UiKit.title(this, getString(R.string.share_title)));
        page.addView(UiKit.subtitle(this, getString(R.string.share_intro)));
        page.addView(UiKit.spacer(this, 12));

        page.addView(UiKit.section(this, getString(R.string.share_what)));
        LinearLayout choose = UiKit.card(this);
        what = new RadioGroup(this);
        what.addView(radio(1, R.string.share_project));
        what.addView(radio(2, R.string.share_download));
        what.check(1);
        what.setOnCheckedChangeListener((g, id) -> render());
        choose.addView(what);
        preview = UiKit.body(this, "");
        preview.setTextSize(13.5f);
        preview.setTextIsSelectable(true);
        preview.setPadding(0, UiKit.dp(this, 6), 0, 0);
        choose.addView(preview);
        page.addView(choose, UiKit.cardParams(this));

        page.addView(UiKit.section(this, getString(R.string.share_to)));
        LinearLayout targets = UiKit.card(this);
        grid = new GridLayout(this);
        grid.setColumnCount(3);
        targets.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        page.addView(targets, UiKit.cardParams(this));

        LinearLayout more = UiKit.card(this);
        Button copy = UiKit.actionButton(this, getString(R.string.share_copy));
        copy.setOnClickListener(v -> {
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("OPhone AI", link()));
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
        });
        Button system = UiKit.actionButton(this, getString(R.string.share_system));
        system.setOnClickListener(v -> startActivity(Intent.createChooser(send(null), getString(R.string.share_title))));
        more.addView(copy, new LinearLayout.LayoutParams(-1, -2));
        more.addView(system, new LinearLayout.LayoutParams(-1, -2));
        page.addView(more, UiKit.cardParams(this));
        setContentView(scroll);
        render();
    }

    private RadioButton radio(int id, int text) {
        RadioButton r = new RadioButton(this); r.setId(id); r.setText(text); r.setTextColor(UiKit.primaryTextColor(this)); r.setMinHeight(UiKit.dp(this, 44)); return r;
    }
    private String link() { return what.getCheckedRadioButtonId() == 2 ? DOWNLOAD_URL : projectPage(); }

    /**
     * The project page in the phone's language: the repository's translated README where one exists
     * (README.zh-CN.md, README.ja-JP.md, README.ko-KR.md), otherwise the English front page.
     */
    static String projectPage() {
        String language = java.util.Locale.getDefault().getLanguage(), readme;
        switch (language) {
            case "zh": readme = "README.zh-CN.md"; break;
            case "ja": readme = "README.ja-JP.md"; break;
            case "ko": readme = "README.ko-KR.md"; break;
            default: return UpdateManager.REPO_URL;
        }
        return UpdateManager.REPO_URL + "/blob/main/" + readme;
    }
    private String text() { return getString(what.getCheckedRadioButtonId() == 2 ? R.string.share_text_download : R.string.share_text_project); }
    private String message() { return text() + "\n" + link(); }

    private Intent send(String pkg) {
        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, message()).putExtra(Intent.EXTRA_SUBJECT, "OPhone AI");
        if (pkg != null) i.setPackage(pkg);
        return i;
    }

    private void render() {
        preview.setText(message());
        grid.removeAllViews();
        PackageManager pm = getPackageManager();
        for (String[] t : TARGETS) {
            Intent app = send(t[1]);
            boolean installed = app.resolveActivity(pm) != null;
            String web = t[2].isEmpty() ? "" : t[2].replace("%u", Uri.encode(link())).replace("%t", Uri.encode(text()));
            Runnable go = installed ? () -> start(app) : web.isEmpty() ? null : () -> WebPageActivity.open(this, web);
            grid.addView(tile(localName(t[0]), installed ? "" : web.isEmpty() ? getString(R.string.share_not_installed) : getString(R.string.share_web), go));
        }
        Intent mail = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).putExtra(Intent.EXTRA_SUBJECT, "OPhone AI").putExtra(Intent.EXTRA_TEXT, message());
        grid.addView(tile(getString(R.string.share_email), "", mail.resolveActivity(pm) != null ? () -> start(mail) : null));
        Intent sms = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")).putExtra("sms_body", message());
        grid.addView(tile(getString(R.string.share_sms), "", sms.resolveActivity(pm) != null ? () -> start(sms) : null));
    }

    /** App names as people know them in their language. */
    private static String localName(String name) {
        String lang = UiKit.language();
        if ("zh".equals(lang)) { if ("WeChat".equals(name)) return "微信"; if ("Weibo".equals(name)) return "微博"; }
        if ("ko".equals(lang) && "KakaoTalk".equals(name)) return "카카오톡";
        if ("ja".equals(lang) && "WeChat".equals(name)) return "WeChat";
        return name;
    }

    private void start(Intent i) {
        try { startActivity(i); } catch (Exception e) { Toast.makeText(this, R.string.web_page_open_failed, Toast.LENGTH_SHORT).show(); }
    }

    private LinearLayout tile(String name, String note, Runnable go) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(UiKit.dp(this, 4), UiKit.dp(this, 10), UiKit.dp(this, 4), UiKit.dp(this, 10));
        TextView mark = new TextView(this);
        mark.setText(name.substring(0, 1));
        mark.setGravity(Gravity.CENTER);
        mark.setTextSize(20);
        mark.setTypeface(Typeface.DEFAULT_BOLD);
        mark.setTextColor(0xFFFFFFFF);
        android.graphics.drawable.GradientDrawable dot = new android.graphics.drawable.GradientDrawable();
        dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dot.setColor(UiKit.accentColor(this));
        mark.setBackground(dot);
        tile.addView(mark, new LinearLayout.LayoutParams(UiKit.dp(this, 46), UiKit.dp(this, 46)));
        TextView label = new TextView(this);
        label.setText(name);
        label.setTextSize(13.5f);
        label.setTextColor(UiKit.primaryTextColor(this));
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, UiKit.dp(this, 6), 0, 0);
        tile.addView(label);
        if (!note.isEmpty()) {
            TextView n = new TextView(this);
            n.setText(note); n.setTextSize(11); n.setTextColor(UiKit.secondaryTextColor(this)); n.setGravity(Gravity.CENTER);
            tile.addView(n);
        }
        if (go == null) tile.setAlpha(0.4f);
        else {
            tile.setOnClickListener(v -> go.run());
            android.util.TypedValue tv = new android.util.TypedValue();
            if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) tile.setBackgroundResource(tv.resourceId);
        }
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f));
        lp.width = 0;
        tile.setLayoutParams(lp);
        return tile;
    }
}
