package ai.ophone.workbench;

import android.app.Activity;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import ai.ophone.bridge.R;
import org.json.JSONObject;

/** Ordinary browser chrome; no model protocol or chat session is injected into its pages. */
public final class BrowserActivity extends Activity {
    BrowserManager browser;LinearLayout root,bar,zoomBar;FrameLayout content;EditText address;TextView info,zoomLabel;
    @Override public void onCreate(Bundle saved){super.onCreate(saved);browser=BrowserManager.get(this);browser.activity=this;root=new LinearLayout(this);root.setOrientation(1);bar=new LinearLayout(this);content=new FrameLayout(this);address=new EditText(this);address.setSingleLine(true);address.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);address.setTextSize(14);
        button(bar,"‹",()->nav(false));button(bar,"›",()->nav(true));bar.addView(address,new LinearLayout.LayoutParams(0,-2,1));button(bar,getString(R.string.browser_go),()->{try{String u=address.getText().toString();BrowserManager.url(u);BrowserManager.Tab t=browser.selected.isEmpty()?browser.create():browser.tab("");t.scope=new JSONObject();t.web.setInitialScale(0);t.web.loadUrl(u);refresh();}catch(Exception e){toast(e.getMessage());}});button(bar,getString(R.string.browser_tabs),this::chooseTabs);root.addView(bar);zoomBar=new LinearLayout(this);zoomLabel=new TextView(this);zoomLabel.setText(R.string.browser_zoom_label);zoomLabel.setGravity(Gravity.CENTER_VERTICAL);zoomBar.addView(zoomLabel,new LinearLayout.LayoutParams(0,-2,1));button(zoomBar,"−",()->zoom(-1));button(zoomBar,getString(R.string.browser_fit_width),this::fit);button(zoomBar,"+",()->zoom(1));root.addView(zoomBar);info=new TextView(this);info.setTextSize(11);root.addView(info);root.addView(content,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);refresh();}
    void button(LinearLayout row,String label,Runnable action){Button b=new Button(this);b.setText(label);b.setTextSize(12);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(12,0,12,0);b.setOnClickListener(v->action.run());row.addView(b,new LinearLayout.LayoutParams(-2,-2));}
    void toast(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    void nav(boolean forward){try{BrowserManager.Tab t=browser.tab("");t.scope=new JSONObject();if(forward)t.web.goForward();else t.web.goBack();}catch(Exception e){toast(e.getMessage());}}
    void zoom(int direction){try{BrowserManager.Tab t=browser.tab("");boolean changed=direction>0?t.web.zoomIn():t.web.zoomOut();if(!changed)toast(getString(direction>0?R.string.browser_zoom_max:R.string.browser_zoom_min));}catch(Exception e){toast(e.getMessage());}}
    void fit(){try{BrowserManager.Tab t=browser.tab("");browser.fitToWidth(t);toast(getString(R.string.browser_fit_done));}catch(Exception e){toast(e.getMessage());}}
    void chooseTabs(){try{String[] ids=browser.tabs.keySet().toArray(new String[0]),labels=new String[ids.length+1];for(int i=0;i<ids.length;i++){BrowserManager.Tab t=browser.tabs.get(ids[i]);labels[i]=String.valueOf(t.web.getTitle())+" "+t.web.getUrl();}labels[ids.length]=getString(R.string.browser_new_tab);new android.app.AlertDialog.Builder(this).setItems(labels,(d,n)->{try{if(n==ids.length)browser.create();else browser.selected=ids[n];refresh();}catch(Exception e){toast(e.getMessage());}}).show();}catch(Exception e){toast(e.getMessage());}}
    void refresh(){if(content==null)return;content.removeAllViews();try{if(browser.selected.isEmpty()){info.setText(R.string.browser_empty_info);zoomLabel.setText(R.string.browser_zoom_label);return;}BrowserManager.Tab t=browser.tab("");if(t.web.getParent() instanceof ViewGroup)((ViewGroup)t.web.getParent()).removeView(t.web);content.addView(t.web,new FrameLayout.LayoutParams(-1,-1));if(!address.hasFocus())address.setText(t.web.getUrl());zoomLabel.setText(getString(R.string.browser_zoom_label).trim()+" "+browser.zoomPercent(t)+"% ");info.setText(getString(R.string.browser_info,t.loading?getString(R.string.browser_loading_prefix):"",t.error,browser.tabs.size()));}catch(Exception e){info.setText(e.getMessage());}}
    @Override public void onBackPressed(){try{BrowserManager.Tab t=browser.tab("");if(t.web.canGoBack()){nav(false);return;}}catch(Exception ignored){}super.onBackPressed();}
    @Override protected void onDestroy(){if(browser.activity==this)browser.activity=null;content.removeAllViews();super.onDestroy();}
}
