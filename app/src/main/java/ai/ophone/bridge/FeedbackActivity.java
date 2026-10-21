package ai.ophone.bridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Bug reports and suggestions. Builds a zip (feedback text + optional redacted logs) and hands it to the
 * user's email app addressed to BuildConfig.FEEDBACK_EMAIL; nothing is sent without the user pressing send there.
 */
public final class FeedbackActivity extends Activity {
    @Override protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(base); UiKit.applyTheme(this); }
    private static final String[] KINDS={"bug","suggestion","other"};
    private int kind=0;private EditText description,contact;private Switch logs;private final TextView[] kindViews=new TextView[3];

    @Override protected void onCreate(Bundle state){super.onCreate(state);
        ScrollView scroll=new ScrollView(this);LinearLayout page=UiKit.page(this);scroll.addView(page,new ScrollView.LayoutParams(-1,-2));
        page.addView(UiKit.title(this,getString(R.string.feedback_title)));page.addView(UiKit.subtitle(this,getString(R.string.feedback_intro)));

        page.addView(UiKit.section(this,getString(R.string.feedback_kind)));
        LinearLayout seg=new LinearLayout(this);seg.setPadding(UiKit.dp(this,3),UiKit.dp(this,3),UiKit.dp(this,3),UiKit.dp(this,3));seg.setBackground(round(UiKit.isDark(this)?0x3D767680:0x1F767680,10));
        int[] labels={R.string.feedback_kind_bug,R.string.feedback_kind_suggestion,R.string.feedback_kind_other};
        for(int i=0;i<3;i++){TextView t=new TextView(this);t.setText(labels[i]);t.setGravity(Gravity.CENTER);t.setTextSize(14);t.setTextColor(UiKit.primaryTextColor(this));int index=i;t.setOnClickListener(v->selectKind(index));kindViews[i]=t;seg.addView(t,new LinearLayout.LayoutParams(0,UiKit.dp(this,34),1));}
        page.addView(seg,new LinearLayout.LayoutParams(-1,-2));selectKind(0);

        page.addView(UiKit.section(this,getString(R.string.feedback_description)));
        LinearLayout card=UiKit.card(this);
        description=field(getString(R.string.feedback_description_hint),true);card.addView(description,new LinearLayout.LayoutParams(-1,-2));
        page.addView(card,UiKit.cardParams(this));

        LinearLayout contactCard=UiKit.card(this);contact=field(getString(R.string.feedback_contact_hint),false);contact.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);contactCard.addView(contact,new LinearLayout.LayoutParams(-1,-2));
        page.addView(contactCard,UiKit.cardParams(this));

        LinearLayout logCard=UiKit.card(this);logs=new Switch(this);logs.setText(R.string.feedback_include_logs);logs.setTextColor(UiKit.primaryTextColor(this));logs.setChecked(true);logs.setMinHeight(UiKit.dp(this,44));logCard.addView(logs);
        TextView logDesc=UiKit.body(this,getString(R.string.feedback_logs_desc));logDesc.setTextSize(13);logCard.addView(logDesc);
        Button preview=UiKit.actionButton(this,getString(R.string.feedback_preview));preview.setOnClickListener(v->preview());logCard.addView(preview,new LinearLayout.LayoutParams(-1,-2));
        page.addView(logCard,UiKit.cardParams(this));

        TextView send=new TextView(this);send.setText(R.string.feedback_send);send.setTextSize(17);send.setTypeface(Typeface.DEFAULT_BOLD);send.setTextColor(0xFFFFFFFF);send.setGravity(Gravity.CENTER);
        send.setBackground(round(UiKit.accentColor(this),16));send.setOnClickListener(v->send());LinearLayout.LayoutParams sl=new LinearLayout.LayoutParams(-1,UiKit.dp(this,52));sl.topMargin=UiKit.dp(this,8);page.addView(send,sl);
        String to=BuildConfig.FEEDBACK_EMAIL;TextView dest=UiKit.body(this,to.isEmpty()?getString(R.string.feedback_no_email):getString(R.string.feedback_to,to));dest.setTextSize(12.5f);dest.setGravity(Gravity.CENTER);dest.setPadding(0,UiKit.dp(this,10),0,0);page.addView(dest);
        setContentView(scroll);
    }
    private GradientDrawable round(int color,float r){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(UiKit.dp(this,r));return d;}
    private EditText field(String hint,boolean multi){EditText e=new EditText(this);e.setHint(hint);e.setBackground(null);e.setTextSize(17);e.setTextColor(UiKit.primaryTextColor(this));e.setHintTextColor(UiKit.secondaryTextColor(this));e.setPadding(0,UiKit.dp(this,4),0,UiKit.dp(this,4));
        if(multi){e.setMinLines(6);e.setGravity(Gravity.TOP|Gravity.START);e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);}else e.setSingleLine(true);return e;}
    private void selectKind(int index){kind=index;for(int i=0;i<3;i++){TextView t=kindViews[i];if(t==null)continue;boolean on=i==index;t.setBackground(on?round(UiKit.cardColor(this),8):null);t.setElevation(on?UiKit.dp(this,1):0);t.setTypeface(on?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);}}

    /** Only the app version and a UTC time: no device model, system version, language, time zone or location. */
    private String header(){SimpleDateFormat utc=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'",Locale.ROOT);utc.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return "Type: "+KINDS[kind]+"\nApp: OPhone AI "+BuildConfig.VERSION_NAME+" ("+BuildConfig.VERSION_CODE+")\nTime: "+utc.format(new Date())+"\nContact: "+contact.getText().toString().trim()+"\n\n"+describedProblem()+"\n";}
    /** The user's description; when empty, a short one from the newest recent error or warning, else "no description". */
    private String describedProblem(){String typed=description.getText().toString().trim();if(!typed.isEmpty())return typed;
        try{long since=System.currentTimeMillis()-6*3600_000L;
            for(String level:new String[]{"error","warning"}){org.json.JSONArray rows=AgentStore.get(this).logs(new JSONObject().put("limit",1).put("level",level)).optJSONArray("events");
                JSONObject row=rows==null?null:rows.optJSONObject(0);
                if(row==null||row.optLong("time_ms")<since)continue;
                String what=scrub(row.optString("code")+(row.optString("message").isEmpty()?"":": "+row.optString("message"))).replaceAll("\\s+"," ").trim();
                if(what.length()>120)what=what.substring(0,120)+"…";
                if(!what.isEmpty())return getString("error".equals(level)?R.string.feedback_auto_error:R.string.feedback_auto_warning,what);}
        }catch(Exception ignored){}
        return getString(R.string.feedback_no_description);}
    /** Logs attached to a report: runtime events, task diagnostics and this app's own logcat, all passed through the credential redactor. */
    // Control characters (NUL etc. from logcat) break email apps and text viewers; tabs and line breaks stay.
    private String collectLogs(){return scrub(rawLogs()).replaceAll("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]","");}
    private String rawLogs(){StringBuilder s=new StringBuilder();
        // Errors and warnings first: frequent informational events must not push the actual failure out of the report.
        for(String level:new String[]{"error","warning"})try{s.append("=== ").append(level).append("s ===\n").append(DetailedDiagnostics.clean(AgentStore.get(this).logs(new JSONObject().put("limit",40).put("level",level)).toString(2))).append("\n\n");}catch(Exception e){s.append(level).append(" logs unavailable: ").append(e.getMessage()).append("\n\n");}
        try{s.append("=== runtime_logs ===\n").append(DetailedDiagnostics.clean(RuntimeDiagnostics.logs(this,60).toString(2))).append("\n\n");}catch(Exception e){s.append("runtime_logs unavailable: ").append(e.getMessage()).append("\n\n");}
        try{s.append("=== diagnostics ===\n").append(DetailedDiagnostics.clean(AgentStore.get(this).logs(new JSONObject().put("limit",80)).toString(2))).append("\n\n");}catch(Exception e){s.append("diagnostics unavailable: ").append(e.getMessage()).append("\n\n");}
        try{s.append("=== app_logcat ===\n").append(DetailedDiagnostics.clean(OwnAppLogcat.read(this,400).optString("text"))).append("\n");}catch(Exception e){s.append("app_logcat unavailable: ").append(e.getMessage()).append("\n");}
        return s.toString();}

    private void preview(){String text=header()+(logs.isChecked()?"\n"+collectLogs():"");TextView v=new TextView(this);v.setText(text.length()>60000?text.substring(0,60000)+"\n…":text);v.setTextSize(11);v.setTypeface(Typeface.MONOSPACE);v.setTextIsSelectable(true);v.setPadding(UiKit.dp(this,18),UiKit.dp(this,8),UiKit.dp(this,18),UiKit.dp(this,8));
        ScrollView s=new ScrollView(this);s.addView(v);new AlertDialog.Builder(this).setTitle(R.string.feedback_preview).setView(s).setPositiveButton(android.R.string.ok,null).show();}

    // Email bodies travel through an Intent (about 1 MB for the whole transaction), so very long logs keep their newest part.
    private static final int MAX_BODY_CHARS=150000;
    /** Sends the report as plain text in the email body (no attachment), addressed to BuildConfig.FEEDBACK_EMAIL. */
    private void send(){
        try{String body=header();
            if(logs.isChecked()){String log=collectLogs();int room=MAX_BODY_CHARS-body.length()-200;
                if(log.length()>room)log=log.substring(0,Math.max(0,room/2))+"\n\n[... "+(log.length()-room)+" characters omitted ...]\n\n"+log.substring(log.length()-room/2);
                body+="\n----- logs -----\n"+log;}
            String subject="[OPhone AI "+BuildConfig.VERSION_NAME+"] "+getString(new int[]{R.string.feedback_kind_bug,R.string.feedback_kind_suggestion,R.string.feedback_kind_other}[kind])+": "+firstLine(describedProblem());
            Intent i=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT,subject).putExtra(Intent.EXTRA_TEXT,body);
            String to=BuildConfig.FEEDBACK_EMAIL;
            if(to.isEmpty()){new AlertDialog.Builder(this).setTitle(R.string.feedback_no_email_title).setMessage(R.string.feedback_no_email_body).setNegativeButton(R.string.cancel,null)
                    .setPositiveButton(R.string.feedback_share_package,(d,w)->startActivity(Intent.createChooser(i,getString(R.string.feedback_title)))).show();return;}
            i.putExtra(Intent.EXTRA_EMAIL,new String[]{to});
            chooseRoute(i,to,subject,body);
        }catch(Exception e){Toast.makeText(this,String.valueOf(e.getMessage()),Toast.LENGTH_LONG).show();}}
    private static final java.util.regex.Pattern PRIVATE_FIELDS=java.util.regex.Pattern.compile("(?i)(\"?(?:model|manufacturer|brand|device|device_name|product|hardware|board|fingerprint|build_id|build_display|android_version|os_version|release|sdk|sdk_int|security_patch|serial|imei|meid|locale|language|languages|country|region|timezone|time_zone|latitude|longitude|lat|lng|lon|altitude|accuracy|location|address|city|province|ssid|wifi_ssid|bssid|ip|ip_address|mac|mac_address|carrier|operator|phone_number|abi|abis|supported_abis)\"?\\s*[:=]\\s*)(\"(?:[^\"\\\\]|\\\\.)*\"|\\[[^\\]]*\\]|[-\\w.:]+)");
    private static final java.util.regex.Pattern ESCAPED_FIELDS=java.util.regex.Pattern.compile("(?i)(\\\\\"(?:model|manufacturer|brand|device|device_name|product|hardware|board|fingerprint|build_id|build_display|android_version|os_version|release|sdk|sdk_int|security_patch|serial|imei|meid|locale|language|languages|country|region|timezone|time_zone|latitude|longitude|lat|lng|lon|altitude|accuracy|location|address|city|province|ssid|wifi_ssid|bssid|ip|ip_address|mac|mac_address|carrier|operator|phone_number|abi|abis|supported_abis)\\\\\"\\s*:\\s*)(\\\\\"(?:(?!\\\\\").)*\\\\\"|[-\\w.:]+)");
    private static final java.util.regex.Pattern COORDINATES=java.util.regex.Pattern.compile("-?\\d{1,3}\\.\\d{3,}\\s*,\\s*-?\\d{1,3}\\.\\d{3,}");
    private static final java.util.regex.Pattern IPV4=java.util.regex.Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
    /** Removes device identity, system version, language/region and location data from logs before they leave the phone. */
    static String scrub(String text){if(text==null)return "";String out=text;
        for(String value:new String[]{Build.MODEL,Build.MANUFACTURER,Build.BRAND,Build.DEVICE,Build.PRODUCT,Build.HARDWARE,Build.BOARD,Build.FINGERPRINT,Build.DISPLAY,Build.ID})
            if(value!=null&&value.length()>=3&&!"unknown".equalsIgnoreCase(value))out=out.replace(value,"[device]");
        out=ESCAPED_FIELDS.matcher(out).replaceAll("$1\\\\\"[removed]\\\\\"");
        out=PRIVATE_FIELDS.matcher(out).replaceAll("$1\"[removed]\"");
        out=COORDINATES.matcher(out).replaceAll("[location]");
        return IPV4.matcher(out).replaceAll("[ip]");}
    /**
     * Our own list of ways to send, so it is never empty: each installed email app, any other app that takes text,
     * and copying the report (the system chooser restricted to mailto: shows nothing on phones without an email app).
     */
    private void chooseRoute(Intent send,String to,String subject,String body){
        android.content.pm.PackageManager pm=getPackageManager();
        java.util.List<CharSequence> labels=new java.util.ArrayList<>();java.util.List<Runnable> actions=new java.util.ArrayList<>();
        java.util.Set<String> seen=new java.util.HashSet<>();
        for(android.content.pm.ResolveInfo r:pm.queryIntentActivities(new Intent(Intent.ACTION_SENDTO,Uri.parse("mailto:")),0)){
            String pkg=r.activityInfo.packageName;if(!seen.add(pkg))continue;
            labels.add(getString(R.string.feedback_via_email,r.loadLabel(pm)));
            actions.add(()->{try{startActivity(new Intent(send).setPackage(pkg));}
                catch(Exception notSend){startActivity(new Intent(Intent.ACTION_SENDTO,Uri.parse("mailto:"+to)).setPackage(pkg).putExtra(Intent.EXTRA_SUBJECT,subject).putExtra(Intent.EXTRA_TEXT,body));}});}
        labels.add(getString(R.string.feedback_via_other));
        actions.add(()->startActivity(Intent.createChooser(new Intent(send),getString(R.string.feedback_send))));
        labels.add(getString(R.string.feedback_via_copy));
        actions.add(()->{android.content.ClipboardManager clip=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            clip.setPrimaryClip(android.content.ClipData.newPlainText(subject,subject+"\n\n"+body));
            Toast.makeText(this,getString(R.string.feedback_copied,to),Toast.LENGTH_LONG).show();});
        new AlertDialog.Builder(this).setTitle(getString(R.string.feedback_to,to))
                .setItems(labels.toArray(new CharSequence[0]),(d,w)->{try{actions.get(w).run();}catch(Exception e){Toast.makeText(this,String.valueOf(e.getMessage()),Toast.LENGTH_LONG).show();}})
                .setNegativeButton(R.string.cancel,null).show();
    }

    private static String firstLine(String s){String t=s.trim().split("\n",2)[0];return t.length()>60?t.substring(0,60)+"…":t;}
}
