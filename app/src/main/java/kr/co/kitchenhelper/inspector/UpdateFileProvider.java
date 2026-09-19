package kr.co.kitchenhelper.inspector;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/** Read-only provider exposing only the downloaded OTA APK to Android's package installer. */
public final class UpdateFileProvider extends ContentProvider {
    public static final String FILE_NAME = "kitchen-helper.apk";

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        if (!"r".equals(mode) || !FILE_NAME.equals(uri.getLastPathSegment())) {
            throw new FileNotFoundException("Unknown update file");
        }
        File file = new File(new File(providerContext().getCacheDir(), "updates"), FILE_NAME);
        if (!file.isFile()) throw new FileNotFoundException("Update has not been downloaded");
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        File file = new File(new File(providerContext().getCacheDir(), "updates"), FILE_NAME);
        MatrixCursor cursor = new MatrixCursor(
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, 1);
        cursor.addRow(new Object[]{FILE_NAME, file.length()});
        return cursor;
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read only");
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }

    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) { return 0; }

    private android.content.Context providerContext() {
        android.content.Context context = getContext();
        if (context == null) throw new IllegalStateException("Provider unavailable");
        return context;
    }
}
