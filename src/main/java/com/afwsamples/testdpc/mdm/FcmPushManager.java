package com.afwsamples.testdpc.mdm;

import android.app.admin.DevicePolicyManager;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import com.afwsamples.testdpc.DeviceAdminReceiver;
import com.afwsamples.testdpc.EnrolState;
import com.afwsamples.testdpc.FileLogger;
import com.afwsamples.testdpc.mdm.MdmApiClient.HttpException;
import java.util.concurrent.TimeUnit;

/** Handles FCM push token retrieval from Core and registration with the backend. */
public final class FcmPushManager {
  private static final String TAG = "QbitDpcPush";
  private static final Uri CORE_FCM_URI = Uri.parse("content://com.qubit.mqttcore.fcm");
  private static final String CORE_PACKAGE = "com.qubit.mqttcore";
  private static final String ACTION_REFRESH_TOKEN = "com.qubit.mqttcore.ACTION_REFRESH_FCM_TOKEN";
  private static final String ACTION_TOKEN_REGISTERED = "com.qubit.mqttcore.ACTION_FCM_TOKEN_REGISTERED";
  private static final long RESYNC_AFTER_MS = TimeUnit.HOURS.toMillis(24);
  private static final long BASE_RETRY_MS = TimeUnit.MINUTES.toMillis(5);
  private static final long MAX_RETRY_MS = TimeUnit.HOURS.toMillis(6);
  private static final int JOB_ID = 900201;

  private static final String PREFS = "qbit_push_token_state";
  private static final String KEY_TOKEN = "token";
  private static final String KEY_UPDATED_AT = "updated_at";
  private static final String KEY_ENABLED = "enabled";
  private static final String KEY_LAST_SYNC_TOKEN = "last_sync_token";
  private static final String KEY_LAST_SYNC_MS = "last_sync_ms";
  private static final String KEY_LAST_SYNC_ENABLED = "last_sync_enabled";
  private static final String KEY_WAITING_FOR_CORE = "waiting_for_core";
  private static final String KEY_BACKOFF = "backoff_attempts";

  private FcmPushManager() {}

  /** Runs synchronously; safe to call from a background thread. */
  public static void sync(Context context) {
    syncInternal(context.getApplicationContext());
  }

  /** Convenience helper for fire-and-forget triggers (spawns a new thread). */
  public static void syncAsync(Context context) {
    new Thread(() -> syncInternal(context.getApplicationContext())).start();
  }

  /** Invoked from {@link PushTokenJobService}. */
  static void syncFromJob(Context context) {
    syncInternal(context.getApplicationContext());
  }

  private static void syncInternal(Context context) {
    PushState state = loadState(context);
    String deviceToken = new EnrolState(context).getDeviceToken();
    if (deviceToken == null) {
      log(context, "No device_token; skipping push token sync");
      return;
    }

    TokenInfo tokenInfo = fetchToken(context);
    if (tokenInfo == null || tokenInfo.token == null) {
      state.waitingForCore = true;
      state.backoffAttempts = incrementBackoff(state.backoffAttempts);
      saveState(context, state);
      log(context, "No FCM token yet; asking Core to refresh");
      requestRefreshFromCore(context);
      scheduleRetry(context, computeBackoffMs(state.backoffAttempts));
      return;
    }

    state.waitingForCore = false;
    state.cachedToken = tokenInfo.token;
    state.cachedUpdatedAt = tokenInfo.updatedAt;
    state.cachedEnabled = tokenInfo.enabled;

    if (!shouldSync(tokenInfo, state)) {
      saveState(context, state);
      ensureRestrictionsForCore(context, tokenInfo);
      return;
    }

    try {
      log(context, "POST /push-token start");
      MdmApiClient.postPushToken(context, tokenInfo.token, tokenInfo.enabled);
      state.lastSyncedToken = tokenInfo.token;
      state.lastSyncedEnabled = tokenInfo.enabled;
      state.lastSyncMs = System.currentTimeMillis();
      state.backoffAttempts = 0;
      saveState(context, state);
      log(context, "POST /push-token success");
      ensureRestrictionsForCore(context, tokenInfo);
      notifyCoreRegistered(context, tokenInfo);
      cancelRetry(context);
    } catch (HttpException e) {
      log(context, "POST /push-token HTTP " + e.code + " body=" + e.body);
      state.backoffAttempts = incrementBackoff(state.backoffAttempts);
      saveState(context, state);
      if (e.code == 401 || e.code == 403) {
        new EnrolState(context).setRotateRequired(true);
        scheduleRetry(context, computeBackoffMs(state.backoffAttempts));
        return;
      }
      scheduleRetry(context, computeBackoffMs(state.backoffAttempts));
    } catch (Exception e) {
      log(context, "POST /push-token failed: " + e.getMessage());
      state.backoffAttempts = incrementBackoff(state.backoffAttempts);
      saveState(context, state);
      scheduleRetry(context, computeBackoffMs(state.backoffAttempts));
    }
  }

  private static boolean shouldSync(TokenInfo tokenInfo, PushState state) {
    if (!TextUtils.equals(tokenInfo.token, state.lastSyncedToken)) {
      return true;
    }
    if (tokenInfo.enabled != state.lastSyncedEnabled) {
      return true;
    }
    long now = System.currentTimeMillis();
    return now - state.lastSyncMs > RESYNC_AFTER_MS;
  }

  private static TokenInfo fetchToken(Context context) {
    TokenInfo fromProvider = queryProvider(context);
    if (fromProvider != null && !TextUtils.isEmpty(fromProvider.token)) {
      return fromProvider;
    }
    TokenInfo fromRestrictions = readRestrictions(context);
    if (fromRestrictions != null && !TextUtils.isEmpty(fromRestrictions.token)) {
      return fromRestrictions;
    }
    return null;
  }

  private static TokenInfo queryProvider(Context context) {
    Cursor cursor = null;
    try {
      ContentResolver resolver = context.getContentResolver();
      cursor = resolver.query(CORE_FCM_URI, new String[] {"token", "updated_at"}, null, null, null);
      if (cursor != null && cursor.moveToFirst()) {
        String token = cursor.getString(cursor.getColumnIndexOrThrow("token"));
        if (token == null) {
          return null;
        }
        long updatedAt = 0L;
        int updatedIdx = cursor.getColumnIndex("updated_at");
        if (updatedIdx >= 0) {
          updatedAt = cursor.getLong(updatedIdx);
        }
        log(context, "Got token from provider len=" + token.length() + " updated_at=" + updatedAt);
        boolean enabled = !TextUtils.isEmpty(token);
        return new TokenInfo(token, updatedAt, enabled);
      }
    } catch (Exception e) {
      log(context, "Provider query failed: " + e.getMessage());
    } finally {
      if (cursor != null) {
        cursor.close();
      }
    }
    return null;
  }

  private static TokenInfo readRestrictions(Context context) {
    try {
      DevicePolicyManager dpm =
          (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
      ComponentName admin = DeviceAdminReceiver.getComponentName(context);
      if (dpm == null || admin == null) {
        return null;
      }
      Bundle bundle = dpm.getApplicationRestrictions(admin, CORE_PACKAGE);
      if (bundle == null) {
        return null;
      }
      String token = bundle.getString("fcm_registration_token", null);
      long updatedAt = bundle.getLong("fcm_token_updated_at", 0L);
      if (token != null) {
        log(context, "Got token from restrictions len=" + token.length() + " updated_at=" + updatedAt);
        boolean enabled = !TextUtils.isEmpty(token);
        return new TokenInfo(token, updatedAt, enabled);
      }
    } catch (Exception e) {
      log(context, "Read restrictions failed: " + e.getMessage());
    }
    return null;
  }

  private static void ensureRestrictionsForCore(Context context, TokenInfo tokenInfo) {
    try {
      DevicePolicyManager dpm =
          (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
      ComponentName admin = DeviceAdminReceiver.getComponentName(context);
      if (dpm == null || admin == null || tokenInfo == null) {
        return;
      }
      Bundle bundle = new Bundle();
      bundle.putString("fcm_registration_token", tokenInfo.token == null ? "" : tokenInfo.token);
      long updatedAt = tokenInfo.updatedAt > 0 ? tokenInfo.updatedAt : System.currentTimeMillis();
      bundle.putLong("fcm_token_updated_at", updatedAt);
      dpm.setApplicationRestrictions(admin, CORE_PACKAGE, bundle);
      log(context, "Wrote token to restrictions for Core updated_at=" + updatedAt);
    } catch (Exception e) {
      log(context, "Writing restrictions failed: " + e.getMessage());
    }
  }

  private static void requestRefreshFromCore(Context context) {
    try {
      Intent intent = new Intent(ACTION_REFRESH_TOKEN);
      intent.setPackage(CORE_PACKAGE);
      context.sendBroadcast(intent);
      log(context, "Sent refresh broadcast to Core");
    } catch (Exception e) {
      log(context, "Refresh broadcast failed: " + e.getMessage());
    }
  }

  private static void notifyCoreRegistered(Context context, TokenInfo tokenInfo) {
    try {
      Intent intent = new Intent(ACTION_TOKEN_REGISTERED);
      intent.setPackage(CORE_PACKAGE);
      intent.putExtra("token", tokenInfo.token);
      intent.putExtra("updated_at", tokenInfo.updatedAt);
      intent.putExtra("enabled", tokenInfo.enabled);
      context.sendBroadcast(intent);
      log(context, "Notified Core that token is registered");
    } catch (Exception e) {
      log(context, "Notify Core failed: " + e.getMessage());
    }
  }

  private static void scheduleRetry(Context context, long delayMs) {
    try {
      JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
      if (scheduler == null) {
        return;
      }
      ComponentName service = new ComponentName(context, PushTokenJobService.class);
      JobInfo.Builder builder = new JobInfo.Builder(JOB_ID, service)
          .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
          .setMinimumLatency(delayMs)
          .setBackoffCriteria(Math.max(delayMs, BASE_RETRY_MS), JobInfo.BACKOFF_POLICY_EXPONENTIAL)
          .setPersisted(true);
      int res = scheduler.schedule(builder.build());
      log(context, "Scheduled retry in " + delayMs + "ms res=" + res);
    } catch (Exception e) {
      log(context, "Schedule retry failed: " + e.getMessage());
    }
  }

  private static void cancelRetry(Context context) {
    try {
      JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
      if (scheduler != null) {
        scheduler.cancel(JOB_ID);
        log(context, "Cancelled pending push-token retries");
      }
    } catch (Exception e) {
      log(context, "Cancel retry failed: " + e.getMessage());
    }
  }

  private static long computeBackoffMs(int attempts) {
    long delay = (long) (BASE_RETRY_MS * Math.pow(2, Math.max(0, attempts - 1)));
    return Math.min(delay, MAX_RETRY_MS);
  }

  private static int incrementBackoff(int attempts) {
    return attempts < 10 ? attempts + 1 : attempts;
  }

  private static PushState loadState(Context context) {
    android.content.SharedPreferences prefs =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    PushState state = new PushState();
    state.cachedToken = prefs.getString(KEY_TOKEN, null);
    state.cachedUpdatedAt = prefs.getLong(KEY_UPDATED_AT, 0L);
    state.cachedEnabled = prefs.getBoolean(KEY_ENABLED, true);
    state.lastSyncedToken = prefs.getString(KEY_LAST_SYNC_TOKEN, null);
    state.lastSyncedEnabled = prefs.getBoolean(KEY_LAST_SYNC_ENABLED, true);
    state.lastSyncMs = prefs.getLong(KEY_LAST_SYNC_MS, 0L);
    state.waitingForCore = prefs.getBoolean(KEY_WAITING_FOR_CORE, false);
    state.backoffAttempts = prefs.getInt(KEY_BACKOFF, 0);
    return state;
  }

  private static void saveState(Context context, PushState state) {
    android.content.SharedPreferences prefs =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    prefs
        .edit()
        .putString(KEY_TOKEN, state.cachedToken)
        .putLong(KEY_UPDATED_AT, state.cachedUpdatedAt)
        .putBoolean(KEY_ENABLED, state.cachedEnabled)
        .putString(KEY_LAST_SYNC_TOKEN, state.lastSyncedToken)
        .putBoolean(KEY_LAST_SYNC_ENABLED, state.lastSyncedEnabled)
        .putLong(KEY_LAST_SYNC_MS, state.lastSyncMs)
        .putBoolean(KEY_WAITING_FOR_CORE, state.waitingForCore)
        .putInt(KEY_BACKOFF, state.backoffAttempts)
        .apply();
  }

  private static void log(Context context, String msg) {
    Log.i(TAG, msg);
    FileLogger.log(context, TAG + ": " + msg);
  }

  private static final class TokenInfo {
    final String token;
    final long updatedAt;
    final boolean enabled;

    TokenInfo(String token, long updatedAt, boolean enabled) {
      this.token = token;
      this.updatedAt = updatedAt;
      this.enabled = enabled;
    }
  }

  private static final class PushState {
    String cachedToken;
    long cachedUpdatedAt;
    boolean cachedEnabled;
    String lastSyncedToken;
    boolean lastSyncedEnabled;
    long lastSyncMs;
    boolean waitingForCore;
    int backoffAttempts;
  }
}
