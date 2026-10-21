package ai.ophone.bridge;

public final class OPhoneAIApplication extends android.app.Application {
    private final java.util.Map<android.app.Activity,String> themes=new java.util.WeakHashMap<>();
    private static String processName(){try(java.io.BufferedReader r=new java.io.BufferedReader(new java.io.FileReader("/proc/self/cmdline"))){String s=r.readLine();return s==null?"":s.trim().replace("\u0000","");}catch(Exception e){return "";}}
    @Override public void onCreate() {
        super.onCreate();
        if(android.os.Build.VERSION.SDK_INT>=28&&android.app.Application.getProcessName().endsWith(":workbench"))android.webkit.WebView.setDataDirectorySuffix("agent_browser");
        DetailedDiagnostics.install(this);
        // Every screen, in every process: no black letterbox beside the display cutout in landscape.
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks(){
            public void onActivityCreated(android.app.Activity a,android.os.Bundle b){UiKit.fitCutout(a);themes.put(a,UiKit.themeMode(a));}
            public void onActivityStarted(android.app.Activity a){}
            public void onActivityResumed(android.app.Activity a){UiKit.immersive(a);
                // Night mode changed in Settings while this screen was open: rebuild it in the new mode.
                String applied=themes.get(a);if(applied!=null&&!applied.equals(UiKit.reloadThemeMode(a))){themes.remove(a);a.recreate();}}public void onActivityPaused(android.app.Activity a){}
            public void onActivityStopped(android.app.Activity a){}public void onActivitySaveInstanceState(android.app.Activity a,android.os.Bundle b){}public void onActivityDestroyed(android.app.Activity a){}});
        TaskProgress.track(this);
        if(getPackageName().equals(processName())){PhoneEvents.install(this);new Thread(()->{UpdateManager.reconcileInstalled(this);ScheduleStore.arm(this);},"OPhoneAI-update-state").start();}
    }
}
