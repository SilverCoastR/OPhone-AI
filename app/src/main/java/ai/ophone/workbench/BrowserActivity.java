package ai.ophone.workbench;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.*;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.widget.*;
import ai.ophone.bridge.R;
import ai.ophone.bridge.UiKit;
import org.json.JSONObject;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;

/**
 * Safari-style chrome: the page fills the screen and a floating glass bar (back, address capsule, tabs, more)
 * sits at the bottom, sliding away while reading. No model protocol or chat session is injected into pages.
 */
public final class BrowserActivity extends Activity {
    @Override protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(base); UiKit.applyTheme(this); }
    static final String SEARCH="https://www.bing.com/search?q=";
    private static final int FILE_REQUEST=4101;
    private static final PathInterpolator SPRING=new PathInterpolator(.32f,.72f,0f,1f);
    BrowserManager browser;
    private FrameLayout root,content,overlay;private LinearLayout bar,editBar,startView,errorView;private TextView hostText,errorText,aiChip;private EditText address;private View progress;
    private Icon lock,reloadIcon,tabsIcon;private boolean barHidden;private ValueCallback<Uri[]> fileCallback;private final Map<String,Bitmap> thumbs=new HashMap<>();
    private int fg,muted,accent,surface;

    @Override public void onCreate(Bundle saved){super.onCreate(saved);browser=BrowserManager.get(this);browser.activity=this;
        fg=UiKit.primaryTextColor(this);muted=UiKit.secondaryTextColor(this);accent=UiKit.accentColor(this);surface=UiKit.cardColor(this);
        getWindow().setStatusBarColor(surface);getWindow().setNavigationBarColor(surface);
        if(!UiKit.isDark(this))getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|(android.os.Build.VERSION.SDK_INT>=26?View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR:0));
        root=new FrameLayout(this);root.setBackgroundColor(surface);
        content=new FrameLayout(this);root.addView(content,new FrameLayout.LayoutParams(-1,-1));
        progress=new View(this);progress.setBackgroundColor(accent);progress.setPivotX(0);root.addView(progress,new FrameLayout.LayoutParams(-1,dp(2.5f),Gravity.TOP));

        aiChip=new TextView(this);aiChip.setText(R.string.browser_ai_active);aiChip.setTextSize(13);aiChip.setTextColor(fg);aiChip.setTypeface(Typeface.DEFAULT_BOLD);aiChip.setPadding(dp(14),dp(7),dp(14),dp(7));aiChip.setBackground(UiKit.glass(this,20));
        aiChip.setCompoundDrawablePadding(dp(6));aiChip.setCompoundDrawablesRelativeWithIntrinsicBounds(new Dot(accent,dp(8)),null,null,null);aiChip.setVisibility(View.GONE);
        FrameLayout.LayoutParams chip=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);chip.topMargin=dp(10);root.addView(aiChip,chip);

        startView=message(R.string.browser_start_title,R.string.browser_start_hint,0,null);
        errorView=message(R.string.browser_error_title,0,R.string.browser_retry,this::retry);errorText=(TextView)errorView.getChildAt(1);

        // Floating bottom bar.
        bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(circle(Icon.BACK,()->nav(false)),new LinearLayout.LayoutParams(dp(50),dp(50)));
        LinearLayout pill=new LinearLayout(this);pill.setGravity(Gravity.CENTER);pill.setPadding(dp(14),0,dp(4),0);pill.setBackground(ripple(UiKit.glass(this,25)));pill.setElevation(dp(6));pill.setOutlineProvider(pillOutline());
        lock=new Icon(this,Icon.LOCK,muted);pill.addView(lock,new LinearLayout.LayoutParams(dp(14),dp(14)));
        hostText=new TextView(this);hostText.setTextSize(16);hostText.setTextColor(fg);hostText.setSingleLine(true);hostText.setEllipsize(TextUtils.TruncateAt.MIDDLE);hostText.setPadding(dp(6),0,dp(6),0);hostText.setHint(R.string.browser_address_hint);hostText.setHintTextColor(muted);
        pill.addView(hostText,new LinearLayout.LayoutParams(0,-2,1));
        reloadIcon=new Icon(this,Icon.RELOAD,fg);pill.addView(tap(reloadIcon,this::reloadOrStop,20),new LinearLayout.LayoutParams(dp(40),dp(40)));
        pill.setOnClickListener(v->startEditing());
        LinearLayout.LayoutParams pillLp=new LinearLayout.LayoutParams(0,dp(50),1);pillLp.setMargins(dp(8),0,dp(8),0);bar.addView(pill,pillLp);
        View tabs=circle(Icon.TABS,this::showTabs);tabsIcon=(Icon)((ViewGroup)tabs).getChildAt(0);bar.addView(tabs,new LinearLayout.LayoutParams(dp(50),dp(50)));
        LinearLayout.LayoutParams gap=new LinearLayout.LayoutParams(dp(50),dp(50));gap.setMarginStart(dp(8));bar.addView(circle(Icon.MORE,this::showMenu),gap);
        FrameLayout.LayoutParams barLp=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);barLp.setMargins(dp(12),0,dp(12),dp(12));bar.setPadding(0,dp(6),0,dp(6));root.addView(bar,barLp);

        // Address editing capsule, shown above the keyboard.
        editBar=new LinearLayout(this);editBar.setGravity(Gravity.CENTER_VERTICAL);editBar.setVisibility(View.GONE);
        LinearLayout field=new LinearLayout(this);field.setGravity(Gravity.CENTER_VERTICAL);field.setPadding(dp(16),0,dp(8),0);field.setBackground(UiKit.glass(this,25));field.setElevation(dp(6));field.setOutlineProvider(pillOutline());
        field.addView(new Icon(this,Icon.SEARCH,muted),new LinearLayout.LayoutParams(dp(18),dp(18)));
        address=new EditText(this);address.setSingleLine(true);address.setBackground(null);address.setTextSize(17);address.setTextColor(fg);address.setHintTextColor(muted);address.setHint(R.string.browser_address_hint);
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);address.setImeOptions(EditorInfo.IME_ACTION_GO);address.setSelectAllOnFocus(true);address.setPadding(dp(8),dp(12),dp(4),dp(12));
        address.setOnEditorActionListener((v,id,e)->{go(address.getText().toString());return true;});
        field.addView(address,new LinearLayout.LayoutParams(0,-2,1));
        editBar.addView(field,new LinearLayout.LayoutParams(0,dp(50),1));
        TextView cancel=new TextView(this);cancel.setText(R.string.cancel);cancel.setTextColor(accent);cancel.setTextSize(17);cancel.setPadding(dp(14),dp(10),dp(6),dp(10));cancel.setOnClickListener(v->stopEditing());editBar.addView(cancel);
        FrameLayout.LayoutParams editLp=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);editLp.setMargins(dp(12),0,dp(12),dp(12));root.addView(editBar,editLp);

        overlay=new FrameLayout(this);overlay.setVisibility(View.GONE);root.addView(overlay,new FrameLayout.LayoutParams(-1,-1));
        setContentView(root);openRequested(getIntent());refresh();
    }

    private int dp(float v){return UiKit.dp(this,v);}
    private static GradientDrawable round(int color,float radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(radius);return d;}
    private RippleDrawable ripple(android.graphics.drawable.Drawable base){return new RippleDrawable(ColorStateList.valueOf(UiKit.isDark(this)?0x33FFFFFF:0x1A000000),base,null);}
    private ViewOutlineProvider pillOutline(){return new ViewOutlineProvider(){@Override public void getOutline(View v,Outline o){o.setRoundRect(0,0,v.getWidth(),v.getHeight(),v.getHeight()/2f);o.setAlpha(.18f);}};}
    /** A glass circle button with a drawn icon. */
    private View circle(int kind,Runnable action){FrameLayout box=new FrameLayout(this);box.setBackground(ripple(UiKit.glass(this,25)));box.setElevation(dp(6));box.setOutlineProvider(pillOutline());
        box.addView(new Icon(this,kind,fg),new FrameLayout.LayoutParams(dp(22),dp(22),Gravity.CENTER));box.setOnClickListener(v->{press(v);action.run();});return box;}
    private View tap(View icon,Runnable action,int sizeDp){FrameLayout box=new FrameLayout(this);box.setBackground(new RippleDrawable(ColorStateList.valueOf(UiKit.isDark(this)?0x33FFFFFF:0x1A000000),null,round(0xFFFFFFFF,dp(24))));
        box.addView(icon,new FrameLayout.LayoutParams(dp(sizeDp),dp(sizeDp),Gravity.CENTER));box.setOnClickListener(v->action.run());return box;}
    private void press(View v){v.animate().scaleX(.9f).scaleY(.9f).setDuration(90).withEndAction(()->v.animate().scaleX(1).scaleY(1).setInterpolator(SPRING).setDuration(380).start()).start();}
    private LinearLayout message(int title,int body,int button,Runnable action){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);v.setGravity(Gravity.CENTER_HORIZONTAL);v.setPadding(dp(28),dp(96),dp(28),dp(28));v.setBackgroundColor(surface);
        TextView t=new TextView(this);t.setText(title);t.setTextSize(30);t.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));t.setLetterSpacing(-0.02f);t.setTextColor(fg);t.setGravity(Gravity.CENTER);v.addView(t);
        TextView b=UiKit.body(this,body==0?"":getString(body));b.setTextSize(15);b.setGravity(Gravity.CENTER);b.setPadding(0,dp(10),0,dp(20));v.addView(b);
        if(button!=0){TextView btn=new TextView(this);btn.setText(button);btn.setTextColor(0xFFFFFFFF);btn.setTextSize(16);btn.setTypeface(Typeface.DEFAULT_BOLD);btn.setPadding(dp(26),dp(12),dp(26),dp(12));btn.setBackground(ripple(round(accent,dp(24))));btn.setOnClickListener(x->action.run());v.addView(btn);}
        return v;}

    void toast(String text){Toast.makeText(this,text,Toast.LENGTH_SHORT).show();}
    private BrowserManager.Tab current(){try{return browser.selected.isEmpty()?null:browser.tab("");}catch(Exception e){return null;}}
    /** Typed text becomes a URL when it looks like one, otherwise a web search. */
    static String resolve(String raw){String s=raw.trim();if(s.isEmpty())return "";if(s.matches("(?i)https?://\\S+"))return s;
        if(!s.contains(" ")&&s.matches("(?i)[\\w-]+(\\.[\\w-]+)+(:\\d+)?(/\\S*)?"))return "https://"+s;
        try{return SEARCH+URLEncoder.encode(s,"UTF-8");}catch(Exception e){return SEARCH+s;}}
    void go(String raw){String u=resolve(raw);if(u.isEmpty())return;try{BrowserManager.url(u);BrowserManager.Tab t=current()==null?browser.create():current();t.scope=new JSONObject();t.web.setInitialScale(0);t.web.loadUrl(u);stopEditing();refresh();}catch(Exception e){toast(e.getMessage());}}
    private void startEditing(){BrowserManager.Tab t=current();address.setText(t==null||t.web.getUrl()==null?"":t.web.getUrl());bar.setVisibility(View.GONE);editBar.setVisibility(View.VISIBLE);
        overlay.removeAllViews();View dim=new View(this);dim.setBackgroundColor(0x33000000);dim.setOnClickListener(v->stopEditing());overlay.addView(dim,new FrameLayout.LayoutParams(-1,-1));overlay.setVisibility(View.VISIBLE);editBar.bringToFront();
        address.requestFocus();address.selectAll();InputMethodManager im=(InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);if(im!=null)im.showSoftInput(address,InputMethodManager.SHOW_IMPLICIT);}
    private void stopEditing(){InputMethodManager im=(InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);if(im!=null)im.hideSoftInputFromWindow(address.getWindowToken(),0);address.clearFocus();
        editBar.setVisibility(View.GONE);bar.setVisibility(View.VISIBLE);closeOverlay();showBar(true);}
    void nav(boolean forward){BrowserManager.Tab t=current();if(t==null)return;t.scope=new JSONObject();if(forward){if(t.web.canGoForward())t.web.goForward();}else if(t.web.canGoBack())t.web.goBack();}
    void reloadOrStop(){BrowserManager.Tab t=current();if(t==null)return;t.scope=new JSONObject();if(t.loading)t.web.stopLoading();else t.web.reload();}
    void retry(){BrowserManager.Tab t=current();if(t!=null){t.error="";t.web.reload();refresh();}}
    void newTab(){try{BrowserManager.Tab t=current();if(t!=null)capture(t);browser.create();refresh();startEditing();}catch(Exception e){toast(e.getMessage());}}
    void closeTab(String id){thumbs.remove(id);try{browser.tabs(new JSONObject().put("action","close").put("tab_id",id));}catch(Exception e){toast(e.getMessage());}refresh();}

    /** Safari-like minimization: slide the bar away while scrolling down, bring it back on scroll up. */
    private void showBar(boolean show){if(show==!barHidden)return;barHidden=!show;bar.animate().translationY(show?0:bar.getHeight()+dp(24)).alpha(show?1:0).setInterpolator(SPRING).setDuration(450).start();}
    private void watchScroll(BrowserManager.Tab t){t.web.setOnScrollChangeListener((v,x,y,ox,oy)->{if(y<=dp(8))showBar(true);else if(y-oy>dp(6))showBar(false);else if(oy-y>dp(6))showBar(true);});}

    // ---- overlays: menu sheet and tab overview ----
    private void closeOverlay(){overlay.removeAllViews();overlay.setVisibility(View.GONE);}
    private FrameLayout scrim(){overlay.removeAllViews();FrameLayout s=new FrameLayout(this);s.setBackgroundColor(0x40000000);s.setOnClickListener(v->closeOverlay());overlay.addView(s,new FrameLayout.LayoutParams(-1,-1));overlay.setVisibility(View.VISIBLE);overlay.bringToFront();return s;}
    private void rise(View sheet){sheet.setTranslationY(dp(260));sheet.setAlpha(0);sheet.animate().translationY(0).alpha(1).setInterpolator(SPRING).setDuration(480).start();}
    private TextView row(LinearLayout group,int icon,String label,int color,Runnable action){LinearLayout r=new LinearLayout(this);r.setGravity(Gravity.CENTER_VERTICAL);r.setPadding(dp(16),0,dp(16),0);r.setBackground(ripple(null));
        TextView t=new TextView(this);t.setText(label);t.setTextSize(17);t.setTextColor(color);r.addView(t,new LinearLayout.LayoutParams(0,-2,1));
        if(icon>=0)r.addView(new Icon(this,icon,color),new LinearLayout.LayoutParams(dp(20),dp(20)));
        r.setOnClickListener(v->{closeOverlay();action.run();});
        if(group.getChildCount()>0){View line=new View(this);line.setBackgroundColor(UiKit.isDark(this)?0x33FFFFFF:0x1F3C3C43);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,Math.max(1,dp(.5f)));lp.setMarginStart(dp(16));group.addView(line,lp);}
        group.addView(r,new LinearLayout.LayoutParams(-1,dp(50)));return t;}

    void showMenu(){BrowserManager.Tab t=current();FrameLayout s=scrim();
        LinearLayout sheet=new LinearLayout(this);sheet.setOrientation(LinearLayout.VERTICAL);sheet.setPadding(dp(10),dp(10),dp(10),dp(10));sheet.setBackground(UiKit.glass(this,30));sheet.setElevation(dp(12));sheet.setClickable(true);
        // Quick actions row.
        LinearLayout quick=new LinearLayout(this);
        quick.addView(quickAction(Icon.FORWARD,getString(R.string.browser_forward),()->nav(true)),new LinearLayout.LayoutParams(0,-2,1));
        quick.addView(quickAction(Icon.SHARE,getString(R.string.browser_share),this::share),new LinearLayout.LayoutParams(0,-2,1));
        quick.addView(quickAction(Icon.COPY,getString(R.string.browser_copy_link),this::copyLink),new LinearLayout.LayoutParams(0,-2,1));
        quick.addView(quickAction(Icon.PLUS,getString(R.string.browser_new_tab),this::newTab),new LinearLayout.LayoutParams(0,-2,1));
        sheet.addView(quick);
        // Zoom stepper.
        LinearLayout zoom=new LinearLayout(this);zoom.setGravity(Gravity.CENTER_VERTICAL);zoom.setPadding(dp(16),0,dp(6),0);zoom.setBackground(round(surface,dp(18)));
        TextView zl=new TextView(this);zl.setText(R.string.browser_zoom_label);zl.setTextSize(17);zl.setTextColor(fg);zoom.addView(zl,new LinearLayout.LayoutParams(0,-2,1));
        TextView pct=new TextView(this);pct.setText((t==null?100:browser.zoomPercent(t))+"%");pct.setTextSize(15);pct.setTextColor(muted);pct.setPadding(dp(8),0,dp(8),0);
        zoom.addView(tap(new Icon(this,Icon.MINUS,accent),()->{zoomBy(-1);pct.setText((current()==null?100:browser.zoomPercent(current()))+"%");},20),new LinearLayout.LayoutParams(dp(44),dp(44)));zoom.addView(pct);
        zoom.addView(tap(new Icon(this,Icon.PLUS,accent),()->{zoomBy(1);pct.setText((current()==null?100:browser.zoomPercent(current()))+"%");},20),new LinearLayout.LayoutParams(dp(44),dp(44)));
        LinearLayout.LayoutParams zlp=new LinearLayout.LayoutParams(-1,dp(52));zlp.setMargins(0,dp(10),0,dp(10));sheet.addView(zoom,zlp);
        LinearLayout group=new LinearLayout(this);group.setOrientation(LinearLayout.VERTICAL);group.setBackground(round(surface,dp(18)));
        row(group,Icon.RELOAD,getString(R.string.browser_reload),fg,this::reloadOrStop);
        row(group,Icon.FIT,getString(R.string.browser_fit_width),fg,this::fit);
        row(group,Icon.EXTERNAL,getString(R.string.browser_open_external),fg,this::openExternal);
        if(t!=null)row(group,Icon.CLOSE,getString(R.string.browser_close_tab),UiKit.dangerColor(this),()->closeTab(t.id));
        sheet.addView(group);
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);lp.setMargins(dp(10),0,dp(10),dp(10));s.addView(sheet,lp);rise(sheet);}
    private View quickAction(int icon,String label,Runnable action){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);v.setGravity(Gravity.CENTER);v.setPadding(dp(4),dp(12),dp(4),dp(10));v.setBackground(ripple(round(surface,dp(18))));
        v.addView(new Icon(this,icon,accent),new LinearLayout.LayoutParams(dp(24),dp(24)));TextView t=new TextView(this);t.setText(label);t.setTextSize(12);t.setTextColor(fg);t.setGravity(Gravity.CENTER);t.setSingleLine(true);t.setEllipsize(TextUtils.TruncateAt.END);t.setPadding(0,dp(6),0,0);v.addView(t);
        v.setOnClickListener(x->{closeOverlay();action.run();});LinearLayout wrap=new LinearLayout(this);wrap.setPadding(dp(3),0,dp(3),0);wrap.addView(v,new LinearLayout.LayoutParams(-1,-2));return wrap;}

    private void zoomBy(int direction){BrowserManager.Tab t=current();if(t==null)return;boolean changed=direction>0?t.web.zoomIn():t.web.zoomOut();if(!changed)toast(getString(direction>0?R.string.browser_zoom_max:R.string.browser_zoom_min));}
    void fit(){BrowserManager.Tab t=current();if(t==null)return;browser.fitToWidth(t);toast(getString(R.string.browser_fit_done));}
    private void copyLink(){BrowserManager.Tab t=current();if(t==null)return;((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("url",String.valueOf(t.web.getUrl())));toast(getString(R.string.copied));}
    private void share(){BrowserManager.Tab t=current();if(t==null||t.web.getUrl()==null)return;Intent i=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,t.web.getUrl()).putExtra(Intent.EXTRA_SUBJECT,String.valueOf(t.web.getTitle()));try{startActivity(Intent.createChooser(i,null));}catch(Exception e){toast(e.getMessage());}}
    private void openExternal(){BrowserManager.Tab t=current();if(t==null)return;try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(t.web.getUrl())));}catch(Exception e){toast(e.getMessage());}}

    private void capture(BrowserManager.Tab t){try{int w=t.web.getWidth(),h=t.web.getHeight();if(w<=0||h<=0)return;float s=.35f;Bitmap b=Bitmap.createBitmap(Math.max(1,(int)(w*s)),Math.max(1,(int)(h*s)),Bitmap.Config.RGB_565);Canvas c=new Canvas(b);c.scale(s,s);c.translate(-t.web.getScrollX(),-t.web.getScrollY());t.web.draw(c);thumbs.put(t.id,b);}catch(Throwable ignored){}}
    void showTabs(){BrowserManager.Tab cur=current();if(cur!=null)capture(cur);overlay.removeAllViews();overlay.setVisibility(View.VISIBLE);overlay.bringToFront();
        FrameLayout page=new FrameLayout(this);page.setBackgroundColor(UiKit.pageColor(this));page.setClickable(true);overlay.addView(page,new FrameLayout.LayoutParams(-1,-1));
        ScrollView scroll=new ScrollView(this);LinearLayout col=new LinearLayout(this);col.setOrientation(LinearLayout.VERTICAL);col.setPadding(dp(14),dp(20),dp(14),dp(110));scroll.addView(col);
        TextView title=new TextView(this);title.setText(getString(R.string.browser_tabs_count,browser.tabs.size()));title.setTextSize(30);title.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));title.setLetterSpacing(-0.02f);title.setTextColor(fg);title.setPadding(dp(4),0,0,dp(14));col.addView(title);
        LinearLayout rowView=null;int i=0;int cardH=dp(230);
        for(BrowserManager.Tab t:browser.tabs.values()){if(i%2==0){rowView=new LinearLayout(this);col.addView(rowView,new LinearLayout.LayoutParams(-1,-2));}i++;
            LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);
            FrameLayout frame=new FrameLayout(this);GradientDrawable border=round(surface,dp(20));if(t.id.equals(browser.selected))border.setStroke(dp(3),accent);frame.setBackground(border);frame.setClipToOutline(true);frame.setPadding(t.id.equals(browser.selected)?dp(3):0,t.id.equals(browser.selected)?dp(3):0,t.id.equals(browser.selected)?dp(3):0,t.id.equals(browser.selected)?dp(3):0);
            ImageView thumb=new ImageView(this);thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);Bitmap b=thumbs.get(t.id);if(b!=null)thumb.setImageBitmap(b);thumb.setClipToOutline(true);thumb.setBackground(round(surface,dp(17)));
            frame.addView(thumb,new FrameLayout.LayoutParams(-1,-1));
            String id=t.id;FrameLayout close=new FrameLayout(this);close.setBackground(round(UiKit.isDark(this)?0xCC3A3A3C:0xCCE5E5EA,dp(14)));close.addView(new Icon(this,Icon.CLOSE,fg),new FrameLayout.LayoutParams(dp(14),dp(14),Gravity.CENTER));
            close.setOnClickListener(v->{closeTab(id);if(browser.tabs.isEmpty())closeOverlay();else showTabs();});
            FrameLayout.LayoutParams clp=new FrameLayout.LayoutParams(dp(28),dp(28),Gravity.TOP|Gravity.END);clp.setMargins(0,dp(8),dp(8),0);frame.addView(close,clp);
            card.addView(frame,new LinearLayout.LayoutParams(-1,cardH));
            TextView name=new TextView(this);name.setText(TextUtils.isEmpty(t.web.getTitle())?host(t.web.getUrl()):t.web.getTitle());name.setTextSize(13);name.setTextColor(fg);name.setSingleLine(true);name.setEllipsize(TextUtils.TruncateAt.END);name.setGravity(Gravity.CENTER);name.setPadding(dp(6),dp(6),dp(6),0);card.addView(name);
            if(!t.scope.optString("task_id").isEmpty()){TextView ai=new TextView(this);ai.setText("AI");ai.setTextSize(11);ai.setTypeface(Typeface.DEFAULT_BOLD);ai.setTextColor(accent);ai.setGravity(Gravity.CENTER);card.addView(ai);}
            card.setOnClickListener(v->{browser.selected=id;closeOverlay();refresh();showBar(true);});frame.setOnClickListener(v->card.performClick());
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.setMargins(dp(6),0,dp(6),dp(16));rowView.addView(card,lp);}
        if(rowView!=null&&i%2==1){rowView.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));}
        page.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout bottom=new LinearLayout(this);bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.addView(circle(Icon.PLUS,()->{closeOverlay();newTab();}),new LinearLayout.LayoutParams(dp(50),dp(50)));
        bottom.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        TextView done=new TextView(this);done.setText(R.string.browser_done);done.setTextSize(17);done.setTypeface(Typeface.DEFAULT_BOLD);done.setTextColor(accent);done.setGravity(Gravity.CENTER);done.setPadding(dp(22),0,dp(22),0);done.setBackground(ripple(UiKit.glass(this,25)));done.setElevation(dp(6));done.setOutlineProvider(pillOutline());
        done.setOnClickListener(v->closeOverlay());bottom.addView(done,new LinearLayout.LayoutParams(-2,dp(50)));
        FrameLayout.LayoutParams blp=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);blp.setMargins(dp(12),0,dp(12),dp(12));page.addView(bottom,blp);
        page.setAlpha(0);page.setScaleX(.96f);page.setScaleY(.96f);page.animate().alpha(1).scaleX(1).scaleY(1).setInterpolator(SPRING).setDuration(420).start();}
    static String host(String url){if(url==null)return "";try{String h=Uri.parse(url).getHost();return h==null?url:h.replaceFirst("^www\\.","");}catch(Exception e){return url;}}

    /** Called by BrowserManager on page, title, progress and tab changes. */
    void refresh(){if(content==null)return;BrowserManager.Tab t=current();tabsIcon.setCount(browser.tabs.size());
        if(t==null){if(startView.getParent()!=content){content.removeAllViews();content.addView(startView,new FrameLayout.LayoutParams(-1,-1));}progress.setVisibility(View.INVISIBLE);aiChip.setVisibility(View.GONE);lock.setVisibility(View.GONE);hostText.setText("");reloadIcon.setVisibility(View.GONE);return;}
        // Re-attach only when the selected tab changed; progress updates arrive many times per page.
        if(t.web.getParent()!=content){content.removeAllViews();if(t.web.getParent() instanceof ViewGroup)((ViewGroup)t.web.getParent()).removeView(t.web);content.addView(t.web,new FrameLayout.LayoutParams(-1,-1));watchScroll(t);showBar(true);}
        content.removeView(startView);
        if(t.error.isEmpty())content.removeView(errorView);
        else{errorText.setText(host(t.web.getUrl())+"\n"+t.error);if(errorView.getParent()!=content)content.addView(errorView,new FrameLayout.LayoutParams(-1,-1));showBar(true);}
        String url=t.web.getUrl()==null?"":t.web.getUrl();hostText.setText(host(url));lock.setVisibility(url.isEmpty()?View.GONE:View.VISIBLE);lock.setKind(url.startsWith("https://")?Icon.LOCK:Icon.INFO);
        reloadIcon.setVisibility(url.isEmpty()?View.GONE:View.VISIBLE);reloadIcon.setKind(t.loading?Icon.CLOSE:Icon.RELOAD);
        progress.setVisibility(t.loading?View.VISIBLE:View.INVISIBLE);progress.setScaleX(Math.max(.05f,t.progress/100f));
        aiChip.setVisibility(t.scope.optString("task_id").isEmpty()?View.GONE:View.VISIBLE);
    }

    /** <input type=file> support for pages in this browser. */
    boolean chooseFile(ValueCallback<Uri[]> callback,WebChromeClient.FileChooserParams params){
        if(fileCallback!=null)fileCallback.onReceiveValue(null);fileCallback=callback;
        try{startActivityForResult(params.createIntent(),FILE_REQUEST);return true;}catch(Exception e){fileCallback=null;toast(e.getMessage());return false;}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
        if(request==FILE_REQUEST&&fileCallback!=null){fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result,data));fileCallback=null;}}

    /** A link the user opened in OPhone AI (GitHub, releases, a link in a reply): a new tab of this browser. */
    static final String EXTRA_OPEN_URL="open_url";
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);openRequested(intent);}
    private void openRequested(Intent intent){
        String url=intent==null?null:intent.getStringExtra(EXTRA_OPEN_URL);if(url==null||url.isEmpty())return;intent.removeExtra(EXTRA_OPEN_URL);
        new Thread(()->{try{browser.open(new JSONObject().put("url",url).put("new_tab",browser.tabs.size()<6).put("wait_ms",0),new JSONObject());}
            catch(Exception e){runOnUiThread(()->toast(String.valueOf(e.getMessage())));}},"OPhoneAI-open-link").start();
    }
    @Override public void onBackPressed(){if(editBar.getVisibility()==View.VISIBLE){stopEditing();return;}if(overlay.getVisibility()==View.VISIBLE){closeOverlay();return;}
        BrowserManager.Tab t=current();if(t!=null&&t.web.canGoBack()){nav(false);return;}super.onBackPressed();}
    @Override protected void onDestroy(){if(browser.activity==this)browser.activity=null;if(fileCallback!=null){fileCallback.onReceiveValue(null);fileCallback=null;}content.removeAllViews();super.onDestroy();}

    static final class Dot extends android.graphics.drawable.Drawable {
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);private final int size;
        Dot(int color,int size){p.setColor(color);this.size=size;}
        @Override public void draw(Canvas c){c.drawCircle(getBounds().exactCenterX(),getBounds().exactCenterY(),size/2f,p);}
        @Override public int getIntrinsicWidth(){return size;} @Override public int getIntrinsicHeight(){return size;}
        @Override public void setAlpha(int a){p.setAlpha(a);} @Override public void setColorFilter(android.graphics.ColorFilter f){p.setColorFilter(f);} @Override public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
    }

    /** Small stroked icons drawn in code (SF Symbols-like weights), identical on every device font. */
    static final class Icon extends View {
        static final int BACK=0,FORWARD=1,RELOAD=2,CLOSE=3,PLUS=4,TABS=5,MORE=6,LOCK=7,INFO=8,SEARCH=9,SHARE=10,COPY=11,EXTERNAL=12,MINUS=13,FIT=14;
        private int kind,count;private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG),text=new Paint(Paint.ANTI_ALIAS_FLAG);private final Path path=new Path();
        Icon(Context c,int kind,int color){super(c);this.kind=kind;p.setColor(color);p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);text.setColor(color);text.setTextAlign(Paint.Align.CENTER);text.setTypeface(Typeface.DEFAULT_BOLD);}
        void setKind(int k){if(k!=kind){kind=k;invalidate();}}
        void setCount(int n){if(n!=count){count=n;invalidate();}}
        @Override protected void onDraw(Canvas c){float s=Math.min(getWidth(),getHeight())/24f;c.save();c.translate((getWidth()-24*s)/2,(getHeight()-24*s)/2);c.scale(s,s);p.setStrokeWidth(2.1f);p.setStyle(Paint.Style.STROKE);path.reset();
            switch(kind){
                case BACK:path.moveTo(15,4.5f);path.lineTo(7.5f,12);path.lineTo(15,19.5f);break;
                case FORWARD:path.moveTo(9,4.5f);path.lineTo(16.5f,12);path.lineTo(9,19.5f);break;
                case CLOSE:path.moveTo(6.5f,6.5f);path.lineTo(17.5f,17.5f);path.moveTo(17.5f,6.5f);path.lineTo(6.5f,17.5f);break;
                case PLUS:path.moveTo(12,5);path.lineTo(12,19);path.moveTo(5,12);path.lineTo(19,12);break;
                case MINUS:path.moveTo(5,12);path.lineTo(19,12);break;
                case RELOAD:path.addArc(new RectF(5,5,19,19),-60,300);path.moveTo(19,4);path.lineTo(19,9);path.lineTo(14,9);break;
                case TABS:c.drawRoundRect(new RectF(6.5f,6.5f,20,20),3.5f,3.5f,p);path.moveTo(4,16);path.lineTo(4,6.5f);path.quadTo(4,4,6.5f,4);path.lineTo(16,4);text.setTextSize(count>9?7.5f:9);c.drawText(count>99?"99":String.valueOf(count),13.25f,16.5f,text);break;
                case MORE:p.setStyle(Paint.Style.FILL);c.drawCircle(5.5f,12,1.7f,p);c.drawCircle(12,12,1.7f,p);c.drawCircle(18.5f,12,1.7f,p);break;
                case LOCK:p.setStyle(Paint.Style.FILL);c.drawRoundRect(new RectF(5,11,19,21),2.5f,2.5f,p);p.setStyle(Paint.Style.STROKE);path.moveTo(8,11);path.lineTo(8,8);path.addArc(new RectF(8,4,16,12),180,180);path.moveTo(16,8);path.lineTo(16,11);break;
                case INFO:c.drawCircle(12,12,8.5f,p);path.moveTo(12,11);path.lineTo(12,16);path.moveTo(12,8);path.lineTo(12,8.2f);break;
                case SEARCH:c.drawCircle(10.5f,10.5f,6,p);path.moveTo(15,15);path.lineTo(20,20);break;
                case SHARE:path.moveTo(12,3.5f);path.lineTo(12,15);path.moveTo(8,7.5f);path.lineTo(12,3.5f);path.lineTo(16,7.5f);path.moveTo(8.5f,10.5f);path.lineTo(6.5f,10.5f);path.lineTo(6.5f,20.5f);path.lineTo(17.5f,20.5f);path.lineTo(17.5f,10.5f);path.lineTo(15.5f,10.5f);break;
                case COPY:c.drawRoundRect(new RectF(8.5f,8.5f,20,20),2.5f,2.5f,p);path.moveTo(15.5f,5.5f);path.lineTo(15.5f,4.5f);path.quadTo(15.5f,4,15,4);path.lineTo(5,4);path.quadTo(4,4,4,5);path.lineTo(4,15);path.quadTo(4,15.5f,5,15.5f);path.lineTo(5.5f,15.5f);break;
                case EXTERNAL:path.moveTo(13,4);path.lineTo(20,4);path.lineTo(20,11);path.moveTo(20,4);path.lineTo(11,13);path.moveTo(17,14);path.lineTo(17,20);path.lineTo(4,20);path.lineTo(4,7);path.lineTo(10,7);break;
                case FIT:path.moveTo(4,9);path.lineTo(4,4);path.lineTo(9,4);path.moveTo(15,4);path.lineTo(20,4);path.lineTo(20,9);path.moveTo(20,15);path.lineTo(20,20);path.lineTo(15,20);path.moveTo(9,20);path.lineTo(4,20);path.lineTo(4,15);break;
            }
            c.drawPath(path,p);c.restore();}
    }
}
