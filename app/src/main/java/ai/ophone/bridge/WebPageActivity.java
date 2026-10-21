package ai.ophone.bridge;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * OPhone AI's own page viewer for links the app opens (the GitHub project, releases, issues, links in chat
 * replies), so the user never leaves for another browser. No app bridge is exposed to the page. Downloads go to
 * the system download manager (the update APK through the verified in-app updater).
 */
public final class WebPageActivity extends Activity {
    @Override protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(base); UiKit.applyTheme(this); }
    private WebView web;private TextView title,host;private ProgressBar progress;

    /**
     * Opens an http(s) link inside OPhone AI: a new tab of the built-in browser (Android 9+, its own WebView
     * profile), or this simple viewer on older Android. Other schemes (mailto:, tel:, market:) go to the app that
     * handles them.
     */
    public static void open(Context context,String url){
        if(url==null||url.trim().isEmpty())return;url=url.trim();
        Intent i;
        if((url.startsWith("https://")||url.startsWith("http://"))&&android.os.Build.VERSION.SDK_INT>=28)
            i=new Intent().setComponent(new android.content.ComponentName(context.getPackageName(),WorkbenchClient.BROWSER)).putExtra("open_url",url);
        else if(url.startsWith("https://")||url.startsWith("http://"))i=new Intent(context,WebPageActivity.class).putExtra("url",url);
        else i=new Intent(Intent.ACTION_VIEW,Uri.parse(url));
        if(!(context instanceof Activity))i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try{context.startActivity(i);}catch(Exception e){Toast.makeText(context,R.string.web_page_open_failed,Toast.LENGTH_SHORT).show();}
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle state){super.onCreate(state);
        int fg=UiKit.primaryTextColor(this),muted=UiKit.secondaryTextColor(this),surface=UiKit.cardColor(this);
        getWindow().setStatusBarColor(surface);getWindow().setNavigationBarColor(surface);
        if(!UiKit.isDark(this))getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|(android.os.Build.VERSION.SDK_INT>=26?View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR:0));
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(surface);

        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(UiKit.dp(this,4),0,UiKit.dp(this,4),0);
        bar.addView(glyph("✕",fg,v->finish(),R.string.browser_done),new LinearLayout.LayoutParams(UiKit.dp(this,48),UiKit.dp(this,48)));
        LinearLayout middle=new LinearLayout(this);middle.setOrientation(LinearLayout.VERTICAL);middle.setGravity(Gravity.CENTER);
        title=new TextView(this);title.setTextSize(15);title.setTypeface(Typeface.DEFAULT_BOLD);title.setTextColor(fg);title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.END);title.setGravity(Gravity.CENTER);
        host=new TextView(this);host.setTextSize(12);host.setTextColor(muted);host.setSingleLine(true);host.setEllipsize(TextUtils.TruncateAt.MIDDLE);host.setGravity(Gravity.CENTER);
        middle.addView(title,new LinearLayout.LayoutParams(-1,-2));middle.addView(host,new LinearLayout.LayoutParams(-1,-2));
        bar.addView(middle,new LinearLayout.LayoutParams(0,-2,1));
        bar.addView(glyph("⋯",fg,this::menu,R.string.web_page_more),new LinearLayout.LayoutParams(UiKit.dp(this,48),UiKit.dp(this,48)));
        root.addView(bar,new LinearLayout.LayoutParams(-1,UiKit.dp(this,54)));

        FrameLayout body=new FrameLayout(this);
        web=new WebView(this);body.addView(web,new FrameLayout.LayoutParams(-1,-1));
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);
        body.addView(progress,new FrameLayout.LayoutParams(-1,UiKit.dp(this,3),Gravity.TOP));
        root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);

        WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setLoadWithOverviewMode(true);s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);s.setDisplayZoomControls(false);s.setAllowFileAccess(false);s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){String u=r.getUrl().toString();
                if(u.startsWith("https://")||u.startsWith("http://"))return false;
                try{startActivity(new Intent(Intent.ACTION_VIEW,r.getUrl()));}catch(Exception ignored){}return true;}
            @Override public void onPageStarted(WebView v,String u,Bitmap icon){progress.setVisibility(View.VISIBLE);show(u,v.getTitle());}
            @Override public void onPageFinished(WebView v,String u){progress.setVisibility(View.GONE);show(u,v.getTitle());}
        });
        web.setWebChromeClient(new WebChromeClient(){
            @Override public void onProgressChanged(WebView v,int p){progress.setProgress(p);progress.setVisibility(p>=100?View.GONE:View.VISIBLE);}
            @Override public void onReceivedTitle(WebView v,String t){show(v.getUrl(),t);}
        });
        web.setDownloadListener((url,agent,disposition,mime,length)->download(url,disposition,mime));
        String url=getIntent().getStringExtra("url");
        if(state!=null)web.restoreState(state);else if(url!=null)web.loadUrl(url);
        show(url,"");
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);String url=intent.getStringExtra("url");if(url!=null)web.loadUrl(url);}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);web.saveState(out);}
    @Override protected void onResume(){super.onResume();web.onResume();}
    @Override protected void onPause(){web.onPause();super.onPause();}
    @Override protected void onDestroy(){web.destroy();super.onDestroy();}
    @Override public boolean onKeyDown(int code,KeyEvent e){if(code==KeyEvent.KEYCODE_BACK&&web.canGoBack()){web.goBack();return true;}return super.onKeyDown(code,e);}

    private void show(String url,String pageTitle){Uri u=url==null?null:Uri.parse(url);String h=u==null||u.getHost()==null?"":u.getHost().replaceFirst("^www\\.","");
        title.setText(pageTitle==null||pageTitle.isEmpty()||pageTitle.equals(url)?h:pageTitle);host.setText(h);}

    private TextView glyph(String text,int color,View.OnClickListener click,int description){TextView b=new TextView(this);b.setText(text);b.setTextSize(20);b.setTextColor(color);b.setGravity(Gravity.CENTER);
        b.setContentDescription(getString(description));b.setOnClickListener(click);
        android.util.TypedValue tv=new android.util.TypedValue();if(getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless,tv,true))b.setBackgroundResource(tv.resourceId);return b;}

    private void menu(View anchor){PopupMenu m=new PopupMenu(this,anchor);
        m.getMenu().add(0,1,0,R.string.browser_reload);if(web.canGoForward())m.getMenu().add(0,2,1,R.string.browser_forward);m.getMenu().add(0,3,2,R.string.browser_copy_link);
        m.setOnMenuItemClickListener(item->{switch(item.getItemId()){
            case 1:web.reload();break;case 2:web.goForward();break;
            case 3:((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("url",String.valueOf(web.getUrl())));Toast.makeText(this,R.string.copied,Toast.LENGTH_SHORT).show();break;
            default:break;}return true;});
        m.show();}

    /** Release APKs of this app go through the verified in-app updater; anything else to the system download manager. */
    private void download(String url,String disposition,String mime){
        String name=URLUtil.guessFileName(url,disposition,mime);
        if(name.toLowerCase(java.util.Locale.ROOT).endsWith(".apk")&&url.startsWith("https://github.com/"+UpdateManager.REPO+"/")){startActivity(new Intent(this,AboutActivity.class));return;}
        try{DownloadManager.Request r=new DownloadManager.Request(Uri.parse(url)).setMimeType(mime).setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED).setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,name);
            ((DownloadManager)getSystemService(DOWNLOAD_SERVICE)).enqueue(r);Toast.makeText(this,getString(R.string.web_page_downloading,name),Toast.LENGTH_SHORT).show();}
        catch(Exception e){Toast.makeText(this,R.string.web_page_open_failed,Toast.LENGTH_SHORT).show();}
    }
}
