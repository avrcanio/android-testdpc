package com.afwsamples.testdpc.mdm;

import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.PersistableBundle;
import android.content.SharedPreferences;
import android.os.UserManager;
import android.util.Log;
import com.afwsamples.testdpc.DeviceAdminReceiver;
import com.afwsamples.testdpc.EnrolState;
import com.afwsamples.testdpc.FileLogger;
import com.afwsamples.testdpc.common.PackageInstallationUtils;
import com.afwsamples.testdpc.common.Util;
import com.afwsamples.testdpc.mdm.InventoryReporter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Runs a manual sync cycle against Qubit backend: policy -> inbox -> ack. */
public final class MdmSyncManager {
  private static final String TAG = "MdmSyncManager";
  private static final long MAX_APK_BYTES = 200L * 1024L * 1024L; // 200MB guardrail
  private static final Map<String, Integer> KEYGUARD_FLAGS = new HashMap<>();
  private static final String PREF_PWD = "mdm_pwd_req";
  private static final String KEY_LAST_PWD_REQ_ID = "last_request_id";

  static {
    KEYGUARD_FLAGS.put("disable_secure_camera", DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA);
    KEYGUARD_FLAGS.put(
        "disable_secure_notifications", DevicePolicyManager.KEYGUARD_DISABLE_SECURE_NOTIFICATIONS);
    KEYGUARD_FLAGS.put(
        "disable_unredacted_notifications",
        DevicePolicyManager.KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS);
    KEYGUARD_FLAGS.put("disable_trust_agents", DevicePolicyManager.KEYGUARD_DISABLE_TRUST_AGENTS);
    KEYGUARD_FLAGS.put("disable_face_unlock", DevicePolicyManager.KEYGUARD_DISABLE_FACE);
    KEYGUARD_FLAGS.put("disable_fingerprint", DevicePolicyManager.KEYGUARD_DISABLE_FINGERPRINT);
    KEYGUARD_FLAGS.put("disable_iris_unlock", DevicePolicyManager.KEYGUARD_DISABLE_IRIS);
    KEYGUARD_FLAGS.put(
        "disable_shortcuts", DevicePolicyManager.KEYGUARD_DISABLE_SHORTCUTS_ALL);
    KEYGUARD_FLAGS.put("disable_widgets", DevicePolicyManager.KEYGUARD_DISABLE_WIDGETS_ALL);
  }

  public interface SyncCallback {
    void onComplete(boolean success, String message);
  }

  private MdmSyncManager() {}

  public static void syncNow(Context context, SyncCallback callback) {
    final Context app = context.getApplicationContext();
    final String requestId = Long.toHexString(System.currentTimeMillis());
    new Thread(
            () -> {
              boolean success = false;
              String msg = "";
              try {
                String token = new EnrolState(app).getDeviceToken();
                if (token == null) {
                  msg = "No device_token; enrol first";
                  log(app, requestId, msg);
                  postResult(callback, success, msg);
                  return;
                }

                log(app, requestId, "Sync start");
                FcmPushManager.sync(app);
                JSONObject policyRoot = MdmApiClient.getPolicy(app);
                JSONObject policyObj = policyRoot.optJSONObject("policy");
                String etag = policyRoot.optString("policy_etag", null);
                int poll = policyRoot.optInt("poll_interval_sec", 30);
                if (policyObj != null) {
                  PolicyConfig.savePolicy(app, policyObj.toString(), etag, poll);
                  log(
                      app,
                      requestId,
                      "Policy saved etag=" + etag + " poll=" + poll + " bodyLen=" + policyObj.length());
                }

                boolean isDeviceOwner = Util.isDeviceOwner(app);
                JSONObject inboxBody = new JSONObject();
                inboxBody.put("is_device_owner", isDeviceOwner);
                JSONArray inbox =
                    MdmApiClient.postInbox(app, inboxBody);
                JSONArray ackList = new JSONArray();
                if (inbox != null) {
                  log(app, requestId, "Inbox size=" + inbox.length());
                  for (int i = 0; i < inbox.length(); i++) {
                    JSONObject cmd = inbox.optJSONObject(i);
                    if (cmd == null) continue;
                    JSONObject ack = processCommand(app, cmd, requestId);
                    if (ack != null) {
                      ackList.put(ack);
                    }
                  }
                } else {
                  log(app, requestId, "Inbox empty (null results)");
                }

                if (ackList.length() > 0) {
                  JSONObject ackResp = MdmApiClient.postAck(app, ackList);
                  log(app, requestId, "Ack sent count=" + ackList.length() + " resp=" + ackResp);
                } else {
                  log(app, requestId, "No acks to send");
                }

                success = true;
                msg = "Sync done, commands acked=" + ackList.length();
              } catch (Exception e) {
                Log.e(TAG, "Sync failed", e);
                log(app, requestId, "Sync error: " + e.getMessage());
                msg = e.getMessage();
              }
              postResult(callback, success, msg);
            })
        .start();
  }

  private static void postResult(SyncCallback callback, boolean success, String msg) {
    if (callback != null) {
      callback.onComplete(success, msg);
    }
  }

  private static JSONObject processCommand(Context context, JSONObject cmd, String requestId) {
    String typeRaw = cmd.optString("type", cmd.optString("command", ""));
    String type = typeRaw.toLowerCase(Locale.US);
    long id = cmd.optLong("id", -1);
    JSONObject ack = new JSONObject();
    try {
      ack.put("id", id);
      if (!typeRaw.isEmpty()) {
        ack.put("command", typeRaw);
      }
      String qid = cmd.optString("qid", null);
      if (qid != null) {
        ack.put("qid", qid);
      }
      switch (type) {
        case "install_apk_package":
          JSONObject payload = cmd.optJSONObject("payload");
          JSONObject audit = cmd.optJSONObject("audit");
          JSONObject installResult =
              handleInstallCommand(context, payload, audit, requestId, ack.optLong("id", -1));
          for (java.util.Iterator<String> it = installResult.keys(); it.hasNext(); ) {
            String key = it.next();
            ack.put(key, installResult.get(key));
          }
          break;
        case "uninstall_app":
          JSONObject uninstallPayload = cmd.optJSONObject("payload");
          String pkgName =
              uninstallPayload != null ? uninstallPayload.optString("package_name", null) : null;
          if (pkgName == null) {
            ack.put("success", false);
            ack.put("error", "missing_package_name");
            break;
          }
          FileLogger.log(context, "MdmSync uninstall start pkg=" + pkgName);
          PackageInstallationUtils.uninstallPackage(context, pkgName);
          FileLogger.log(context, "MdmSync uninstall invoked pkg=" + pkgName);
          ack.put("success", true);
          JSONArray inventoryUninstall = InventoryReporter.collect(context);
          ack.put("inventory", inventoryUninstall);
          maybePostInventory(context, inventoryUninstall, requestId);
          break;
        case "suspend_app":
          JSONObject suspendPayload = cmd.optJSONObject("payload");
          JSONArray pkgArray =
              suspendPayload != null ? suspendPayload.optJSONArray("packages") : null;
          boolean suspended = suspendPayload != null && suspendPayload.optBoolean("suspended", true);
          List<String> pkgs = new ArrayList<>();
          if (pkgArray != null) {
            for (int i = 0; i < pkgArray.length(); i++) {
              String p = pkgArray.optString(i, null);
              if (p != null) pkgs.add(p);
            }
          }
          if (pkgs.isEmpty()) {
            ack.put("success", false);
            ack.put("error", "missing_packages");
            break;
          }
          try {
            DevicePolicyManager dpmSuspend =
                (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName adminSuspend = DeviceAdminReceiver.getComponentName(context);
            dpmSuspend.setPackagesSuspended(
                adminSuspend, pkgs.toArray(new String[0]), suspended);
            FileLogger.log(
                context,
                "MdmSync suspend_app invoked suspended=" + suspended + " pkgs=" + pkgs.toString());
            ack.put("success", true);
            JSONArray inventorySuspend = InventoryReporter.collect(context);
            ack.put("inventory", inventorySuspend);
            maybePostInventory(context, inventorySuspend, requestId);
          } catch (Exception e) {
            FileLogger.log(context, "MdmSync suspend_app error: " + e.getMessage());
            ack.put("success", false);
            ack.put("error", e.getMessage());
          }
          break;
        case "hide_app":
          JSONObject hidePayload = cmd.optJSONObject("payload");
          JSONArray hidePkgArray =
              hidePayload != null ? hidePayload.optJSONArray("packages") : null;
          boolean hidden = hidePayload != null && hidePayload.optBoolean("hidden", true);
          List<String> hidePkgs = new ArrayList<>();
          if (hidePkgArray != null) {
            for (int i = 0; i < hidePkgArray.length(); i++) {
              String p = hidePkgArray.optString(i, null);
              if (p != null) hidePkgs.add(p);
            }
          }
          if (hidePkgs.isEmpty()) {
            ack.put("success", false);
            ack.put("error", "missing_packages");
            break;
          }
          try {
            DevicePolicyManager dpmHide =
                (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName adminHide = DeviceAdminReceiver.getComponentName(context);
            boolean allOk = true;
            for (String p : hidePkgs) {
              boolean res = dpmHide.setApplicationHidden(adminHide, p, hidden);
              if (!res) {
                allOk = false;
              }
            }
            FileLogger.log(
                context,
                "MdmSync hide_app invoked hidden=" + hidden + " pkgs=" + hidePkgs.toString());
            ack.put("success", allOk);
            if (!allOk) {
              ack.put("error", "setApplicationHidden returned false for at least one pkg");
            }
            JSONArray inventoryHide = InventoryReporter.collect(context);
            ack.put("inventory", inventoryHide);
            maybePostInventory(context, inventoryHide, requestId);
          } catch (Exception e) {
            FileLogger.log(context, "MdmSync hide_app error: " + e.getMessage());
            ack.put("success", false);
            ack.put("error", e.getMessage());
          }
          break;
        case "block_uninstall":
          JSONObject blockPayload = cmd.optJSONObject("payload");
          JSONArray blockPkgArray =
              blockPayload != null ? blockPayload.optJSONArray("packages") : null;
          boolean blocked = blockPayload != null && blockPayload.optBoolean("blocked", true);
          if (blockPkgArray == null || blockPkgArray.length() == 0) {
            ack.put("success", false);
            ack.put("error", "missing_packages");
            break;
          }
          DevicePolicyManager dpmBlock =
              (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
          ComponentName adminBlock = DeviceAdminReceiver.getComponentName(context);
          JSONArray metaPackages = new JSONArray();
          boolean allBlocked = true;
          for (int i = 0; i < blockPkgArray.length(); i++) {
            String p = blockPkgArray.optString(i, null);
            if (p == null) {
              continue;
            }
            JSONObject metaEntry = new JSONObject();
            metaEntry.put("package", p);
            try {
              dpmBlock.setUninstallBlocked(adminBlock, p, blocked);
              metaEntry.put("blocked", blocked);
            } catch (Exception e) {
              allBlocked = false;
              metaEntry.put("blocked", !blocked);
              metaEntry.put("message", e.getMessage());
              FileLogger.log(context, "MdmSync block_uninstall error pkg=" + p + " err=" + e.getMessage());
            }
            metaPackages.put(metaEntry);
          }
          ack.put("success", allBlocked);
          JSONObject meta = new JSONObject();
          meta.put("packages", metaPackages);
          meta.put("blocked", blocked);
          ack.put("meta", meta);
          break;
        case "get_user_restrictions":
          ack.put("success", true);
          ack.put("meta", UserRestrictionsManager.snapshot(context));
          break;
        case "set_user_restrictions":
          JSONObject restrictionPayload = cmd.optJSONObject("payload");
          JSONObject requested =
              restrictionPayload != null ? restrictionPayload.optJSONObject("restrictions") : null;
          if (requested == null) {
            ack.put("success", false);
            ack.put("error", "missing_restrictions");
            break;
          }
          JSONObject applyRes = UserRestrictionsManager.apply(context, requested);
          boolean allOk = applyRes.optBoolean("all_ok", false);
          ack.put("success", allOk);
          ack.put("meta", applyRes);
          break;
        case "set_location":
          JSONObject locationPayload = cmd.optJSONObject("payload");
          boolean locationEnabled =
              locationPayload != null && locationPayload.optBoolean("enabled", false);
          DevicePolicyManager dpmLocation =
              (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
          ComponentName adminLocation = DeviceAdminReceiver.getComponentName(context);
          try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
              ack.put("success", false);
              ack.put("error", "setLocationEnabled requires API 30+");
              ack.put("current_state", isLocationEnabled(context));
              break;
            }
            dpmLocation.setLocationEnabled(adminLocation, locationEnabled);
            dpmLocation.addUserRestriction(adminLocation, UserManager.DISALLOW_CONFIG_LOCATION);
            boolean currentLocationState = isLocationEnabled(context);
            FileLogger.log(
                context,
                "MdmSync set_location invoked enabled="
                    + locationEnabled
                    + " now="
                    + currentLocationState);
            ack.put("success", true);
            ack.put("current_state", currentLocationState);
          } catch (Exception e) {
            FileLogger.log(context, "MdmSync set_location error: " + e.getMessage());
            ack.put("success", false);
            ack.put("error", e.getMessage());
            ack.put("current_state", isLocationEnabled(context));
          }
          break;
        case "wipe":
          DevicePolicyManager dpm =
              (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
          ComponentName admin = DeviceAdminReceiver.getComponentName(context);
          JSONObject wipePayload = cmd.optJSONObject("payload");
          boolean wipeExternal =
              wipePayload != null && wipePayload.optBoolean("wipe_external_storage", false);
          boolean wipeResetProtection =
              wipePayload != null && wipePayload.optBoolean("wipe_reset_protection_data", false);
          int flags = 0;
          if (wipeExternal) {
            flags |= DevicePolicyManager.WIPE_EXTERNAL_STORAGE;
          }
          if (wipeResetProtection) {
            flags |= DevicePolicyManager.WIPE_RESET_PROTECTION_DATA;
          }
          FileLogger.log(
              context,
              "MdmSync wipe invoked flags="
                  + flags
                  + " wipeExternal="
                  + wipeExternal
                  + " wipeRP="
                  + wipeResetProtection);
          try {
            boolean isOrgOwned =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    && dpm.isOrganizationOwnedDeviceWithManagedProfile();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
              dpm.wipeDevice(flags);
            } else if (isOrgOwned && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
              // COPE before U uses parent instance and ignores flags.
              DevicePolicyManager parent = dpm.getParentProfileInstance(admin);
              if (parent != null) {
                parent.wipeData(0);
              } else {
                throw new IllegalStateException("Parent DPM missing for org-owned profile");
              }
            } else {
              dpm.wipeData(flags);
            }
            ack.put("success", true);
            ack.put("flags", flags);
          } catch (Exception e) {
            FileLogger.log(context, "MdmSync wipe error: " + e.getMessage());
            ack.put("success", false);
            ack.put("error", e.getMessage());
          }
          break;
        case "set_lock_screen":
          JSONObject lsPayload = cmd.optJSONObject("payload");
          JSONObject lockResult =
              handleSetLockScreen(context, lsPayload, requestId, ack.optString("qid", null), id);
          for (java.util.Iterator<String> it = lockResult.keys(); it.hasNext(); ) {
            String key = it.next();
            ack.put(key, lockResult.get(key));
          }
          break;
        case "set_password_policy":
          JSONObject ppPayload = cmd.optJSONObject("payload");
          JSONObject ppResult =
              handleSetPasswordPolicy(context, ppPayload, requestId, ack.optString("qid", null), id);
          for (java.util.Iterator<String> it = ppResult.keys(); it.hasNext(); ) {
            String key = it.next();
            ack.put(key, ppResult.get(key));
          }
          break;
        case "request_password_change":
          JSONObject rpcPayload = cmd.optJSONObject("payload");
          JSONObject rpcResult =
              handleRequestPasswordChange(context, rpcPayload, requestId, ack.optString("qid", null), id);
          for (java.util.Iterator<String> it = rpcResult.keys(); it.hasNext(); ) {
            String key = it.next();
            ack.put(key, rpcResult.get(key));
          }
          break;
        case "set_password_complexity":
          JSONObject pcPayload = cmd.optJSONObject("payload");
          JSONObject pcResult =
              handleSetPasswordComplexity(context, pcPayload, requestId, ack.optString("qid", null), id);
          for (java.util.Iterator<String> it = pcResult.keys(); it.hasNext(); ) {
            String key = it.next();
            ack.put(key, pcResult.get(key));
          }
          break;
        default:
          ack.put("success", false);
          ack.put("error", "unsupported_type");
          break;
      }
    } catch (Exception e) {
      try {
        ack.put("success", false);
        ack.put("error", e.getMessage());
      } catch (Exception ignore) {
        // ignore
      }
    }
    return ack;
  }

  private static JSONObject handleSetLockScreen(
      Context context, JSONObject payload, String requestId, String qid, long commandId) {
    JSONObject result = new JSONObject();
    JSONObject meta = new JSONObject();
    JSONArray perKey = new JSONArray();
    boolean success = true;
    String req = qid != null ? qid : (commandId >= 0 ? Long.toString(commandId) : requestId);

    try {
      if (payload == null) {
        result.put("success", false);
        result.put("error", "missing_payload");
        result.put("meta", meta);
        return result;
      }
      JSONObject lock = payload.optJSONObject("lock_screen");
      if (lock == null) {
        result.put("success", false);
        result.put("error", "missing_lock_screen");
        result.put("meta", meta);
        return result;
      }

      DevicePolicyManager dpm =
          (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
      ComponentName admin = DeviceAdminReceiver.getComponentName(context);

      if (lock.has("message")) {
        try {
          CharSequence msg = lock.isNull("message") ? "" : lock.optString("message", "");
          dpm.setDeviceOwnerLockScreenInfo(admin, msg);
          meta.put("message", msg);
        } catch (Exception e) {
          success = false;
          meta.put("message_error", e.getMessage());
        }
      }

      if (lock.has("max_time_to_lock_seconds")) {
        try {
          long seconds = lock.optLong("max_time_to_lock_seconds", -1);
          if (seconds >= 0) {
            dpm.setMaximumTimeToLock(admin, TimeUnit.SECONDS.toMillis(seconds));
            meta.put("max_time_to_lock_seconds", seconds);
          }
        } catch (Exception e) {
          success = false;
          meta.put("max_time_to_lock_seconds_error", e.getMessage());
        }
      }

      if (lock.has("strong_auth_timeout_seconds")) {
        try {
          long seconds = lock.optLong("strong_auth_timeout_seconds", -1);
          if (seconds >= 0) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
              dpm.setRequiredStrongAuthTimeout(admin, TimeUnit.SECONDS.toMillis(seconds));
              meta.put("strong_auth_timeout_seconds", seconds);
            } else {
              meta.put("strong_auth_timeout_skipped", "requires_api_26");
            }
          }
        } catch (Exception e) {
          success = false;
          meta.put("strong_auth_timeout_seconds_error", e.getMessage());
        }
      }

      if (lock.has("max_failed_password_for_wipe")) {
        try {
          int fails = lock.optInt("max_failed_password_for_wipe", -1);
          if (fails >= 0) {
            dpm.setMaximumFailedPasswordsForWipe(admin, fails);
            meta.put("max_failed_password_for_wipe", fails);
          }
        } catch (Exception e) {
          success = false;
          meta.put("max_failed_password_for_wipe_error", e.getMessage());
        }
      }

      // Keyguard feature flags
      JSONObject kf = lock.optJSONObject("keyguard_features");
      if (kf != null) {
        try {
          int flags = dpm.getKeyguardDisabledFeatures(admin);
          for (Map.Entry<String, Integer> entry : KEYGUARD_FLAGS.entrySet()) {
            String key = entry.getKey();
            if (!kf.has(key)) {
              continue;
            }
            boolean value = kf.optBoolean(key, false);
            int bit = entry.getValue();
            if (value) {
              flags |= bit;
            } else {
              flags &= ~bit;
            }
            JSONObject metaEntry = new JSONObject();
            metaEntry.put("key", key);
            metaEntry.put("value", value);
            perKey.put(metaEntry);
          }
          dpm.setKeyguardDisabledFeatures(admin, flags);
        } catch (Exception e) {
          success = false;
          meta.put("keyguard_error", e.getMessage());
        }
      }

      // Trust agent
      JSONObject ta = lock.optJSONObject("trust_agent");
      ComponentName taComponent = null;
      if (ta != null) {
        String compStr = ta.optString("component", null);
        if (compStr != null) {
          taComponent = ComponentName.unflattenFromString(compStr);
        }
        if (taComponent == null && compStr != null) {
          // Best-effort parse if the string is "pkg/.Service"
          int slash = compStr.indexOf('/');
          if (slash > 0) {
            String pkg = compStr.substring(0, slash);
            String cls = compStr.substring(slash + 1);
            taComponent = new ComponentName(pkg, cls);
          }
        }
        PersistableBundle taBundle = jsonToPersistable(ta.optJSONObject("config"));
        if (taComponent != null) {
          try {
            dpm.setTrustAgentConfiguration(admin, taComponent, taBundle);
            JSONObject taMeta = new JSONObject();
            taMeta.put("component", taComponent.flattenToString());
            taMeta.put("config", ta.optJSONObject("config"));
            meta.put("trust_agent", taMeta);
          } catch (Exception e) {
            success = false;
            meta.put("trust_agent_error", e.getMessage());
          }
        }
      }

      // Password complexity (API 30+)
      if (lock.has("password_complexity")) {
        String complexityStr = lock.optString("password_complexity", null);
        try {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && complexityStr != null) {
            int complexity = parsePasswordComplexity(complexityStr);
            if (complexity > 0) {
              dpm.setRequiredPasswordComplexity(complexity);
              meta.put("password_complexity", complexityStr.toLowerCase(Locale.US));
            } else {
              success = false;
              meta.put("password_complexity_error", "invalid_value");
            }
          } else {
            meta.put("password_complexity_skipped", "requires_api_30");
          }
        } catch (Exception e) {
          success = false;
          meta.put("password_complexity_error", e.getMessage());
        }
      }

      if (perKey.length() > 0) {
        meta.put("per_key", perKey);
      }

      // Telemetry snapshot
      try {
        JSONObject snapshot =
            buildLockScreenSnapshot(context, admin, taComponent, lock.optJSONObject("keyguard_features"));
        JSONObject telemetry = new JSONObject();
        telemetry.put("request_id", req != null ? req : requestId);
        telemetry.put("timestamp", System.currentTimeMillis() / 1000);
        telemetry.put("lock_screen", snapshot);
        MdmApiClient.postLockScreenState(context, telemetry);
        meta.put("telemetry_sent", true);
      } catch (Exception e) {
        meta.put("telemetry_error", e.getMessage());
      }

      result.put("success", success);
      if (!success) {
        result.put("error", "partial_failure");
      }
      result.put("meta", meta);
      FileLogger.log(
          context,
          "MdmSync set_lock_screen reqId=" + requestId + " qid=" + qid + " success=" + success);
      return result;
    } catch (Exception e) {
      try {
        result.put("success", false);
        result.put("error", e.getMessage());
        result.put("meta", meta);
      } catch (Exception ignore) {
        // ignore
      }
      return result;
    }
  }

  private static JSONObject buildLockScreenSnapshot(
      Context context,
      ComponentName admin,
      ComponentName trustAgent,
      JSONObject requestedKeyguardFeatures) {
    JSONObject snap = new JSONObject();
    try {
      DevicePolicyManager dpm =
          (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
      CharSequence msg = dpm.getDeviceOwnerLockScreenInfo();
      if (msg != null) {
        snap.put("message", msg.toString());
      }
      snap.put("max_time_to_lock_seconds", TimeUnit.MILLISECONDS.toSeconds(dpm.getMaximumTimeToLock(admin)));
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        snap.put(
            "strong_auth_timeout_seconds",
            TimeUnit.MILLISECONDS.toSeconds(dpm.getRequiredStrongAuthTimeout(admin)));
      }
      snap.put("max_failed_password_for_wipe", dpm.getMaximumFailedPasswordsForWipe(admin));

      int flags = dpm.getKeyguardDisabledFeatures(admin);
      JSONObject kf = new JSONObject();
      for (Map.Entry<String, Integer> entry : KEYGUARD_FLAGS.entrySet()) {
        boolean value = (flags & entry.getValue()) != 0;
        // If request was partial, still report actual state.
        kf.put(entry.getKey(), value);
      }
      if (requestedKeyguardFeatures != null) {
        // Preserve other feature keys requested but not in map.
        for (java.util.Iterator<String> it = requestedKeyguardFeatures.keys(); it.hasNext(); ) {
          String k = it.next();
          if (!kf.has(k)) {
            kf.put(k, requestedKeyguardFeatures.optBoolean(k, false));
          }
        }
      }
      snap.put("keyguard_features", kf);

      if (trustAgent != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        List<PersistableBundle> cfg = dpm.getTrustAgentConfiguration(admin, trustAgent);
        if (cfg != null && cfg.size() > 0) {
          JSONObject ta = new JSONObject();
          ta.put("component", trustAgent.flattenToString());
          ta.put("config", persistableToJson(cfg.get(0)));
          snap.put("trust_agent", ta);
        }
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        int c = dpm.getRequiredPasswordComplexity();
        snap.put("password_complexity", complexityToString(c));
      }
    } catch (Exception ignore) {
      // snapshot is best-effort
    }
    return snap;
  }

  private static JSONObject handleSetPasswordComplexity(
      Context context, JSONObject payload, String requestId, String qid, long commandId) {
    JSONObject result = new JSONObject();
    JSONObject meta = new JSONObject();
    boolean success = true;
    String req = qid != null ? qid : (commandId >= 0 ? Long.toString(commandId) : requestId);
    try {
      if (payload == null) {
        result.put("success", false);
        result.put("error", "missing_payload");
        result.put("meta", meta);
        return result;
      }
      String value =
          payload.optString("password_complexity", payload.optString("required_password_complexity", null));
      if (value == null) {
        result.put("success", false);
        result.put("error", "missing_password_complexity");
        result.put("meta", meta);
        return result;
      }
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        result.put("success", false);
        result.put("error", "requires_api_30");
        result.put("meta", meta);
        return result;
      }
      int complexity = parsePasswordComplexity(value);
      if (complexity <= 0) {
        result.put("success", false);
        result.put("error", "invalid_value");
        result.put("meta", meta);
        return result;
      }
      DevicePolicyManager dpm =
          (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
      dpm.setRequiredPasswordComplexity(complexity);
      meta.put("password_complexity", value.toLowerCase(Locale.US));
      meta.put("request_id", req);
      // Snapshot current complexity for audit
      meta.put("current_complexity", complexityToString(dpm.getRequiredPasswordComplexity()));
      result.put("success", success);
      result.put("meta", meta);
      FileLogger.log(
          context,
          "MdmSync set_password_complexity reqId=" + requestId + " qid=" + qid + " complexity=" + value);
      return result;
    } catch (Exception e) {
      try {
        result.put("success", false);
        result.put("error", e.getMessage());
        result.put("meta", meta);
      } catch (Exception ignore) {
        // ignore
      }
      return result;
    }
  }

  private static JSONObject handleSetPasswordPolicy(
      Context context, JSONObject payload, String requestId, String qid, long commandId) {
    JSONObject result = new JSONObject();
    JSONObject meta = new JSONObject();
    boolean success = true;
    String req = qid != null ? qid : (commandId >= 0 ? Long.toString(commandId) : requestId);
    try {
      if (payload == null) {
        result.put("success", false);
        result.put("error", "missing_payload");
        result.put("meta", meta);
        return result;
      }
      JSONObject policy = payload.optJSONObject("password_policy");
      if (policy == null) {
        result.put("success", false);
        result.put("error", "missing_password_policy");
        result.put("meta", meta);
        return result;
      }
      DevicePolicyManager dpm =
          (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
      ComponentName admin = DeviceAdminReceiver.getComponentName(context);

      // Quality
      String qualityStr = policy.optString("quality", null);
      if (qualityStr != null) {
        try {
          int quality = mapPasswordQuality(qualityStr);
          if (quality <= 0) {
            success = false;
            meta.put("quality_error", "invalid_value");
          } else {
            dpm.setPasswordQuality(admin, quality);
            meta.put("quality", qualityStr.toLowerCase(Locale.US));
          }
        } catch (Exception e) {
          success = false;
          meta.put("quality_error", e.getMessage());
        }
      }

      // Expiration
      if (policy.has("expiration_seconds")) {
        try {
          long seconds = policy.optLong("expiration_seconds", 0);
          dpm.setPasswordExpirationTimeout(admin, seconds * 1000);
          meta.put("expiration_seconds", seconds);
        } catch (Exception e) {
          success = false;
          meta.put("expiration_error", e.getMessage());
        }
      }

      // History
      if (policy.has("history_length")) {
        try {
          int hist = policy.optInt("history_length", 0);
          dpm.setPasswordHistoryLength(admin, hist);
          meta.put("history_length", hist);
        } catch (Exception e) {
          success = false;
          meta.put("history_error", e.getMessage());
        }
      }

      // Min requirements
      applyIntPolicy(policy, "min_length", (val) -> dpm.setPasswordMinimumLength(admin, val), meta);
      applyIntPolicy(policy, "min_letters", (val) -> dpm.setPasswordMinimumLetters(admin, val), meta);
      applyIntPolicy(policy, "min_digits", (val) -> dpm.setPasswordMinimumNumeric(admin, val), meta);
      applyIntPolicy(policy, "min_lowercase", (val) -> dpm.setPasswordMinimumLowerCase(admin, val), meta);
      applyIntPolicy(policy, "min_uppercase", (val) -> dpm.setPasswordMinimumUpperCase(admin, val), meta);
      applyIntPolicy(policy, "min_symbols", (val) -> dpm.setPasswordMinimumSymbols(admin, val), meta);
      applyIntPolicy(policy, "min_nonletter", (val) -> dpm.setPasswordMinimumNonLetter(admin, val), meta);

      // Snapshot current state
      try {
        JSONObject snap = buildPasswordPolicySnapshot(context, admin);
        meta.put("snapshot", snap);
      } catch (Exception e) {
        meta.put("snapshot_error", e.getMessage());
      }

      result.put("success", success);
      if (!success) {
        result.put("error", "partial_failure");
      }
      result.put("meta", meta);
      FileLogger.log(
          context,
          "MdmSync set_password_policy reqId=" + requestId + " qid=" + qid + " success=" + success);
      return result;
    } catch (Exception e) {
      try {
        result.put("success", false);
        result.put("error", e.getMessage());
        result.put("meta", meta);
      } catch (Exception ignore) {
        // ignore
      }
      return result;
    }
  }

  private static PersistableBundle jsonToPersistable(JSONObject obj) {
    if (obj == null) {
      return null;
    }
    PersistableBundle out = new PersistableBundle();
    for (java.util.Iterator<String> it = obj.keys(); it.hasNext(); ) {
      String key = it.next();
      Object val = obj.opt(key);
      if (val == null || JSONObject.NULL.equals(val)) {
        continue;
      }
      if (val instanceof Boolean) {
        out.putBoolean(key, (Boolean) val);
      } else if (val instanceof Integer) {
        out.putInt(key, (Integer) val);
      } else if (val instanceof Long) {
        out.putLong(key, (Long) val);
      } else if (val instanceof Double) {
        out.putDouble(key, (Double) val);
      } else if (val instanceof String) {
        out.putString(key, (String) val);
      } else if (val instanceof JSONObject) {
        out.putPersistableBundle(key, jsonToPersistable((JSONObject) val));
      } else if (val instanceof JSONArray) {
        JSONArray arr = (JSONArray) val;
        String[] strs = new String[arr.length()];
        for (int i = 0; i < arr.length(); i++) {
          strs[i] = arr.optString(i, null);
        }
        out.putStringArray(key, strs);
      }
    }
    return out;
  }

  private static int parsePasswordComplexity(String value) {
    if (value == null) {
      return -1;
    }
    switch (value.toLowerCase(Locale.US)) {
      case "none":
        return DevicePolicyManager.PASSWORD_COMPLEXITY_NONE;
      case "low":
        return DevicePolicyManager.PASSWORD_COMPLEXITY_LOW;
      case "medium":
        return DevicePolicyManager.PASSWORD_COMPLEXITY_MEDIUM;
      case "high":
        return DevicePolicyManager.PASSWORD_COMPLEXITY_HIGH;
      default:
        return -1;
    }
  }

  private static String complexityToString(int complexity) {
    switch (complexity) {
      case DevicePolicyManager.PASSWORD_COMPLEXITY_NONE:
        return "none";
      case DevicePolicyManager.PASSWORD_COMPLEXITY_LOW:
        return "low";
      case DevicePolicyManager.PASSWORD_COMPLEXITY_MEDIUM:
        return "medium";
      case DevicePolicyManager.PASSWORD_COMPLEXITY_HIGH:
        return "high";
      default:
        return "unknown";
    }
  }

  private static void savePasswordChangeRequest(Context context, String reqId) {
    if (reqId == null) {
      return;
    }
    SharedPreferences prefs =
        context.getSharedPreferences(PREF_PWD, Context.MODE_PRIVATE);
    prefs.edit().putString(KEY_LAST_PWD_REQ_ID, reqId).apply();
  }

  private static String getPasswordChangeRequest(Context context) {
    SharedPreferences prefs =
        context.getSharedPreferences(PREF_PWD, Context.MODE_PRIVATE);
    return prefs.getString(KEY_LAST_PWD_REQ_ID, null);
  }

  private static void clearPasswordChangeRequest(Context context) {
    SharedPreferences prefs =
        context.getSharedPreferences(PREF_PWD, Context.MODE_PRIVATE);
    prefs.edit().remove(KEY_LAST_PWD_REQ_ID).apply();
  }

  public static void onPasswordChanged(Context context) {
    String reqId = getPasswordChangeRequest(context);
    if (reqId == null) {
      return;
    }
    try {
      MdmApiClient.postPasswordChangeState(context, reqId, true, "changed");
      clearPasswordChangeRequest(context);
      FileLogger.log(context, "MdmSync password_changed reported reqId=" + reqId);
    } catch (Exception e) {
      FileLogger.log(context, "MdmSync password_changed report error: " + e.getMessage());
    }
  }

  private static JSONObject handleRequestPasswordChange(
      Context context, JSONObject payload, String requestId, String qid, long commandId) {
    JSONObject result = new JSONObject();
    JSONObject meta = new JSONObject();
    boolean success = true;
    String req = qid != null ? qid : (commandId >= 0 ? Long.toString(commandId) : requestId);
    try {
      if (payload == null) {
        result.put("success", false);
        result.put("error", "missing_payload");
        result.put("meta", meta);
        return result;
      }
      String message = payload.optString("message", null);
      Intent intent = new Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD);
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      if (message != null) {
        intent.putExtra(Intent.EXTRA_TEXT, message);
      }
      try {
        context.startActivity(intent);
        meta.put("prompt_shown", true);
      } catch (Exception e) {
        success = false;
        meta.put("prompt_shown", false);
        meta.put("prompt_error", e.getMessage());
      }
      meta.put("request_id", req);
      meta.put("timestamp", System.currentTimeMillis() / 1000);
      // Force immediate change: expire current pwd and lock the device
      try {
        DevicePolicyManager dpm =
            (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = DeviceAdminReceiver.getComponentName(context);
        dpm.setPasswordExpirationTimeout(admin, 1000); // expire immediately
        dpm.lockNow();
        meta.put("lock_invoked", true);
        meta.put("expiration_timeout_ms", 1000);
      } catch (Exception e) {
        success = false;
        meta.put("lock_error", e.getMessage());
      }
      savePasswordChangeRequest(context, req);
      result.put("success", success);
      result.put("meta", meta);
      FileLogger.log(
          context,
          "MdmSync request_password_change reqId=" + requestId + " qid=" + qid + " success=" + success);
      return result;
    } catch (Exception e) {
      try {
        result.put("success", false);
        result.put("error", e.getMessage());
        result.put("meta", meta);
      } catch (Exception ignore) {
        // ignore
      }
      return result;
    }
  }

  private static int mapPasswordQuality(String value) {
    if (value == null) {
      return -1;
    }
    switch (value.toLowerCase(Locale.US)) {
      case "unspecified":
        return DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED;
      case "something":
        return DevicePolicyManager.PASSWORD_QUALITY_SOMETHING;
      case "numeric":
        return DevicePolicyManager.PASSWORD_QUALITY_NUMERIC;
      case "numeric_complex":
        return DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX;
      case "alphabetic":
        return DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC;
      case "alphanumeric":
        return DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC;
      case "complex":
        return DevicePolicyManager.PASSWORD_QUALITY_COMPLEX;
      default:
        return -1;
    }
  }

  private interface IntPolicyApplier {
    void apply(int value) throws Exception;
  }

  private static void applyIntPolicy(
      JSONObject policy, String key, IntPolicyApplier applier, JSONObject meta) {
    if (!policy.has(key)) {
      return;
    }
    int val = 0;
    try {
      val = policy.optInt(key, 0);
      applier.apply(val);
      try {
        meta.put(key, val);
      } catch (Exception ignore) {
        // ignore meta write failure
      }
    } catch (Exception e) {
      try {
        meta.put(key + "_error", e.getMessage());
      } catch (Exception ignore) {
        // ignore meta write failure
      }
    }
  }

  private static JSONObject buildPasswordPolicySnapshot(Context context, ComponentName admin) {
    JSONObject snap = new JSONObject();
    try {
      DevicePolicyManager dpm =
          (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
      snap.put("quality", qualityToString(dpm.getPasswordQuality(admin)));
      snap.put(
          "expiration_seconds", TimeUnit.MILLISECONDS.toSeconds(dpm.getPasswordExpirationTimeout(admin)));
      snap.put("history_length", dpm.getPasswordHistoryLength(admin));
      snap.put("min_length", dpm.getPasswordMinimumLength(admin));
      snap.put("min_letters", dpm.getPasswordMinimumLetters(admin));
      snap.put("min_digits", dpm.getPasswordMinimumNumeric(admin));
      snap.put("min_lowercase", dpm.getPasswordMinimumLowerCase(admin));
      snap.put("min_uppercase", dpm.getPasswordMinimumUpperCase(admin));
      snap.put("min_symbols", dpm.getPasswordMinimumSymbols(admin));
      snap.put("min_nonletter", dpm.getPasswordMinimumNonLetter(admin));
    } catch (Exception ignore) {
      // best-effort
    }
    return snap;
  }

  private static String qualityToString(int quality) {
    switch (quality) {
      case DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED:
        return "unspecified";
      case DevicePolicyManager.PASSWORD_QUALITY_SOMETHING:
        return "something";
      case DevicePolicyManager.PASSWORD_QUALITY_NUMERIC:
        return "numeric";
      case DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX:
        return "numeric_complex";
      case DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC:
        return "alphabetic";
      case DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC:
        return "alphanumeric";
      case DevicePolicyManager.PASSWORD_QUALITY_COMPLEX:
        return "complex";
      default:
        return "unknown";
    }
  }

  private static JSONObject persistableToJson(PersistableBundle bundle) {
    if (bundle == null) {
      return null;
    }
    JSONObject obj = new JSONObject();
    for (String key : bundle.keySet()) {
      Object val = bundle.get(key);
      try {
        if (val instanceof Boolean
            || val instanceof Integer
            || val instanceof Long
            || val instanceof Double
            || val instanceof String) {
          obj.put(key, val);
        } else if (val instanceof String[]) {
          JSONArray arr = new JSONArray();
          for (String s : (String[]) val) {
            arr.put(s);
          }
          obj.put(key, arr);
        } else if (val instanceof PersistableBundle) {
          obj.put(key, persistableToJson((PersistableBundle) val));
        }
      } catch (Exception ignore) {
        // best-effort conversion
      }
    }
    return obj;
  }

  private static JSONObject handleInstallCommand(
      Context context, JSONObject payload, JSONObject audit, String requestId, long commandId) {
    JSONObject result = new JSONObject();
    long start = System.currentTimeMillis();
    JSONObject meta = new JSONObject();
    JSONArray metaFiles = new JSONArray();

    try {
      if (payload == null) {
        result.put("success", false);
        result.put("error", "missing_payload");
        result.put("meta", meta);
        return result;
      }
      String pkg = payload.optString("package", null);
      String installType = payload.optString("install_type", "");
      long releaseFileId = payload.optLong("release_file_id", -1);
      JSONArray filesArray = payload.optJSONArray("files");
      if (pkg == null) {
        result.put("success", false);
        result.put("error", "missing_package");
        result.put("meta", meta);
        return result;
      }
      if (filesArray == null || filesArray.length() == 0) {
        result.put("success", false);
        result.put("error", "missing_files");
        result.put("meta", meta);
        return result;
      }

      meta.put("package", pkg);
      meta.put("install_type", installType);
      if (releaseFileId > 0) {
        meta.put("release_file_id", releaseFileId);
      }

      log(
          context,
          requestId,
          "Install start pkg=" + pkg + " release_file_id=" + releaseFileId + " files=" + filesArray.length());

      List<DownloadedFile> files = new ArrayList<>();
      for (int i = 0; i < filesArray.length(); i++) {
        JSONObject fileObj = filesArray.optJSONObject(i);
        if (fileObj == null) {
          continue;
        }
        String url = fileObj.optString("url", null);
        String kind = fileObj.optString("kind", "apk");
        String expectedSha = fileObj.optString("sha256", null);
        int versionCode = fileObj.optInt("version_code", -1);
        String versionName = fileObj.optString("version_name", null);
        if (url == null) {
          result.put("success", false);
          result.put("error", "missing_url");
          result.put("meta", meta);
          return result;
        }
        try {
          long dlStart = System.currentTimeMillis();
          DownloadedFile file = downloadAndVerify(context, url, expectedSha, kind);
          file.versionCode = versionCode;
          file.versionName = versionName;
          file.downloadMs = System.currentTimeMillis() - dlStart;
          files.add(file);

          JSONObject metaFile = new JSONObject();
          metaFile.put("url", url);
          metaFile.put("kind", kind);
          metaFile.put("expected_sha256", expectedSha);
          metaFile.put("actual_sha256", file.actualSha256);
          metaFile.put("bytes", file.length);
          metaFile.put("download_ms", file.downloadMs);
          if (versionCode >= 0) {
            metaFile.put("version_code", versionCode);
          }
          if (versionName != null) {
            metaFile.put("version_name", versionName);
          }
          metaFiles.put(metaFile);
        } catch (Exception e) {
          result.put("success", false);
          result.put("error", e.getMessage());
          meta.put("files", metaFiles);
          meta.put("install_duration_ms", System.currentTimeMillis() - start);
          result.put("meta", meta);
          return result;
        }
      }

      try {
        installApkFiles(context, pkg, files);
        result.put("success", true);
        log(
            context,
            requestId,
            "Install done pkg="
                + pkg
                + " release_file_id="
                + releaseFileId
                + " files="
                + files.size()
                + " ms="
                + (System.currentTimeMillis() - start));
      } catch (Exception e) {
        result.put("success", false);
        result.put("error", e.getMessage());
        log(
            context,
            requestId,
            "Install failed pkg="
                + pkg
                + " release_file_id="
                + releaseFileId
                + " err="
                + e.getMessage());
      }

      meta.put("files", metaFiles);
      if (audit != null) {
        String source = audit.optString("source", null);
        String requestedBy = audit.optString("requested_by", null);
        if (source != null) {
          meta.put("audit_source", source);
        }
        if (requestedBy != null) {
          meta.put("requested_by", requestedBy);
        }
      }
      meta.put("install_duration_ms", System.currentTimeMillis() - start);

      if (result.optBoolean("success", false)) {
        JSONArray inventoryInstall = InventoryReporter.collect(context);
        meta.put("inventory", inventoryInstall);
        maybePostInventory(context, inventoryInstall, requestId);
      }

      result.put("meta", meta);
      return result;
    } catch (JSONException e) {
      try {
        result.put("success", false);
        result.put("error", e.getMessage());
        result.put("meta", meta);
      } catch (JSONException ignore) {
        // ignore secondary failure
      }
      return result;
    }
  }

  private static void installApkFiles(Context context, String packageName, List<DownloadedFile> files)
      throws IOException {
    if (files == null || files.isEmpty()) {
      throw new IOException("no_files_to_install");
    }
    PackageInstaller installer = context.getPackageManager().getPackageInstaller();
    PackageInstaller.SessionParams params =
        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
    params.setAppPackageName(packageName);
    int sessionId = installer.createSession(params);
    PackageInstaller.Session session = installer.openSession(sessionId);
    int idx = 0;
    try {
      for (DownloadedFile file : files) {
        String entry = deriveEntryName(file.url, file.kind != null ? file.kind : "apk", idx++);
        InputStream in = null;
        java.io.OutputStream out = null;
        try {
          in = new FileInputStream(file.file);
          long length = file.file.length();
          out = session.openWrite(entry, 0, length);
          byte[] buffer = new byte[65536];
          int read;
          while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
          }
          session.fsync(out);
        } finally {
          if (in != null) {
            in.close();
          }
          if (out != null) {
            out.close();
          }
        }
      }
      session.commit(createInstallIntentSender(context, sessionId));
    } finally {
      session.close();
      // cleanup temp files
      for (DownloadedFile file : files) {
        if (file.file != null && file.file.exists()) {
          //noinspection ResultOfMethodCallIgnored
          file.file.delete();
        }
      }
    }
  }

  private static String deriveEntryName(String url, String kind, int index) {
    try {
      String path = new URL(url).getPath();
      if (path != null) {
        String[] parts = path.split("/");
        String last = parts[parts.length - 1];
        if (!last.isEmpty()) {
          return last;
        }
      }
    } catch (Exception ignore) {
      // ignore and fall back
    }
    String suffix = "apk".equalsIgnoreCase(kind) ? ".apk" : ".pkg";
    return "file_" + index + suffix;
  }

  private static DownloadedFile downloadAndVerify(
      Context context, String urlStr, String expectedSha, String kind) throws Exception {
    URL url = new URL(urlStr);
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setConnectTimeout(15000);
    conn.setReadTimeout(45000);
    conn.setInstanceFollowRedirects(true);
    conn.setRequestProperty("Cache-Control", "no-cache");
    conn.connect();
    int code = conn.getResponseCode();
    if (code < 200 || code >= 300) {
      conn.disconnect();
      throw new IOException("download_failed code=" + code);
    }
    long contentLength = conn.getContentLengthLong();
    if (contentLength > 0 && contentLength > MAX_APK_BYTES) {
      conn.disconnect();
      throw new IOException("file_too_large bytes=" + contentLength);
    }

    File tmp =
        File.createTempFile("mdm_dl_", ".apk", context.getCacheDir());
    long written = 0;
    String actualSha;
    try (InputStream in = conn.getInputStream();
        FileOutputStream out = new FileOutputStream(tmp)) {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] buf = new byte[65536];
      int read;
      while ((read = in.read(buf)) != -1) {
        written += read;
        if (written > MAX_APK_BYTES) {
          throw new IOException("file_too_large bytes=" + written);
        }
        out.write(buf, 0, read);
        md.update(buf, 0, read);
      }
      actualSha = hex(md.digest());
      out.getFD().sync();
    } catch (Exception e) {
      // cleanup temp file on failure
      if (tmp.exists()) {
        //noinspection ResultOfMethodCallIgnored
        tmp.delete();
      }
      throw e;
    } finally {
      conn.disconnect();
    }

    if (expectedSha != null
        && !expectedSha.isEmpty()
        && !expectedSha.equalsIgnoreCase(actualSha)) {
      // cleanup before throwing
      if (tmp.exists()) {
        //noinspection ResultOfMethodCallIgnored
        tmp.delete();
      }
      throw new IOException("sha256_mismatch expected=" + expectedSha + " actual=" + actualSha);
    }

    DownloadedFile file = new DownloadedFile();
    file.url = urlStr;
    file.kind = kind;
    file.sha256 = expectedSha;
    file.actualSha256 = actualSha;
    file.file = tmp;
    file.length = tmp.length();
    return file;
  }

  private static String hex(byte[] data) {
    StringBuilder sb = new StringBuilder();
    for (byte b : data) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }

  private static IntentSender createInstallIntentSender(Context context, int sessionId) {
    PendingIntent pendingIntent =
        PendingIntent.getBroadcast(
            context,
            sessionId,
            new Intent(PackageInstallationUtils.ACTION_INSTALL_COMPLETE),
            PendingIntent.FLAG_IMMUTABLE);
    return pendingIntent.getIntentSender();
  }

  private static final class DownloadedFile {
    String url;
    String kind;
    String sha256;
    String actualSha256;
    String versionName;
    int versionCode;
    long downloadMs;
    File file;
    long length;
  }

  private static void log(Context context, String reqId, String msg) {
    Log.i(TAG, "reqId=" + reqId + " " + msg);
    FileLogger.log(context, "MdmSync reqId=" + reqId + " " + msg);
  }

  private static boolean isLocationEnabled(Context context) {
    try {
      LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
      return lm != null && lm.isLocationEnabled();
    } catch (Exception e) {
      return false;
    }
  }

  private static void maybePostInventory(Context context, JSONArray inventory, String requestId) {
    if (inventory == null || inventory.length() == 0) {
      return;
    }
    try {
      MdmApiClient.postInventory(context, inventory, requestId);
    } catch (Exception e) {
      FileLogger.log(context, "Inventory upload failed reqId=" + requestId + " err=" + e.getMessage());
    }
  }
}
