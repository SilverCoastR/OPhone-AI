package ai.ophone.bridge;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.util.Rational;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native direct-model chat. Tool-call JSON stays inside the transport and never pollutes the chat UI. */
public final class ApiChatActivity extends Activity {
    private DirectModelAgent agent;private ApiConversationStore store;private String shownConversation;private LinearLayout messages;private ScrollView scroll;private EditText input;private Button send,stop;private TextView status,currentAssistant;
    @Override protected void onCreate(Bundle state){super.onCreate(state);getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);store=new ApiConversationStore(this);shownConversation=store.conversationId();agent=new DirectModelAgent(this);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(UiKit.pageColor(this));
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(UiKit.dp(this,12),UiKit.dp(this,6),UiKit.dp(this,8),UiKit.dp(this,6));
        LinearLayout brand=new LinearLayout(this);brand.setOrientation(LinearLayout.VERTICAL);TextView title=new TextView(this);title.setText("DeepSeek · OPhoneAI");title.setTypeface(Typeface.DEFAULT_BOLD);title.setTextColor(UiKit.primaryTextColor(this));brand.addView(title);status=UiKit.body(this,new ModelApiStore(this).hasKey()?getString(R.string.deepseek_ready):getString(R.string.model_api_key_required));status.setTextSize(11);brand.addView(status);bar.addView(brand,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));
        Button chats=small(getString(R.string.api_conversations));chats.setOnClickListener(v->startActivity(new Intent(this,ApiConversationActivity.class)));bar.addView(chats);Button settings=small(getString(R.string.settings));settings.setOnClickListener(v->startActivity(new Intent(this,SettingsActivity.class)));bar.addView(settings);Button fresh=small(getString(R.string.new_chat));fresh.setOnClickListener(v->newChat());bar.addView(fresh);Button exit=small(getString(R.string.exit_platform));exit.setOnClickListener(v->exitPlatform());bar.addView(exit);root.addView(bar);
        scroll=new ScrollView(this);messages=new LinearLayout(this);messages.setOrientation(LinearLayout.VERTICAL);messages.setPadding(UiKit.dp(this,12),UiKit.dp(this,12),UiKit.dp(this,12),UiKit.dp(this,12));scroll.addView(messages);root.addView(scroll,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1));
        LinearLayout composer=new LinearLayout(this);composer.setGravity(Gravity.BOTTOM);composer.setPadding(UiKit.dp(this,8),UiKit.dp(this,6),UiKit.dp(this,8),UiKit.dp(this,8));Button copy=small(getString(R.string.copy_all));copy.setOnClickListener(v->copyAll());composer.addView(copy);input=new EditText(this);input.setHint(R.string.api_chat_hint);input.setMaxLines(5);composer.addView(input,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));send=small(getString(R.string.send));send.setOnClickListener(v->send());composer.addView(send);stop=small(getString(R.string.stop));stop.setEnabled(false);stop.setOnClickListener(v->agent.terminate());composer.addView(stop);root.addView(composer);setContentView(root);renderHistory();agent.attachUi(callbacks());
    }
    private Button small(String text){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(UiKit.dp(this,8),0,UiKit.dp(this,8),0);return b;}
    private void renderHistory(){messages.removeAllViews();JSONArray all=agent.history();for(int i=0;i<all.length();i++){JSONObject m=all.optJSONObject(i);if(m==null||m.optBoolean("internal"))continue;String role=m.optString("role"),text=m.optString("content");if(text.isEmpty())continue;if("progress".equals(role))progressBubble(text);else if("user".equals(role)||"assistant".equals(role))bubble("user".equals(role),text);}bottom();}
    private TextView bubble(boolean user,String text){TextView v=new TextView(this);v.setText(text);v.setTextIsSelectable(true);v.setTextSize(15);v.setTextColor(UiKit.primaryTextColor(this));v.setPadding(UiKit.dp(this,14),UiKit.dp(this,10),UiKit.dp(this,14),UiKit.dp(this,10));android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();bg.setColor(user?UiKit.accentColor(this):UiKit.cardColor(this));bg.setCornerRadius(UiKit.dp(this,16));v.setBackground(bg);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT);lp.gravity=user?Gravity.END:Gravity.START;lp.setMargins(0,0,0,UiKit.dp(this,10));messages.addView(v,lp);return v;}
    private TextView progressBubble(String text){TextView v=UiKit.body(this,getString(R.string.task_progress)+" · "+text);v.setTextIsSelectable(true);v.setTextSize(12);v.setPadding(UiKit.dp(this,10),UiKit.dp(this,5),UiKit.dp(this,10),UiKit.dp(this,5));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT);lp.gravity=Gravity.START;lp.setMargins(0,0,0,UiKit.dp(this,5));messages.addView(v,lp);return v;}
    private DirectModelAgent.Ui callbacks(){return new DirectModelAgent.Ui(){
        public void onStatus(String v){ui(()->status.setText(v));}public void onResponseStart(){ui(()->currentAssistant=null);}public void onText(String v){ui(()->{if(currentAssistant==null)currentAssistant=bubble(false,"");currentAssistant.append(v);bottom();});}
        public void onTool(String name){ui(()->status.setText(getString(R.string.api_using_tool,name)));}public void onProgress(String value){ui(()->{progressBubble(value);bottom();});}public void onFinished(){ui(()->{status.setText(R.string.deepseek_ready);done();});}public void onError(String value){ui(()->{status.setText(value);bubble(false,getString(R.string.api_error,value));done();});}
    };}
    private void send(){String text=input.getText().toString().trim();if(text.isEmpty())return;input.setText("");bubble(true,text);agent.attachUi(callbacks());if(agent.isRunning()){if(agent.guide(text)){status.setText(R.string.task_guidance_queued);stop.setEnabled(true);bottom();return;}}stop.setEnabled(true);agent.send(text,callbacks());}
    private void done(){send.setEnabled(true);stop.setEnabled(false);currentAssistant=null;bottom();}
    private void newChat(){if(agent.isRunning()){Toast.makeText(this,R.string.api_stop_first,Toast.LENGTH_SHORT).show();return;}agent.clear();shownConversation=store.conversationId();agent=new DirectModelAgent(this);renderHistory();status.setText(R.string.deepseek_ready);}
    private void copyAll(){JSONArray all=agent.history();StringBuilder text=new StringBuilder();for(int i=0;i<all.length();i++){JSONObject m=all.optJSONObject(i);if(m==null||m.optBoolean("internal"))continue;String role=m.optString("role");if(!("user".equals(role)||"assistant".equals(role)||"progress".equals(role)))continue;if(text.length()>0)text.append("\n\n");int label="user".equals(role)?R.string.conversation_role_user:"progress".equals(role)?R.string.conversation_role_progress:R.string.conversation_role_assistant;text.append(getString(label)).append(m.optString("content"));}((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("OPhone AI conversation",text.toString()));Toast.makeText(this,R.string.copied,Toast.LENGTH_SHORT).show();}
    private void exitPlatform(){agent.exitPlatform();startActivity(new Intent(this,PlatformActivity.class).putExtra(PlatformPreference.EXTRA_SHOW_CHOOSER,true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));finish();}
    private void ui(Runnable r){runOnUiThread(()->{if(!isFinishing()&&!(Build.VERSION.SDK_INT>=17&&isDestroyed()))r.run();});}
    private void bottom(){scroll.post(()->scroll.fullScroll(ScrollView.FOCUS_DOWN));}
    @Override protected void onResume(){super.onResume();String activeId=store.conversationId();if(!activeId.equals(shownConversation)&&!agent.isRunning()){shownConversation=activeId;agent=new DirectModelAgent(this);renderHistory();}agent.attachUi(callbacks());boolean active=agent.isRunning();send.setEnabled(true);stop.setEnabled(active);status.setText(!new ModelApiStore(this).hasKey()?getString(R.string.model_api_key_required):active?getString(R.string.task_running_can_guide):getString(R.string.deepseek_ready));}
    @Override public void onUserLeaveHint(){super.onUserLeaveHint();if(Build.VERSION.SDK_INT>=26&&RiskPolicy.pipEnabled(this))try{enterPictureInPictureMode(new PictureInPictureParams.Builder().setAspectRatio(new Rational(9,16)).build());}catch(Exception ignored){}}
    @Override public void onBackPressed(){exitPlatform();}
}
