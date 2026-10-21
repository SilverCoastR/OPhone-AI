package ai.ophone.bridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.DateFormat;
import java.util.Date;

/** Local provider-neutral conversation picker. */
public final class ApiConversationActivity extends Activity {
    @Override protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(base); UiKit.applyTheme(this); }
    private ApiConversationStore store;private LinearLayout list;
    @Override protected void onCreate(Bundle state){super.onCreate(state);store=new ApiConversationStore(this);ScrollView scroll=new ScrollView(this);list=UiKit.page(this);scroll.addView(list,new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));setContentView(scroll);render();}
    @Override protected void onResume(){super.onResume();render();}
    private void render(){list.removeAllViews();list.addView(UiKit.title(this,getString(R.string.api_conversations_title)));list.addView(UiKit.subtitle(this,getString(R.string.api_conversations_desc)));Button fresh=UiKit.actionButton(this,getString(R.string.new_chat));fresh.setOnClickListener(v->{store.create("deepseek");open();});list.addView(fresh,UiKit.cardParams(this));JSONArray all=store.conversations();String active=store.conversationId();for(int i=0;i<all.length();i++){JSONObject item=all.optJSONObject(i);if(item==null)continue;String id=item.optString("id");LinearLayout card=UiKit.card(this);Button title=UiKit.actionButton(this,(id.equals(active)?"✓ ":"")+item.optString("title",getString(R.string.new_chat)));title.setAllCaps(false);title.setOnClickListener(v->{store.select(id);open();});card.addView(title,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));String date=DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(item.optLong("updated_at")));TextView detail=UiKit.body(this,getString(R.string.api_conversation_meta,item.optInt("message_count"),item.optString("last_provider","api"),date));detail.setPadding(UiKit.dp(this,14),0,UiKit.dp(this,14),UiKit.dp(this,8));card.addView(detail);Button delete=UiKit.plainButton(this,getString(R.string.delete_chat),true);delete.setOnClickListener(v->confirmDelete(id));card.addView(delete);list.addView(card,UiKit.cardParams(this));}}
    private void confirmDelete(String id){if(DirectModelAgent.isConversationRunning(id)||PlatformSession.hasActiveConversation(this,id)){Toast.makeText(this,R.string.delete_chat_running,Toast.LENGTH_LONG).show();return;}new AlertDialog.Builder(this).setTitle(R.string.delete_chat_title).setMessage(R.string.delete_chat_message).setNegativeButton(R.string.cancel,null).setPositiveButton(R.string.delete_chat,(dialog,which)->{store.delete(id);render();}).show();}
    private void open(){startActivity(new Intent(this,ApiChatActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));finish();}
}
