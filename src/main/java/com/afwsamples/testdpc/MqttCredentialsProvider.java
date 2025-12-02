package com.afwsamples.testdpc;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Process;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Minimal provider exposing enrol credentials to the MQTT core app. */
public class MqttCredentialsProvider extends ContentProvider {

  public static final String AUTHORITY = "com.afwsamples.testdpc.mqttcredentials";
  public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/credentials");

  private static final String TAG = "MqttCredentialsProvider";
  private static final String[] COLUMNS = new String[] {"device_id", "mqtt_password"};
  private static final String ALLOWED_PACKAGE = "com.qubit.mqttcore";

  @Override
  public boolean onCreate() {
    return true;
  }

  @Nullable
  @Override
  public Cursor query(
      @NonNull Uri uri,
      @Nullable String[] projection,
      @Nullable String selection,
      @Nullable String[] selectionArgs,
      @Nullable String sortOrder) {
    if (!isCredentialsPath(uri)) {
      Log.w(TAG, "Rejecting unknown path: " + uri);
      return null;
    }
    // Allow all callers; consumer is expected to protect transport/storage.
    Context context = getContext();
    if (context == null) {
      return null;
    }
    EnrolState state = new EnrolState(context);
    MatrixCursor cursor = new MatrixCursor(COLUMNS, 1);
    cursor.addRow(new Object[] {state.getDeviceId(), state.getMqttPassword()});
    return cursor;
  }

  @Nullable
  @Override
  public String getType(@NonNull Uri uri) {
    return "vnd.android.cursor.item/vnd.com.qubit.mqttcredentials";
  }

  @Nullable
  @Override
  public Uri insert(@NonNull Uri uri, @Nullable ContentValues contentValues) {
    return null;
  }

  @Override
  public int delete(
      @NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
    return 0;
  }

  @Override
  public int update(
      @NonNull Uri uri,
      @Nullable ContentValues values,
      @Nullable String selection,
      @Nullable String[] selectionArgs) {
    return 0;
  }

  private boolean isCredentialsPath(Uri uri) {
    return AUTHORITY.equals(uri.getAuthority()) && CONTENT_URI.getPath().equals(uri.getPath());
  }

  private boolean isCallerAllowed() {
    Context context = getContext();
    if (context == null) {
      return false;
    }
    int callingUid = Binder.getCallingUid();
    if (callingUid == Process.myUid()) {
      return true;
    }
    PackageManager pm = context.getPackageManager();
    String[] packages = pm.getPackagesForUid(callingUid);
    if (packages == null) {
      return false;
    }
    for (String pkg : packages) {
      if (ALLOWED_PACKAGE.equals(pkg)) {
        return true;
      }
    }
    return false;
  }
}
