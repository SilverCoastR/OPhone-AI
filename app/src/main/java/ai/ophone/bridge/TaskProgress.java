package ai.ophone.bridge;

import android.app.Activity;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.os.Bundle;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the AI is doing right now. While a task runs in another app, a full-screen control mask (an accessibility
 * overlay: no extra permission) keeps the user's touches from interfering and shows the current step with a
 * "Take over" button. Taking over removes the mask, pauses phone actions and tells the AI; "Hand back" resumes.
 * The AI's own gestures pass through the mask, and screenshots never show it. When the task ends or needs the user,
 * the app returns to that task's conversation.
 */
final class TaskProgress {
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final long STALE_MS=10*60*1000L;
    private static volatile String taskId="",conversation="",tool="";
    private static volatile int steps;private static volatile long startedAt,lastActivity;private static volatile boolean running,capturing;
    private static final Map<String,String> handled=new ConcurrentHashMap<>();
    private static FrameLayout mask;private static TextView maskStatus;private static WindowManager.LayoutParams maskParams;
    private static TextView handback;private static WindowManager windows;
    private static volatile boolean userControl;private static volatile long passUntil;
    private static final Runnable TICK=new Runnable(){@Override public void run(){render();if(running)MAIN.postDelayed(this,1000);}};

    private TaskProgress(){}

    // OPhone AI is on screen when one of its activities is resumed (a picture-in-picture window is paused).
    private static volatile int resumed;private static volatile boolean tracking;
    static void track(Application app){if(tracking)return;tracking=true;app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks(){
        public void onActivityResumed(Activity a){resumed++;cancelAttention(a);MAIN.post(TaskProgress::render);}
        public void onActivityPaused(Activity a){resumed=Math.max(0,resumed-1);MAIN.post(TaskProgress::render);}
        public void onActivityCreated(Activity a,Bundle b){}public void onActivityStarted(Activity a){}public void onActivityStopped(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}});}
    static boolean appOnScreen(){return resumed>0;}

    /** The AI is done with other apps (return_to_chat tool): show its conversation now, not only when the task ends. */
    static JSONObject bringChatToFront(Context context,String conversation)throws Exception{
        if(appOnScreen())return new JSONObject().put("ok",true).put("already_in_front",true);
        returnToConversation(context,conversation);
        return new JSONObject().put("ok",true).put("returning",true).put("note","OPhone AI's chat is being brought to the front. Further phone actions will open other apps again only if you call them.");
    }

    /** For callers outside the task store (the ChatGPT page runtime giving up): bring the user back to that conversation. */
    static void returnToConversation(Context context,String conversation){
        try{JSONObject task=new JSONObject().put("id","").put("status","blocked").put("conversation",conversation==null?"":conversation);
            Context app=context.getApplicationContext();MAIN.post(()->returnToChat(app,task));}catch(Exception ignored){}
    }

    static void toolStarted(Context context,JSONObject scope,String name){
        if(scope==null||scope.optString("task_id").isEmpty())return;String id=scope.optString("task_id");long now=System.currentTimeMillis();
        if(!id.equals(taskId)){taskId=id;conversation=scope.optString("conversation");steps=0;startedAt=now;}
        steps++;tool=name==null?"":name;lastActivity=now;start();
    }
    static void toolFinished(JSONObject scope){if(scope!=null&&scope.optString("task_id").equals(taskId)){tool="";lastActivity=System.currentTimeMillis();MAIN.post(TaskProgress::render);}}

    /** Called on every task save; acts once per task and status. */
    static void taskChanged(Context context,JSONObject task){
        String id=task.optString("id"),status=task.optString("status");
        boolean ended=TaskController.terminal(status),needsUser="waiting_user".equals(status);
        if(id.equals(taskId)&&(ended||needsUser)){running=false;tool="";userControl=false;MAIN.post(TaskProgress::render);}
        if(!(ended||needsUser)||"cancelled".equals(status))return;
        if(status.equals(handled.put(id,status)))return;
        if(handled.size()>64)handled.clear();
        Context app=context.getApplicationContext();
        MAIN.postDelayed(()->returnToChat(app,task),1500);
    }

    static boolean running(){return running&&System.currentTimeMillis()-lastActivity<STALE_MS;}
    /** One line for the notification and the overlay. */
    static String statusLine(Context context){
        String time=clock(System.currentTimeMillis()-startedAt);
        return tool.isEmpty()?context.getString(R.string.task_progress_thinking,steps,time):context.getString(R.string.task_progress_tool,label(tool),steps,time);
    }

    /** Screenshots must show the phone, not the mask. */
    static void hideForCapture(boolean hide){capturing=hide;MAIN.post(TaskProgress::render);}
    static boolean visible(){return (mask!=null&&mask.getVisibility()==View.VISIBLE)||(handback!=null&&handback.getVisibility()==View.VISIBLE);}
    /** The user pressed "Take over": phone actions are refused until they hand control back. */
    static boolean userControl(){return userControl;}

    /** The AI's gesture must reach the app under the mask: make the mask non-touchable for the gesture's duration. */
    static void passThrough(long durationMs){
        if(mask==null)return;passUntil=System.currentTimeMillis()+durationMs+250;CountDownLatch done=new CountDownLatch(1);
        MAIN.post(()->{setTouchable(false);done.countDown();});
        try{done.await(300,TimeUnit.MILLISECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
        MAIN.postDelayed(()->{if(System.currentTimeMillis()>=passUntil)setTouchable(true);},durationMs+260);
    }
    private static void setTouchable(boolean touchable){
        if(mask==null||windows==null||maskParams==null)return;
        int flags=touchable?maskParams.flags&~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE:maskParams.flags|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        if(flags==maskParams.flags)return;maskParams.flags=flags;try{windows.updateViewLayout(mask,maskParams);}catch(Exception ignored){}
    }

    private static void takeOver(Context context){
        userControl=true;MAIN.post(TaskProgress::render);
        tellAi(context,context.getString(R.string.takeover_ai_message));
        log(context,"user_takeover","The user pressed Take over; phone actions are paused until they hand control back.");
    }
    private static void handBack(Context context){
        userControl=false;MAIN.post(TaskProgress::render);
        tellAi(context,context.getString(R.string.handback_ai_message));
        log(context,"user_handback","The user handed control back to the AI.");
    }
    /** Delivered at the AI's next step: as guidance to a running API agent, or through the ChatGPT page runtime. */
    private static void tellAi(Context context,String text){
        String conv=conversation;
        if(conv.startsWith("c:api."))DirectModelAgent.systemGuidance(conv,text);else ChatActivity.systemGuidance(text);
    }
    private static void log(Context context,String code,String message){try{AgentStore.get(context).addLog("info","task",taskId,"",code,message,"");}catch(Exception ignored){}}

    private static void start(){running=true;MAIN.removeCallbacks(TICK);MAIN.post(TICK);}

    private static void render(){
        DeviceAutomationService service=DeviceAutomationService.getInstance();
        boolean away=service!=null&&running()&&!capturing&&!appOnScreen()&&!service.getPackageName().equals(service.currentPackageName());
        boolean showMask=away&&!userControl,showHandback=away&&userControl;
        if(showMask&&mask==null)addMask(service);
        if(showHandback&&handback==null)addHandback(service);
        if(mask!=null){mask.setVisibility(showMask?View.VISIBLE:View.GONE);if(showMask)maskStatus.setText(statusLine(service));}
        if(handback!=null)handback.setVisibility(showHandback?View.VISIBLE:View.GONE);
        if(!running())remove();
    }
    private static GradientDrawable round(int color,float radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(radius);return g;}
    private static void addMask(DeviceAutomationService service){
        if(Build.VERSION.SDK_INT<22)return;
        try{windows=(WindowManager)service.getSystemService(Context.WINDOW_SERVICE);float d=service.getResources().getDisplayMetrics().density;
            FrameLayout root=new FrameLayout(service);
            GradientDrawable frame=new GradientDrawable();frame.setColor(0x1A000000);frame.setStroke((int)(3*d),0xCC0A84FF);root.setBackground(frame);
            // Every touch outside the button is absorbed, so the user cannot disturb the AI by accident.
            root.setOnTouchListener((v,e)->true);
            LinearLayout bar=new LinearLayout(service);bar.setOrientation(LinearLayout.HORIZONTAL);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setBackground(round(0xF21C1C1E,26*d));
            bar.setPadding((int)(18*d),(int)(10*d),(int)(10*d),(int)(10*d));
            TextView status=new TextView(service);status.setTextColor(0xFFFFFFFF);status.setTextSize(13.5f);status.setMaxLines(2);
            bar.addView(status,new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f));
            TextView take=new TextView(service);take.setText(R.string.takeover_button);take.setTextColor(0xFF000000);take.setTextSize(15f);take.setTypeface(Typeface.DEFAULT_BOLD);
            take.setBackground(round(0xFFFFFFFF,18*d));take.setPadding((int)(18*d),(int)(9*d),(int)(18*d),(int)(9*d));take.setOnClickListener(v->takeOver(service));
            LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,LinearLayout.LayoutParams.WRAP_CONTENT);tp.leftMargin=(int)(12*d);bar.addView(take,tp);
            FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.WRAP_CONTENT,Gravity.BOTTOM);bp.setMargins((int)(14*d),0,(int)(14*d),(int)(44*d));root.addView(bar,bp);
            WindowManager.LayoutParams lp=new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.TRANSLUCENT);
            windows.addView(root,lp);mask=root;maskStatus=status;maskParams=lp;
        }catch(Exception e){DetailedDiagnostics.event(service,"warning","task","","","task_mask_failed","Unable to show the AI control mask.",e);mask=null;}
    }
    private static void addHandback(DeviceAutomationService service){
        if(Build.VERSION.SDK_INT<22)return;
        try{if(windows==null)windows=(WindowManager)service.getSystemService(Context.WINDOW_SERVICE);float d=service.getResources().getDisplayMetrics().density;
            TextView view=new TextView(service);view.setText(R.string.handback_button);view.setTextSize(13.5f);view.setTextColor(0xFFFFFFFF);view.setTypeface(Typeface.DEFAULT_BOLD);
            view.setPadding((int)(16*d),(int)(9*d),(int)(16*d),(int)(9*d));view.setBackground(round(0xF20A84FF,20*d));view.setOnClickListener(v->handBack(service));
            WindowManager.LayoutParams lp=new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
            lp.gravity=Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL;lp.y=(int)(44*d);windows.addView(view,lp);handback=view;
        }catch(Exception e){DetailedDiagnostics.event(service,"warning","task","","","task_handback_failed","Unable to show the hand-back button.",e);handback=null;}
    }
    private static void remove(){
        if(windows!=null){if(mask!=null)try{windows.removeView(mask);}catch(Exception ignored){}if(handback!=null)try{windows.removeView(handback);}catch(Exception ignored){}}
        mask=null;maskStatus=null;maskParams=null;handback=null;userControl=false;
    }

    /** Back to the conversation that owns the task: the API chat with that conversation selected, or that ChatGPT conversation. */
    private static void returnToChat(Context context,JSONObject task){
        try{if(!task.optString("id").isEmpty()){JSONObject current=AgentStore.get(context).task(task.optString("id"));if(current!=null&&!current.optString("status").equals(task.optString("status")))return;}}catch(Exception ignored){}
        DeviceAutomationService service=DeviceAutomationService.getInstance();
        if(appOnScreen())return;
        String conv=task.optString("conversation");Intent intent;
        if(conv.startsWith("c:api.")){try{new ApiConversationStore(context).select(conv);}catch(Exception ignored){}intent=new Intent(context,ApiChatActivity.class);}
        else{intent=new Intent(context,ChatActivity.class);if(conv.startsWith("c:")&&!conv.startsWith("c:WEB:"))intent.putExtra("url","https://chatgpt.com/c/"+Uri.encode(conv.substring(2)));}
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        // The bound accessibility service may start activities from the background; the app context is the fallback.
        try{(service!=null?service:context).startActivity(intent);}catch(Exception e){DetailedDiagnostics.event(context,"warning","task",task.optString("id"),"","task_return_failed","Unable to return to the conversation after the task ended.",e);}
        // Without the accessibility service Android (and MIUI's "pop up in background" switch) may block that start
        // silently. A heads-up notification is the way back; it is cancelled as soon as OPhone AI is on screen.
        if(service==null||!ChatActivity.isAutomationInPictureInPicture()&&!ApiChatActivity.inPictureInPicture())MAIN.postDelayed(()->{if(!appOnScreen())notifyAttention(context,task,intent);},1200);
    }

    private static final int ATTENTION_ID=7302;private static final String ATTENTION_CHANNEL="task_attention";
    private static void notifyAttention(Context context,JSONObject task,Intent intent){try{
        NotificationManager nm=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
        if(Build.VERSION.SDK_INT>=26)nm.createNotificationChannel(new NotificationChannel(ATTENTION_CHANNEL,context.getString(R.string.task_attention_channel),NotificationManager.IMPORTANCE_HIGH));
        String status=task.optString("status");int title="succeeded".equals(status)?R.string.task_attention_done:"waiting_user".equals(status)?R.string.task_attention_question:R.string.task_attention_blocked;
        PendingIntent open=PendingIntent.getActivity(context,ATTENTION_ID,intent,PendingIntent.FLAG_UPDATE_CURRENT|(Build.VERSION.SDK_INT>=23?PendingIntent.FLAG_IMMUTABLE:0));
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(context,ATTENTION_CHANNEL):new Notification.Builder(context).setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL);
        nm.notify(ATTENTION_ID,b.setSmallIcon(android.R.drawable.stat_notify_more).setContentTitle(context.getString(title)).setContentText(context.getString(R.string.task_attention_tap))
                .setContentIntent(open).setAutoCancel(true).setCategory(Notification.CATEGORY_STATUS).build());
    }catch(Exception e){DetailedDiagnostics.event(context,"warning","task","","","task_attention_notify_failed","Unable to post the task attention notification.",e);}}
    static void cancelAttention(Context context){try{((NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(ATTENTION_ID);}catch(Exception ignored){}}

    private static String clock(long ms){long s=Math.max(0,ms/1000);return String.format(Locale.ROOT,"%d:%02d",s/60,s%60);}
    private static final Map<String,String> ZH=new HashMap<>();
    static{String[] pairs={"launch_app","打开应用","get_ui","读取界面","screenshot","截图","tap","点击","click_text","点击文字","input_text","输入文字","swipe","滑动","press_global","系统按键",
            "find_apps","查找应用","wait_for_ui","等待界面","download_file","下载文件","install_apk","安装应用","open_url","打开链接","python_run","运行 Python","terminal_start","运行命令",
            "terminal_status","读取命令输出","browser_open","打开网页","browser_action","操作网页","browser_snapshot","读取网页","http_request","网络请求","task_verify","验证结果","task_status","查看任务",
            "task_finish","完成任务","task_set_criteria","设定验收标准","capabilities","检查能力","memory_search","查找经验","memory_save","保存经验","android_intent","调用系统功能","android_info","读取系统状态",
            "android_control","系统控制","ask_user","询问你","miniapp_save","保存小应用","miniapp_open","打开小应用","workbench_file","读写文件"};
        for(int i=0;i+1<pairs.length;i+=2)ZH.put(pairs[i],pairs[i+1]);}
    private static String label(String name){return Locale.getDefault().getLanguage().startsWith("zh")&&ZH.containsKey(name)?ZH.get(name):name.replace('_',' ');}
}
