package com.yfuse.cutoutprobe;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/** Only the completed report ZIP can be shared; arbitrary app paths are never exposed. */
public final class ReportProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    private File file(Uri uri) throws FileNotFoundException {
        if (!uri.toString().equals("content://com.yfuse.cutoutprobe.report/report.zip")) {
            throw new FileNotFoundException("Unknown report");
        }
        return new File(getContext().getCacheDir(), "mlkit-probe-report.zip");
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!mode.equals("r")) throw new FileNotFoundException("Read only");
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) { return "application/zip"; }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        try {
            File report = file(uri);
            String[] columns = projection == null
                    ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            MatrixCursor cursor = new MatrixCursor(columns);
            MatrixCursor.RowBuilder row = cursor.newRow();
            for (String column : columns) {
                row.add(column.equals(OpenableColumns.DISPLAY_NAME) ? "mlkit-probe-report.zip"
                        : column.equals(OpenableColumns.SIZE) ? report.length() : null);
            }
            return cursor;
        } catch (FileNotFoundException exception) {
            return null;
        }
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
