package app.grapheneos.goscompat.checks;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

public final class MapsScanProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (GosCompatContract.MapsScan.Method.RUN_CHECK.equals(method)
                || GosCompatContract.MapsScan.Method.RUN_DIRECT_CHECK.equals(method)) {
            return MapsScanRunner.runDirect().toBundle();
        }
        if (GosCompatContract.MapsScan.Method.RUN_REFLECTIVE_CHECK.equals(method)) {
            return MapsScanRunner.runReflective().toBundle();
        }
        if (GosCompatContract.MapsScan.Method.GET_RESULT.equals(method)) {
            return MapsScanResultStore.load(getContext(), arg);
        }
        if (GosCompatContract.MapsScan.Method.CLEAR_RESULT.equals(method)) {
            return MapsScanResultStore.clear(getContext());
        }
        return super.call(method, arg, extras);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
