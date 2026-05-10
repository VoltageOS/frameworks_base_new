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
        if (GosCompatContract.METHOD_RUN_MAPS_SCAN_CHECK.equals(method)
                || GosCompatContract.METHOD_RUN_DIRECT_MAPS_SCAN_CHECK.equals(method)) {
            return MapsScanRunner.runDirect().toBundle();
        }
        if (GosCompatContract.METHOD_RUN_REFLECTIVE_MAPS_SCAN_CHECK.equals(method)) {
            return MapsScanRunner.runReflective().toBundle();
        }
        if (GosCompatContract.METHOD_GET_MAPS_SCAN_RESULT.equals(method)) {
            return MapsScanResultStore.load(getContext(), arg);
        }
        if (GosCompatContract.METHOD_CLEAR_MAPS_SCAN_RESULT.equals(method)) {
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
