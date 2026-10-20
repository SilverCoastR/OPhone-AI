package ai.ophone.bridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Local-only configuration of one API platform; FLAG_SECURE keeps credentials out of screenshots. */
public final class ModelApiSettingsActivity extends Activity {
    static final String EXTRA_PROVIDER="provider";
    private ModelProvider provider;private ModelApiStore store;private EditText key,maxTokens,baseUrl,modelText;private Spinner model,effort;private Switch thinking;private TextView status;

    static Intent intent(Context context,ModelProvider provider){return new Intent(context,ModelApiSettingsActivity.class).putExtra(EXTRA_PROVIDER,provider.id);}

    @Override protected void onCreate(Bundle state){super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        provider=ModelProvider.of(getIntent().getStringExtra(EXTRA_PROVIDER));if(provider==null)provider=ModelProvider.current(this);store=provider.store(this);
        ScrollView scroll=new ScrollView(this);LinearLayout root=UiKit.page(this);scroll.addView(root,new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(UiKit.title(this,provider.label+" API"));root.addView(UiKit.subtitle(this,getString(R.string.model_api_intro_for,provider.label,provider.company)));root.addView(UiKit.spacer(this,16));
        LinearLayout card=UiKit.card(this);card.addView(UiKit.body(this,getString(R.string.api_key_label,provider.label)));
        key=new EditText(this);key.setHint(store.hasKey()?getString(R.string.key_saved_hint):getString(R.string.key_missing_hint_for,provider.label));key.setSingleLine(true);key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);card.addView(key,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        ModelApiStore.Config c=store.config();
        if(provider.editableBaseUrl){card.addView(UiKit.body(this,getString(R.string.api_base_url_label)));baseUrl=new EditText(this);baseUrl.setSingleLine(true);baseUrl.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);baseUrl.setText(c.baseUrl);card.addView(baseUrl);
            TextView hint=UiKit.body(this,getString(R.string.api_base_url_hint));hint.setTextSize(12.5f);card.addView(hint);}
        card.addView(UiKit.body(this,getString(R.string.deepseek_model_label)));
        if(provider.fixedModels){model=new Spinner(this);model.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,provider.models));card.addView(model);model.setSelection(Math.max(0,Arrays.asList(provider.models).indexOf(c.model)));}
        else{modelText=new EditText(this);modelText.setSingleLine(true);modelText.setHint(R.string.api_model_hint);modelText.setText(c.model);card.addView(modelText);
            Button pick=UiKit.actionButton(this,getString(R.string.api_model_pick));pick.setOnClickListener(v->pickModel());card.addView(pick);}
        if(provider.supportsThinking()){thinking=new Switch(this);thinking.setText(R.string.deepseek_thinking);thinking.setChecked(c.thinking);card.addView(thinking);
            card.addView(UiKit.body(this,getString(R.string.deepseek_effort_label)));effort=new Spinner(this);effort.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,provider.efforts));card.addView(effort);effort.setSelection(Math.max(0,Arrays.asList(provider.efforts).indexOf(c.reasoningEffort)));}
        card.addView(UiKit.body(this,getString(R.string.deepseek_max_tokens)));maxTokens=new EditText(this);maxTokens.setSingleLine(true);maxTokens.setInputType(InputType.TYPE_CLASS_NUMBER);maxTokens.setText(String.valueOf(c.maxTokens));card.addView(maxTokens);
        status=UiKit.body(this,"");status.setPadding(0,UiKit.dp(this,8),0,0);card.addView(status);root.addView(card,UiKit.cardParams(this));updateStatus();
        add(root,R.string.model_api_save,()->save(true));add(root,R.string.model_api_test,this::test);add(root,R.string.model_api_clear_key,this::confirmClear);
        add(root,getString(R.string.open_platform_chat,provider.label),()->{if(!save(false))return;PlatformSession.switchTo(this,provider.platform);startActivity(new Intent(this,ApiChatActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));finish();});
        add(root,R.string.return_settings,this::finish);setContentView(scroll);
    }
    private void add(LinearLayout root,int text,Runnable action){add(root,getString(text),action);}
    private void add(LinearLayout root,String text,Runnable action){Button b=UiKit.actionButton(this,text);b.setOnClickListener(v->action.run());LinearLayout card=UiKit.card(this);card.addView(b);root.addView(card,UiKit.cardParams(this));}
    private String chosenModel(){return model!=null?String.valueOf(model.getSelectedItem()):modelText.getText().toString().trim();}
    private boolean save(boolean toast){try{
        int value=Integer.parseInt(maxTokens.getText().toString().trim());String m=chosenModel();if(m.isEmpty())throw new IllegalArgumentException(getString(R.string.api_model_required));
        if(baseUrl!=null)store.saveBaseUrl(baseUrl.getText().toString());
        store.saveConfig(m,thinking!=null&&thinking.isChecked(),effort!=null?String.valueOf(effort.getSelectedItem()):"high",value);
        String raw=key.getText().toString().trim();if(!raw.isEmpty()){store.saveKey(raw);key.setText("");key.setHint(R.string.key_saved_hint);}
        updateStatus();if(toast)Toast.makeText(this,R.string.model_api_saved,Toast.LENGTH_SHORT).show();return true;
    }catch(Exception e){status.setText(DetailedDiagnostics.clean(e.getMessage()));return false;}}
    private void test(){if(!saveKeyOnly())return;if(!store.hasKey()){status.setText(getString(R.string.model_api_not_ready,provider.label));return;}status.setText(R.string.model_api_testing);
        new Thread(()->{try{String result=provider.client().testConnection(store.apiKey(),store.config());runOnUiThread(()->status.setText(result));}catch(Exception e){String safe=DetailedDiagnostics.clean(e.getMessage());runOnUiThread(()->status.setText(safe));}},"OPhoneAI-model-test").start();}
    /** Testing and listing models needs the key and endpoint, not a model ID yet. */
    private boolean saveKeyOnly(){try{if(baseUrl!=null)store.saveBaseUrl(baseUrl.getText().toString());String raw=key.getText().toString().trim();if(!raw.isEmpty()){store.saveKey(raw);key.setText("");key.setHint(R.string.key_saved_hint);}return true;}catch(Exception e){status.setText(DetailedDiagnostics.clean(e.getMessage()));return false;}}
    private void pickModel(){if(!saveKeyOnly())return;
        if(!store.hasKey()){showModels(new ArrayList<>(Arrays.asList(provider.models)));return;}
        status.setText(R.string.api_model_loading);
        new Thread(()->{List<String> models;String error=null;try{models=provider.client().listModels(store.apiKey(),store.config());}catch(Exception e){models=new ArrayList<>(Arrays.asList(provider.models));error=DetailedDiagnostics.clean(e.getMessage());}
            List<String> shown=models;String failure=error;runOnUiThread(()->{status.setText(failure==null?"":failure);showModels(shown);});},"OPhoneAI-model-list").start();}
    private void showModels(List<String> models){if(models.isEmpty()){status.setText(R.string.api_model_none);return;}String[] items=models.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle(R.string.api_model_pick).setItems(items,(d,which)->modelText.setText(items[which])).setNegativeButton(R.string.cancel,null).show();}
    private void confirmClear(){new AlertDialog.Builder(this).setTitle(R.string.model_api_clear_key).setMessage(R.string.model_api_clear_confirm).setNegativeButton(R.string.cancel,null).setPositiveButton(R.string.model_api_clear_key,(d,w)->{store.clearKey();key.setHint(getString(R.string.key_missing_hint_for,provider.label));updateStatus();}).show();}
    private void updateStatus(){status.setText(store.ready()?getString(R.string.model_api_key_saved):getString(R.string.model_api_not_ready,provider.label));}
}
