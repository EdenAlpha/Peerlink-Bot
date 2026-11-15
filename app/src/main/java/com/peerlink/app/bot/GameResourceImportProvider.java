package com.peerlink.app.bot;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** One temporary write grant lets the existing shell helper copy a game pack
 * into app-private storage. It never exposes arbitrary files or a read route. */
public final class GameResourceImportProvider extends ContentProvider {
    private static final ConcurrentHashMap<String, File> pending = new ConcurrentHashMap<>();

    public static Uri grant(Context context, File destination) {
        String id = UUID.randomUUID().toString();
        Uri uri = new Uri.Builder().scheme("content")
            .authority(context.getPackageName() + ".bot-resource-import")
            .appendPath("write").appendPath(id).build();
        pending.put(id, destination);
        try {
            context.grantUriPermission("com.android.shell", uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            return uri;
        } catch (RuntimeException error) {
            pending.remove(id);
            throw error;
        }
    }

    public static void revoke(Context context, Uri uri) {
        pending.remove(uri.getLastPathSegment());
        context.revokeUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    }

    @Override public boolean onCreate() { return true; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"w".equals(mode) && !"wt".equals(mode)) throw new FileNotFoundException("Write-only import");
        if (uri.getPathSegments().size() != 2 || !"write".equals(uri.getPathSegments().get(0)))
            throw new FileNotFoundException("Invalid import");
        File file = pending.get(uri.getLastPathSegment());
        if (file == null) throw new FileNotFoundException("Import expired");
        return ParcelFileDescriptor.open(file,
            ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_TRUNCATE);
    }
    @Override public String getType(Uri uri) { return "application/octet-stream"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
}
