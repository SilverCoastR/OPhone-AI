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
    static JSONObject bringChatToFront(Context context,String conversation,String task)throws Exception{
        if(appOnScreen())return new JSONObject().put("ok",true).put("already_in_front",true);
        String block=foregroundBlock(context,task);logForeground(context,task,"return_to_chat",block);
        if(!block.isEmpty())return new JSONObject().put("ok",true).put("returning",false).put("reason",block)
                .put("note","OPhone AI was not brought to the front ("+block+"): the user is not looking at an app you operated. Do not call return_to_chat again; your reply appears in the chat and the user is notified when the task ends.");
        returnToConversation(context,conversation);
        return new JSONObject().put("ok",true).put("returning",true).put("note","OPhone AI's chat is being brought to the front. Further phone actions will open other apps again only if you call them.");
    }

    /** The ChatGPT page runtime gave up while the user was away: back to that chat only if the AI holds the screen. */
    static void runtimeGaveUp(Context context,String conversation){
        String task=taskId,block=foregroundBlock(context,task);logForeground(context,task,"chatgpt_runtime_failed",block);
        try{JSONObject t=new JSONObject().put("id","").put("status","blocked").put("conversation",conversation==null?"":conversation);
            if(block.isEmpty())returnToConversation(context,conversation);
            else if(!"already_on_screen".equals(block)){Context app=context.getApplicationContext();MAIN.post(()->notifyAttention(app,t,chatIntent(app,t)));}}catch(Exception ignored){}
    }
    /** Brings the user back to that conversation; callers have checked foregroundBlock. */
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

    /* ---------- when OPhone AI may come to the front by itself ----------
     * Only to return the user from an app the AI itself was operating for that task: the AI acted outside OPhone AI
     * for this task in this session, recently; the app on screen is still the one the AI was operating (the user has
     * not gone elsewhere); the user has not taken over; the screen is on and unlocked. Otherwise a notification is
     * the way back. Every decision is written to the diagnostics log. */
    private static volatile String aiTask="",aiPackage="";private static volatile long aiAt;
    private static final long AI_RECENT_MS=15*60*1000L;
    /** After each phone action of a task: the app the AI is operating now (not OPhone AI itself). */
    static void noteAiAction(Context context,JSONObject scope,String name){
        if(scope==null||scope.optString("task_id").isEmpty()||name==null)return;
        if(!TaskController.isMutation(name)&&!"get_ui".equals(name)&&!"screenshot".equals(name)&&!"wait_for_ui".equals(name))return;
        DeviceAutomationService service=DeviceAutomationService.getInstance();if(service==null)return;
        String pkg=service.currentPackageName();if(pkg==null||pkg.isEmpty()||pkg.equals(context.getPackageName()))return;
        aiTask=scope.optString("task_id");aiPackage=pkg;aiAt=System.currentTimeMillis();
    }
    /** Why OPhone AI must not come to the front now, or "" when it may. */
    static String foregroundBlock(Context context,String task){
        if(appOnScreen())return "already_on_screen";
        if(userControl)return "user_took_over";
        try{android.os.PowerManager pm=(android.os.PowerManager)context.getSystemService(Context.POWER_SERVICE);if(pm!=null&&!pm.isInteractive())return "screen_off";
            android.app.KeyguardManager km=(android.app.KeyguardManager)context.getSystemService(Context.KEYGUARD_SERVICE);if(km!=null&&km.isKeyguardLocked())return "phone_locked";}catch(Exception ignored){}
        if(task==null||task.isEmpty()||!task.equals(aiTask))return "ai_did_not_leave_ophone_for_this_task";
        if(System.currentTimeMillis()-aiAt>AI_RECENT_MS)return "ai_action_too_old";
        DeviceAutomationService service=DeviceAutomationService.getInstance();
        String now=service==null?"":service.currentPackageName();
        if(service==null)return "accessibility_unavailable";
        if(!aiPackage.equals(now))return "user_switched_to_another_app";
        return "";
    }
    /** The AI is operating the phone right now (its app is on screen): OPhone AI's own screens may be shown for the task. */
    static boolean aiControlsScreen(Context context,String task){
        if(appOnScreen())return true;
        String block=foregroundBlock(context,task);
        return block.isEmpty()&&System.currentTimeMillis()-aiAt<2*60*1000L;
    }
    private static void logForeground(Context context,String task,String why,String block){
        DetailedDiagnostics.event(context,"info","foreground",task==null?"":task,"",block.isEmpty()?"foreground_return":"foreground_return_skipped",why+(block.isEmpty()?"":": "+block),null);
    }

    /** Called on every task save; acts once per task and status. */
    static void taskChanged(Context context,JSONObject task){
        String id=task.optString("id"),status=task.optString("status");
        boolean ended=TaskController.terminal(status),needsUser="waiting_user".equals(status);
        if(id.equals(taskId)&&(ended||needsUser)){running=false;tool="";userControl=false;MAIN.post(TaskProgress::render);}
        if(!(ended||needsUser)||"cancelled".equals(status))return;
        // Only tasks that worked in this session; a task saved again later (restart, cleanup) never brings anything up.
        if(!id.equals(taskId)&&!id.equals(aiTask))return;
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
    private static Intent chatIntent(Context context,JSONObject task){
        String conv=task.optString("conversation");Intent intent;
        if(conv.startsWith("c:api."))intent=new Intent(context,ApiChatActivity.class);
        else{intent=new Intent(context,ChatActivity.class);if(conv.startsWith("c:")&&!conv.startsWith("c:WEB:"))intent.putExtra("url","https://chatgpt.com/c/"+Uri.encode(conv.substring(2)));}
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
    }
    private static void returnToChat(Context context,JSONObject task){
        try{if(!task.optString("id").isEmpty()){JSONObject current=AgentStore.get(context).task(task.optString("id"));if(current!=null&&!current.optString("status").equals(task.optString("status")))return;}}catch(Exception ignored){}
        DeviceAutomationService service=DeviceAutomationService.getInstance();
        if(appOnScreen())return;
        String block=task.optString("id").isEmpty()?"":foregroundBlock(context,task.optString("id"));
        if(!task.optString("id").isEmpty())logForeground(context,task.optString("id"),"task_"+task.optString("status"),block);
        Intent intent=chatIntent(context,task);
        // Not appropriate to come to the front: the notification is the way back (it opens the conversation).
        if(!block.isEmpty()){notifyAttention(context,task,intent);return;}
        String conv=task.optString("conversation");
        if(conv.startsWith("c:api."))try{new ApiConversationStore(context).select(conv);}catch(Exception ignored){}
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
    // Tool names in the task notification, in the phone's language (zh, ja, ko); other languages show the tool id.
    private static final Map<String,Map<String,String>> LABELS=new HashMap<>();
    private static void labels(String lang,String... pairs){Map<String,String> m=new HashMap<>();for(int i=0;i+1<pairs.length;i+=2)m.put(pairs[i],pairs[i+1]);LABELS.put(lang,m);}
    static{
        labels("zh","launch_app","打开应用","get_ui","读取界面","screenshot","截图","tap","点击","click_text","点击文字","input_text","输入文字","swipe","滑动","press_global","系统按键",
            "find_apps","查找应用","wait_for_ui","等待界面","download_file","下载文件","install_apk","安装应用","open_url","打开链接","python_run","运行 Python","terminal_start","运行命令",
            "terminal_status","读取命令输出","browser_open","打开网页","browser_action","操作网页","browser_snapshot","读取网页","http_request","网络请求","task_verify","验证结果","task_status","查看任务",
            "task_finish","完成任务","task_set_criteria","设定验收标准","capabilities","检查能力","memory_search","查找经验","memory_save","保存经验","android_intent","调用系统功能","android_info","读取系统状态",
            "android_control","系统控制","ask_user","询问你","miniapp_save","保存小应用","miniapp_open","打开小应用","workbench_file","读写文件");
        labels("ja","launch_app","アプリを開く","get_ui","画面を読み取る","screenshot","スクリーンショット","tap","タップ","click_text","文字をタップ","input_text","文字を入力","swipe","スワイプ","press_global","システムキー",
            "find_apps","アプリを探す","wait_for_ui","画面を待つ","download_file","ダウンロード","install_apk","アプリをインストール","open_url","リンクを開く","python_run","Python 実行","terminal_start","コマンド実行",
            "terminal_status","コマンド出力を読む","browser_open","ページを開く","browser_action","ページを操作","browser_snapshot","ページを読む","http_request","HTTP リクエスト","task_verify","結果を検証","task_status","タスク状況",
            "task_finish","タスク完了","task_set_criteria","完了条件を設定","capabilities","機能を確認","memory_search","記憶を検索","memory_save","記憶する","android_intent","システム機能","android_info","システム状態",
            "android_control","デバイス操作","ask_user","あなたに質問","miniapp_save","ミニアプリ保存","miniapp_open","ミニアプリを開く","workbench_file","ファイル");
        labels("ko","launch_app","앱 열기","get_ui","화면 읽기","screenshot","스크린샷","tap","탭","click_text","텍스트 탭","input_text","텍스트 입력","swipe","스와이프","press_global","시스템 키",
            "find_apps","앱 찾기","wait_for_ui","화면 대기","download_file","다운로드","install_apk","앱 설치","open_url","링크 열기","python_run","Python 실행","terminal_start","명령 실행",
            "terminal_status","명령 출력 읽기","browser_open","페이지 열기","browser_action","페이지 조작","browser_snapshot","페이지 읽기","http_request","HTTP 요청","task_verify","결과 검증","task_status","작업 상태",
            "task_finish","작업 완료","task_set_criteria","완료 조건 설정","capabilities","기능 확인","memory_search","기억 찾기","memory_save","기억하기","android_intent","시스템 기능","android_info","시스템 상태",
            "android_control","기기 제어","ask_user","질문하기","miniapp_save","미니 앱 저장","miniapp_open","미니 앱 열기","workbench_file","파일");
    }
    static String label(String name){Map<String,String> m=LABELS.get(UiKit.language());return m!=null&&m.containsKey(name)?m.get(name):name.replace('_',' ');}
}
