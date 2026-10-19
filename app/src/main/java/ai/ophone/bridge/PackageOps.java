package ai.ophone.bridge;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.File;

public final class PackageOps {
    private PackageOps() {}

    public static JSONObject requestInstall(Context context, String fileId) throws Exception {
        JSONObject out = new JSONObject();
        File file = LocalDownloadStore.resolve(context, fileId);
        if (file == null) throw new IllegalArgumentException("unknown_file_id");
        if (!file.getName().toLowerCase().endsWith(".apk")) {
            throw new IllegalArgumentException("file_is_not_apk");
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !context.getPackageManager().canRequestPackageInstalls()) {
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + context.getPackageName()));
            settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(settings);
            out.put("ok", false);
            out.put("requires_user_action", true);
            out.put("action", "enable_unknown_app_installs");
            return out;
        }

        Uri uri = new Uri.Builder()
                .scheme("content")
                .authority(LocalFileProvider.authority(context))
                .appendPath("download")
                .appendPath(file.getName())
                .build();

        Intent install = new Intent(Intent.ACTION_VIEW);
        install.setDataAndType(uri, "application/vnd.android.package-archive");
        install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(install);

        out.put("ok", true);
        out.put("initiated", true);
        out.put("requires_user_confirmation", true);
        out.put("file_id", fileId);
        return out;
    }

    public static JSONObject requestUninstall(Context context, String packageName) throws Exception {
        if (packageName == null || packageName.trim().isEmpty()) {
            throw new IllegalArgumentException("invalid_package");
        }
        if (context.getPackageName().equals(packageName)) {
            throw new IllegalArgumentException("cannot_uninstall_self_from_tool");
        }

        Intent uninstall = new Intent(Intent.ACTION_DELETE,
                Uri.parse("package:" + packageName));
        uninstall.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(uninstall);

        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("initiated", true);
        out.put("requires_user_confirmation", true);
        out.put("package", packageName);
        return out;
    }

    public static JSONObject openUrl(Context context, String url) throws Exception {
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("unsupported_url_scheme");
        }
        Intent i = new Intent(Intent.ACTION_VIEW, uri);
        ResolveInfo resolved = context.getPackageManager().resolveActivity(
                i, PackageManager.MATCH_DEFAULT_ONLY);
        String targetPackage = "";
        if (resolved != null && resolved.activityInfo != null) {
            targetPackage = resolved.activityInfo.packageName;
            if (!AppControlPolicy.isAllowed(context, targetPackage)) {
                throw new SecurityException(
                        "app_control_blocked_by_user: " + targetPackage);
            }
        }
        boolean agentPip = ChatActivity.prepareForExternalAutomation(context);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(i);
        try { Thread.sleep(650L); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("opened", url);
        out.put("agent_pip", agentPip);
        out.put("package", targetPackage);
        DeviceAutomationService automation = DeviceAutomationService.getInstance();
        if (automation != null) {
            String foreground = automation.currentPackageName();
            out.put("foreground_package", foreground);
            out.put("foreground_matches_target", !targetPackage.isEmpty() && targetPackage.equals(foreground));
        }
        return out;
    }
}
