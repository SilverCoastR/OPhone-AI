package ai.ophone.bridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Local-only configuration screen; FLAG_SECURE keeps credentials out of screenshots. */
public final class ModelApiSettingsActivity extends Activity {
    private ModelApiStore store;private EditText key,maxTokens;private Spinner model,effort;private Switch thinking;private TextView status;
    @Override protected void onCreate(Bundle state){super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);store=new ModelApiStore(this);
        ScrollView scroll=new ScrollView(this);LinearLayout root=UiKit.page(this);scroll.addView(root,new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(UiKit.title(this,getString(R.string.model_api_title)));root.addView(UiKit.subtitle(this,getString(R.string.model_api_intro)));root.addView(UiKit.spacer(this,16));
        LinearLayout card=UiKit.card(this);card.addView(UiKit.body(this,getString(R.string.deepseek_key_label)));
        key=new EditText(this);key.setHint(store.hasKey()?getString(R.string.key_saved_hint):getString(R.string.key_missing_hint));key.setSingleLine(true);key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);card.addView(key,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(UiKit.body(this,getString(R.string.deepseek_model_label)));model=new Spinner(this);String[] models={ModelApiStore.MODEL_FLASH,ModelApiStore.MODEL_PRO};model.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,models));card.addView(model);
        thinking=new Switch(this);thinking.setText(R.string.deepseek_thinking);card.addView(thinking);
        card.addView(UiKit.body(this,getString(R.string.deepseek_effort_label)));effort=new Spinner(this);String[] efforts={"low","high","max"};effort.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,efforts));card.addView(effort);
        card.addView(UiKit.body(this,getString(R.string.deepseek_max_tokens)));maxTokens=new EditText(this);maxTokens.setSingleLine(true);maxTokens.setInputType(InputType.TYPE_CLASS_NUMBER);card.addView(maxTokens);
        status=UiKit.body(this,"");status.setPadding(0,UiKit.dp(this,8),0,0);card.addView(status);root.addView(card,UiKit.cardParams(this));
        ModelApiStore.Config c=store.config();model.setSelection(c.model.equals(ModelApiStore.MODEL_PRO)?1:0);thinking.setChecked(c.thinking);effort.setSelection("low".equals(c.reasoningEffort)?0:"max".equals(c.reasoningEffort)?2:1);maxTokens.setText(String.valueOf(c.maxTokens));updateStatus();
        add(root,R.string.model_api_save,()->save(true));add(root,R.string.model_api_test,this::test);add(root,R.string.model_api_clear_key,this::confirmClear);add(root,R.string.open_deepseek,()->{PlatformSession.cancelProvider(this,PlatformSession.CHATGPT);startActivity(new Intent(this,ApiChatActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));});add(root,R.string.return_settings,()->finish());setContentView(scroll);
    }
    private void add(LinearLayout root,int text,Runnable action){Button b=UiKit.actionButton(this,getString(text));b.setOnClickListener(v->action.run());LinearLayout card=UiKit.card(this);card.addView(b);root.addView(card,UiKit.cardParams(this));}
    private boolean save(boolean toast){try{int value=Integer.parseInt(maxTokens.getText().toString().trim());store.saveConfig(String.valueOf(model.getSelectedItem()),thinking.isChecked(),String.valueOf(effort.getSelectedItem()),value);String raw=key.getText().toString().trim();if(!raw.isEmpty()){store.saveKey(raw);key.setText("");}updateStatus();if(toast)Toast.makeText(this,R.string.model_api_saved,Toast.LENGTH_SHORT).show();return true;}catch(Exception e){status.setText(DetailedDiagnostics.clean(e.getMessage()));return false;}}
    private void test(){if(!save(false))return;if(!store.hasKey()){status.setText(R.string.model_api_key_required);return;}status.setText(R.string.model_api_testing);new Thread(()->{try{String result=new DeepSeekClient().testConnection(store.apiKey());runOnUiThread(()->status.setText(result));}catch(Exception e){String safe=DetailedDiagnostics.clean(e.getMessage());runOnUiThread(()->status.setText(safe));}},"OPhoneAI-DeepSeek-test").start();}
    private void confirmClear(){new AlertDialog.Builder(this).setTitle(R.string.model_api_clear_key).setMessage(R.string.model_api_clear_confirm).setNegativeButton(R.string.cancel,null).setPositiveButton(R.string.model_api_clear_key,(d,w)->{store.clearKey();updateStatus();}).show();}
    private void updateStatus(){status.setText(store.hasKey()?getString(R.string.model_api_key_saved):getString(R.string.model_api_key_required));}
}
