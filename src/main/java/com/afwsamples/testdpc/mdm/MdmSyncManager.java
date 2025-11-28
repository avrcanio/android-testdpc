package com.afwsamples.testdpc.mdm;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import com.afwsamples.testdpc.DeviceAdminReceiver;
import com.afwsamples.testdpc.EnrolState;
import com.afwsamples.testdpc.FileLogger;
import com.afwsamples.testdpc.common.PackageInstallationUtils;
import java.util.ArrayList;
import java.util.List;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONArray;
import org.json.JSONObject;

/** Runs a manual sync cycle against Qubit backend: policy -> inbox -> ack. */
public final class MdmSyncManager {
  private static final String TAG = "MdmSyncManager";

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

                JSONArray inbox =
                    MdmApiClient.postInbox(app, new JSONObject()); // empty body per contract
                JSONArray ackList = new JSONArray();
                if (inbox != null) {
                  log(app, requestId, "Inbox size=" + inbox.length());
                  for (int i = 0; i < inbox.length(); i++) {
                    JSONObject cmd = inbox.optJSONObject(i);
                    if (cmd == null) continue;
                    JSONObject ack = processCommand(app, cmd);
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

  private static JSONObject processCommand(Context context, JSONObject cmd) {
    String type = cmd.optString("type", "");
    long id = cmd.optLong("id", -1);
    JSONObject ack = new JSONObject();
    try {
      ack.put("id", id);
      switch (type) {
        case "install_apk_package":
          JSONObject payload = cmd.optJSONObject("payload");
          String url = payload != null ? payload.optString("url", null) : null;
          String pkg = payload != null ? payload.optString("package", null) : null;
          String display = payload != null ? payload.optString("display_name", "") : "";
          if (url == null) {
            ack.put("success", false);
            ack.put("error", "missing_url");
            break;
          }
          FileLogger.log(context, "MdmSync install start url=" + url + " pkg=" + pkg + " name=" + display);
          InputStream in = downloadApk(context, url);
          if (in == null) {
            ack.put("success", false);
            ack.put("error", "download_failed");
            break;
          }
          PackageInstallationUtils.installPackage(context, in, pkg);
          FileLogger.log(context, "MdmSync install invoked url=" + url + " pkg=" + pkg);
          ack.put("success", true);
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
          } catch (Exception e) {
            FileLogger.log(context, "MdmSync hide_app error: " + e.getMessage());
            ack.put("success", false);
            ack.put("error", e.getMessage());
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

  private static InputStream downloadApk(Context context, String urlStr) {
    try {
      URL url = new URL(urlStr);
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();
      conn.setConnectTimeout(15000);
      conn.setReadTimeout(30000);
      conn.setInstanceFollowRedirects(true);
      conn.connect();
      int code = conn.getResponseCode();
      if (code >= 200 && code < 300) {
        return conn.getInputStream();
      }
      FileLogger.log(context, "Download failed code=" + code + " url=" + urlStr);
      conn.disconnect();
    } catch (Exception e) {
      FileLogger.log(context, "Download exception: " + e.getMessage());
    }
    return null;
  }

  private static void log(Context context, String reqId, String msg) {
    Log.i(TAG, "reqId=" + reqId + " " + msg);
    FileLogger.log(context, "MdmSync reqId=" + reqId + " " + msg);
  }
}
