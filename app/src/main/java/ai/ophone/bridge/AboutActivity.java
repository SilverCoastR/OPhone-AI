package ai.ophone.bridge;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import org.json.JSONObject;
import java.text.DateFormat;
import java.util.Date;

/** About OPhone AI: what it is, the GitHub project, and updates from GitHub Releases. */
public final class AboutActivity extends Activity {
    @Override protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(base); UiKit.applyTheme(this); }
    private TextView updateTitle,updateDetail,notes;private Button updateButton;private ProgressBar progress;
    private final Runnable listener=this::renderUpdate;

    @Override protected void onCreate(Bundle state){super.onCreate(state);
        ScrollView scroll=new ScrollView(this);LinearLayout page=UiKit.page(this);scroll.addView(page,new ScrollView.LayoutParams(-1,-2));
        // App identity
        LinearLayout hero=new LinearLayout(this);hero.setOrientation(LinearLayout.VERTICAL);hero.setGravity(Gravity.CENTER_HORIZONTAL);hero.setPadding(0,UiKit.dp(this,24),0,UiKit.dp(this,18));
        android.widget.ImageView icon=new android.widget.ImageView(this);icon.setImageResource(R.drawable.ophone_ai_logo);icon.setContentDescription(getString(R.string.app_name));icon.setElevation(UiKit.dp(this,8));
        icon.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);GradientDrawable round=new GradientDrawable();round.setShape(GradientDrawable.OVAL);round.setColor(0xFF111111);icon.setBackground(round);
        hero.addView(icon,new LinearLayout.LayoutParams(UiKit.dp(this,92),UiKit.dp(this,92)));
        TextView name=new TextView(this);name.setText(R.string.app_name);name.setTextSize(28);name.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));name.setTextColor(UiKit.primaryTextColor(this));name.setPadding(0,UiKit.dp(this,14),0,0);hero.addView(name);
        TextView ver=UiKit.body(this,getString(R.string.about_version,BuildConfig.VERSION_NAME));ver.setTextSize(15);hero.addView(ver);
        page.addView(hero);

        LinearLayout intro=UiKit.card(this);TextView text=UiKit.body(this,getString(R.string.about_intro));text.setTextSize(16);text.setTextColor(UiKit.primaryTextColor(this));text.setLineSpacing(0,1.25f);intro.addView(text);
        page.addView(intro,UiKit.cardParams(this));

        page.addView(UiKit.section(this,getString(R.string.about_section_update)));
        LinearLayout upd=UiKit.card(this);
        updateTitle=new TextView(this);updateTitle.setTextSize(17);updateTitle.setTypeface(Typeface.DEFAULT_BOLD);updateTitle.setTextColor(UiKit.primaryTextColor(this));upd.addView(updateTitle);
        updateDetail=UiKit.body(this,"");updateDetail.setTextSize(14);updateDetail.setPadding(0,UiKit.dp(this,2),0,UiKit.dp(this,4));upd.addView(updateDetail);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(1000);progress.setVisibility(View.GONE);upd.addView(progress,new LinearLayout.LayoutParams(-1,UiKit.dp(this,10)));
        notes=UiKit.body(this,"");notes.setTextSize(13.5f);notes.setVisibility(View.GONE);notes.setPadding(0,UiKit.dp(this,6),0,UiKit.dp(this,4));upd.addView(notes);
        updateButton=UiKit.plainButton(this,"",false);updateButton.setTypeface(Typeface.DEFAULT_BOLD);updateButton.setOnClickListener(v->updateAction());upd.addView(updateButton,new LinearLayout.LayoutParams(-1,UiKit.dp(this,48)));
        page.addView(upd,UiKit.cardParams(this));
        LinearLayout autoCard=UiKit.card(this);Switch auto=new Switch(this);auto.setText(R.string.about_auto_check);auto.setTextColor(UiKit.primaryTextColor(this));auto.setChecked(UpdateManager.autoCheck(this));auto.setMinHeight(UiKit.dp(this,44));
        auto.setOnCheckedChangeListener((b,on)->UpdateManager.setAutoCheck(this,on));autoCard.addView(auto);TextView autoDesc=UiKit.body(this,getString(R.string.about_auto_check_desc));autoDesc.setTextSize(13);autoCard.addView(autoDesc);
        page.addView(autoCard,UiKit.cardParams(this));

        page.addView(UiKit.section(this,getString(R.string.about_section_project)));
        LinearLayout links=UiKit.card(this);links.setPadding(0,UiKit.dp(this,4),0,UiKit.dp(this,4));
        link(links,R.string.about_github,ShareActivity.projectPage());link(links,R.string.about_releases,UpdateManager.RELEASES_URL);link(links,R.string.about_issues,UpdateManager.REPO_URL+"/issues");
        Button feedback=UiKit.actionButton(this,getString(R.string.feedback_title));feedback.setOnClickListener(v->startActivity(new Intent(this,FeedbackActivity.class)));links.addView(feedback,new LinearLayout.LayoutParams(-1,-2));
        page.addView(links,UiKit.cardParams(this));
        TextView license=UiKit.body(this,getString(R.string.about_license));license.setTextSize(12.5f);license.setGravity(Gravity.CENTER);license.setPadding(0,UiKit.dp(this,8),0,0);page.addView(license);
        setContentView(scroll);
        // Every time the page is opened it checks GitHub; elsewhere the check runs at most once a day.
        UpdateManager.reconcileInstalled(this);UpdateManager.checkNow(this);
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);UpdateManager.checkNow(this);}
    private void link(LinearLayout card,int label,String url){Button b=UiKit.actionButton(this,getString(label));b.setOnClickListener(v->WebPageActivity.open(this,url));card.addView(b,new LinearLayout.LayoutParams(-1,-2));}

    private void updateAction(){JSONObject s=UpdateManager.status(this);String st=s.optString("state");
        if("available".equals(st)||("failed".equals(st)&&s.optJSONObject("latest")!=null&&UpdateManager.newer(s.optJSONObject("latest").optString("tag"),BuildConfig.VERSION_NAME)))UpdateManager.download(this);
        else if("downloaded".equals(st))UpdateManager.install(this);
        else if(!"checking".equals(st)&&!"downloading".equals(st))UpdateManager.checkNow(this);}

    private void renderUpdate(){JSONObject s=UpdateManager.status(this);String st=s.optString("state");JSONObject latest=s.optJSONObject("latest");
        long last=s.optLong("last_check_at");String lastText=last==0?getString(R.string.update_never_checked):getString(R.string.update_last_checked,DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(last)));
        progress.setVisibility(View.GONE);notes.setVisibility(View.GONE);updateButton.setEnabled(true);
        String version=latest==null?"":latest.optString("version");
        if("checking".equals(st)){updateTitle.setText(R.string.update_checking);updateDetail.setText(lastText);updateButton.setText(R.string.update_checking);updateButton.setEnabled(false);}
        else if("available".equals(st)){updateTitle.setText(getString(R.string.update_available,version));updateDetail.setText(sizeText(latest)+lastText);showNotes(latest);updateButton.setText(R.string.update_download);}
        else if("downloading".equals(st)){long d=s.optLong("downloaded"),t=s.optLong("total");updateTitle.setText(getString(R.string.update_downloading_version,version));
            updateDetail.setText(t>0?String.format(java.util.Locale.ROOT,"%.1f / %.1f MB",d/1048576.0,t/1048576.0):String.format(java.util.Locale.ROOT,"%.1f MB",d/1048576.0));
            progress.setVisibility(View.VISIBLE);progress.setIndeterminate(t<=0);if(t>0)progress.setProgress((int)(d*1000/t));updateButton.setText(R.string.update_downloading);updateButton.setEnabled(false);}
        else if("downloaded".equals(st)){updateTitle.setText(getString(R.string.update_ready_title,version));updateDetail.setText(R.string.update_ready_body);showNotes(latest);updateButton.setText(R.string.update_install);}
        else if("failed".equals(st)){updateTitle.setText(R.string.update_failed);updateDetail.setText(s.optString("error")+"\n"+lastText);updateButton.setText(latest!=null&&UpdateManager.newer(latest.optString("tag"),BuildConfig.VERSION_NAME)?getString(R.string.update_download):getString(R.string.update_check));}
        else if("latest".equals(st)&&!s.optString("updated_to").isEmpty()){updateTitle.setText(getString(R.string.update_installed_title,s.optString("updated_to")));updateDetail.setText(getString(R.string.update_installed_body)+"\n"+lastText);updateButton.setText(R.string.update_check);}
        else if("latest".equals(st)){updateTitle.setText(R.string.update_latest);updateDetail.setText(lastText);updateButton.setText(R.string.update_check);}
        else{updateTitle.setText(getString(R.string.about_version,BuildConfig.VERSION_NAME));updateDetail.setText(lastText);updateButton.setText(R.string.update_check);}
    }
    private String sizeText(JSONObject latest){long size=latest==null?0:latest.optLong("size");return size>0?String.format(java.util.Locale.ROOT,"%.1f MB · ",size/1048576.0):"";}
    private void showNotes(JSONObject latest){String body=latest==null?"":latest.optString("notes").trim();if(body.isEmpty())return;notes.setText(body.length()>600?body.substring(0,600)+"…":body);notes.setVisibility(View.VISIBLE);}

    @Override protected void onResume(){super.onResume();UpdateManager.addListener(listener);renderUpdate();}
    @Override protected void onPause(){UpdateManager.removeListener(listener);super.onPause();}
    @Override protected void onStop(){UpdateManager.clearUpdatedNote(this);super.onStop();}
}
