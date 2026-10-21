package ai.ophone.bridge;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Build;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Launcher and explicit platform boundary. */
public final class PlatformActivity extends Activity {
    @Override protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(base); UiKit.applyTheme(this); }
    @Override protected void onCreate(Bundle state){super.onCreate(state);Intent service=new Intent(this,BridgeService.class);if(Build.VERSION.SDK_INT>=26)startForegroundService(service);else startService(service);
        if(!getIntent().getBooleanExtra(PlatformPreference.EXTRA_SHOW_CHOOSER,false)){String saved=PlatformPreference.get(this);if(PlatformPreference.valid(saved)){launchSaved(saved);return;}}
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);LinearLayout root=UiKit.page(this);scroll.addView(root,new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(UiKit.title(this,getString(R.string.platform_title)));root.addView(UiKit.subtitle(this,getString(R.string.platform_intro)));root.addView(UiKit.spacer(this,18));
        platform(root,R.string.platform_chatgpt,R.string.platform_chatgpt_desc,true,()->enter(PlatformSession.CHATGPT,new Intent(this,ChatActivity.class)));
        platform(root,R.string.platform_deepseek,R.string.platform_deepseek_desc,true,()->enter(PlatformSession.DEEPSEEK,new Intent(this,ApiChatActivity.class)));
        platform(root,R.string.platform_openai,R.string.platform_openai_desc,true,()->enter(PlatformSession.OPENAI,new Intent(this,ApiChatActivity.class)));
        platform(root,R.string.platform_claude,R.string.platform_claude_desc,true,()->enter(PlatformSession.CLAUDE,new Intent(this,ApiChatActivity.class)));
        root.addView(UiKit.section(this,getString(R.string.platform_local_section)));platform(root,R.string.api_conversations_title,R.string.api_conversations_desc,true,()->startActivity(new Intent(this,ApiConversationActivity.class)));platform(root,R.string.open_settings,R.string.platform_settings_desc,true,()->startActivity(new Intent(this,SettingsActivity.class)));setContentView(scroll);
    }
    private void platform(LinearLayout root,int titleRes,int descRes,boolean enabled,Runnable action){LinearLayout card=UiKit.card(this);Button button=UiKit.actionButton(this,getString(titleRes));button.setEnabled(enabled);if(action!=null)button.setOnClickListener(v->action.run());card.addView(button,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));TextView desc=UiKit.body(this,getString(descRes));desc.setPadding(UiKit.dp(this,14),0,UiKit.dp(this,14),UiKit.dp(this,6));card.addView(desc);root.addView(card,UiKit.cardParams(this));}
    /** ChatGPT web and the API platforms never share work: entering one side ends the other side's tasks. API platforms hand off to each other. */
    private void enter(String provider,Intent intent){PlatformSession.switchTo(this,provider);startActivity(intent);finish();}
    private void launchSaved(String provider){Class<?> activity=PlatformPreference.target(this,provider).activity;startActivity(new Intent(this,activity));finish();}
}
