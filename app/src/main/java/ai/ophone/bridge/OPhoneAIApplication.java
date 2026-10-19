package ai.ophone.bridge;

public final class OPhoneAIApplication extends android.app.Application {
    @Override public void onCreate() {
        super.onCreate();
        if(android.os.Build.VERSION.SDK_INT>=28&&android.app.Application.getProcessName().endsWith(":workbench"))android.webkit.WebView.setDataDirectorySuffix("agent_browser");
        DetailedDiagnostics.install(this);
    }
}
