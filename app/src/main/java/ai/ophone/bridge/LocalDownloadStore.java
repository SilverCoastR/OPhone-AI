package ai.ophone.bridge;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

public final class LocalDownloadStore {
    private LocalDownloadStore() {}

    public static JSONObject download(Context context, String urlText,
                                      String requestedName, long maxBytes) throws Exception {
        URL url = new URL(urlText);
        String scheme = url.getProtocol() == null ? "" : url.getProtocol().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("unsupported_url_scheme");
        }

        long limit = Math.max(1024L * 1024L,
                Math.min(1024L * 1024L * 1024L, maxBytes <= 0 ? 256L * 1024L * 1024L : maxBytes));

        File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) dir = new File(context.getFilesDir(), "downloads");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("download_directory_unavailable");
        }

        String name = sanitizeName(requestedName);
        if (name.isEmpty()) {
            String lastSegment = Uri.parse(urlText).getLastPathSegment();
            name = sanitizeName(lastSegment == null ? "" : lastSegment);
        }
        if (name.isEmpty()) name = "download-" + System.currentTimeMillis() + ".bin";

        File outFile = uniqueFile(dir, name);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "OPhoneAI/1.0");
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("download_http_" + code);
        }

        long contentLength = c.getContentLengthLong();
        if (contentLength > limit) {
            throw new IllegalStateException("download_too_large");
        }

        InputStream in = c.getInputStream();
        FileOutputStream out = new FileOutputStream(outFile);
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        try {
            int n;
            while ((n = in.read(buf)) >= 0) {
                total += n;
                if (total > limit) throw new IllegalStateException("download_too_large");
                out.write(buf, 0, n);
            }
            out.flush();
        } catch (Exception e) {
            outFile.delete();
            throw e;
        } finally {
            try { in.close(); } catch (Exception ignored) {}
            try { out.close(); } catch (Exception ignored) {}
            c.disconnect();
        }

        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("file_id", outFile.getName());
        result.put("filename", outFile.getName());
        result.put("bytes", total);
        result.put("path", outFile.getAbsolutePath());
        return result;
    }

    public static File resolve(Context context, String fileId) {
        if (fileId == null) return null;
        String safe = sanitizeName(fileId);
        if (safe.isEmpty() || !safe.equals(fileId)) return null;

        File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) dir = new File(context.getFilesDir(), "downloads");
        File file = new File(dir, safe);
        try {
            String root = dir.getCanonicalPath() + File.separator;
            String child = file.getCanonicalPath();
            if (!child.startsWith(root)) return null;
        } catch (Exception e) {
            return null;
        }
        return file.exists() && file.isFile() ? file : null;
    }

    private static String sanitizeName(String name) {
        if (name == null) return "";
        String clean = name.replaceAll("[^\\p{L}\\p{N}._() \\-]", "_").trim();
        while (clean.startsWith(".")) clean = clean.substring(1);
        if (clean.length() > 120) clean = clean.substring(clean.length() - 120);
        return clean;
    }

    private static File uniqueFile(File dir, String name) {
        File file = new File(dir, name);
        if (!file.exists()) return file;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 10000; i++) {
            file = new File(dir, base + "-" + i + ext);
            if (!file.exists()) return file;
        }
        return new File(dir, base + "-" + System.currentTimeMillis() + ext);
    }
}
