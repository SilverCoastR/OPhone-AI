package ai.ophone.bridge;

public final class OPhoneAIApplication extends android.app.Application {
    @Override public void onCreate() {
        super.onCreate();
        if(android.os.Build.VERSION.SDK_INT>=28&&android.app.Application.getProcessName().endsWith(":workbench"))android.webkit.WebView.setDataDirectorySuffix("agent_browser");
        DetailedDiagnostics.install(this);
        // Every screen, in every process: no black letterbox beside the display cutout in landscape.
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks(){
            public void onActivityCreated(android.app.Activity a,android.os.Bundle b){UiKit.fitCutout(a);}
            public void onActivityStarted(android.app.Activity a){}public void onActivityResumed(android.app.Activity a){UiKit.immersive(a);}public void onActivityPaused(android.app.Activity a){}
            public void onActivityStopped(android.app.Activity a){}public void onActivitySaveInstanceState(android.app.Activity a,android.os.Bundle b){}public void onActivityDestroyed(android.app.Activity a){}});
        TaskProgress.track(this);
    }
}
