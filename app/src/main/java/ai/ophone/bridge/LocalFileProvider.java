package ai.ophone.bridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

public class LocalFileProvider extends ContentProvider {
    public static String authority(android.content.Context context) {
        return context.getPackageName() + ".files";
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        File file = resolve(uri);
        if (file != null && file.getName().toLowerCase().endsWith(".zip")) return "application/zip";
        if (file != null && file.getName().toLowerCase().endsWith(".apk")) {
            return "application/vnd.android.package-archive";
        }
        if (file != null) {
            String ext = android.webkit.MimeTypeMap.getFileExtensionFromUrl(Uri.fromFile(file).toString());
            String mime = ext == null ? null : android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase());
            if (mime != null) return mime;
        }
        return "application/octet-stream";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = resolve(uri);
        if (file == null) throw new FileNotFoundException("file_not_found");
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File file = resolve(uri);
        if (file == null) return null;
        MatrixCursor c = new MatrixCursor(new String[]{
                OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE
        });
        c.addRow(new Object[]{file.getName(), file.length()});
        return c;
    }

    private File resolve(Uri uri) {
        if (getContext() == null || uri == null) return null;
        if (!authority(getContext()).equals(uri.getAuthority())) return null;
        String path = uri.getPath();
        if (path != null && path.startsWith("/workspace/")) return workspaceFile(getContext(), Uri.decode(path.substring("/workspace/".length())));
        if (path != null && path.startsWith("/update/")) return UpdateManager.updateFile(getContext(), Uri.decode(path.substring("/update/".length())));
        if (path != null && path.startsWith("/feedback/")) {
            File f = new File(new File(getContext().getCacheDir(), "feedback"), Uri.decode(path.substring("/feedback/".length())).replaceAll("[^A-Za-z0-9._-]", "_"));
            return f.isFile() ? f : null;
        }
        if (path == null || !path.startsWith("/download/")) return null;
        String id = Uri.decode(path.substring("/download/".length()));
        return LocalDownloadStore.resolve(getContext(), id);
    }

    /** Workspace files are reachable only through a per-intent URI grant made by android_intent(share_file). */
    static File workspaceFile(android.content.Context context, String relative) {
        try {
            File root = new File(context.getFilesDir(), "workbench_workspace").getCanonicalFile();
            File f = new File(root, relative).getCanonicalFile();
            return f.getPath().startsWith(root.getPath() + File.separator) && f.isFile() ? f : null;
        } catch (Exception e) { return null; }
    }
    static Uri workspaceUri(android.content.Context context, String relative) {
        return new Uri.Builder().scheme("content").authority(authority(context)).appendPath("workspace").appendEncodedPath(Uri.encode(relative, "/")).build();
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
