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
        if (file != null && file.getName().toLowerCase().endsWith(".apk")) {
            return "application/vnd.android.package-archive";
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
        if (path == null || !path.startsWith("/download/")) return null;
        String id = Uri.decode(path.substring("/download/".length()));
        return LocalDownloadStore.resolve(getContext(), id);
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
