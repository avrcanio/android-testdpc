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
import org.json.JSONArray;
import org.json.JSONObject;

/** Runs a manual sync cycle against Qubit backend: policy -> inbox -> ack. */
public final class MdmSyncManager {
  private static final String TAG = "MdmSyncManager";
  private static final long MAX_APK_BYTES = 200L * 1024L * 1024L; // 200MB guardrail

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
