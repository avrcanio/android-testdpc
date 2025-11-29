package com.afwsamples.testdpc.provision;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import com.afwsamples.testdpc.EnrolApiClient;
import com.afwsamples.testdpc.FileLogger;
import com.afwsamples.testdpc.common.PackageInstallationUtils;
import com.afwsamples.testdpc.common.Util;
import com.afwsamples.testdpc.mdm.MdmSyncManager;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Installs baseline apps from apk_index_url, then enrols and triggers initial sync. */
public final class BaselineProvisioner {
  private static final String TAG = "BaselineProvisioner";
  private static final int HTTP_TIMEOUT_MS = 15000;
  private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

  private BaselineProvisioner() {}

  public static void run(
      Context context, String apkIndexUrl, String enrolToken, String supportUrl, String requestId) {
    final Context app = context.getApplicationContext();
    if (!Util.isDeviceOwner(app)) {
      FileLogger.log(app, TAG + " skip: not device owner");
      return;
    }
    if (!RUNNING.compareAndSet(false, true)) {
      FileLogger.log(app, TAG + " already running, skip new request");
      return;
    }
    new Thread(
            () -> {
              try {
                FileLogger.log(
                    app,
                    TAG
                        + " start reqId="
                        + requestId
                        + " indexUrl="
                        + apkIndexUrl
                        + " supportUrlPresent="
                        + (supportUrl != null));
                boolean installsOk = fetchAndInstall(app, apkIndexUrl, supportUrl, requestId);
                if (enrolToken != null && !enrolToken.isEmpty()) {
                  EnrolApiClient.EnrolResult result =
                      EnrolApiClient.enrolBlocking(app, enrolToken);
                  if (result.success) {
                    FileLogger.log(
                        app, TAG + " enrol ok reqId=" + requestId + " -> kicking off sync");
                    MdmSyncManager.syncNow(app, null);
                  } else {
                    FileLogger.log(
                        app,
                        TAG
                            + " enrol failed reqId="
                            + requestId
                            + " code="
                            + result.responseCode
                            + " msg="
                            + result.errorMessage);
                    notifySupport(app, supportUrl, "Enrol failed: " + result.errorMessage);
                  }
                } else {
                  FileLogger.log(app, TAG + " enrol token missing, skipping enrol reqId=" + requestId);
                }
                if (!installsOk) {
                  notifySupport(app, supportUrl, "Baseline install failures; check logs");
                }
              } finally {
                RUNNING.set(false);
              }
            })
        .start();
  }

  private static boolean fetchAndInstall(
      Context context, String apkIndexUrl, String supportUrl, String requestId) {
    if (apkIndexUrl == null || apkIndexUrl.isEmpty()) {
      FileLogger.log(context, TAG + " no apk_index_url provided, skipping baseline install");
      return true;
    }
    JSONObject index = downloadIndex(context, apkIndexUrl, requestId);
    if (index == null) {
      notifySupport(context, supportUrl, "Neuspjelo preuzimanje index.json");
      return false;
    }
    JSONArray channel = selectChannel(index);
    if (channel == null || channel.length() == 0) {
      FileLogger.log(context, TAG + " no channel entries in index.json");
      return true;
    }
    boolean allOk = true;
    for (int i = 0; i < channel.length(); i++) {
      JSONObject entry = channel.optJSONObject(i);
      if (entry == null) {
        continue;
      }
      String pkg = entry.optString("package", null);
      String url = entry.optString("url", null);
      long versionCode = entry.optLong("version_code", -1);
      if (pkg == null || url == null || versionCode < 0) {
        FileLogger.log(context, TAG + " skip invalid entry " + entry);
        continue;
      }
      if (!isPackageOutdated(context, pkg, versionCode)) {
        FileLogger.log(
            context,
            TAG
                + " skip install reqId="
                + requestId
                + " pkg="
                + pkg
                + " version="
                + versionCode
                + " already present");
        continue;
      }
      DownloadHandle handle = downloadApk(context, url, requestId);
      if (handle == null) {
        allOk = false;
        continue;
      }
      try {
        PackageInstallationUtils.installPackage(context, handle.stream, pkg);
        FileLogger.log(
            context,
            TAG + " install invoked reqId=" + requestId + " pkg=" + pkg + " url=" + url);
      } catch (Exception e) {
        allOk = false;
        FileLogger.log(
            context,
            TAG
                + " install error reqId="
                + requestId
                + " pkg="
                + pkg
                + " err="
                + e.getMessage());
      } finally {
        handle.close();
      }
    }
    return allOk;
  }

  private static JSONObject downloadIndex(Context context, String urlStr, String requestId) {
    HttpURLConnection conn = null;
    try {
      URL url = new URL(urlStr);
      conn = (HttpURLConnection) url.openConnection();
      conn.setConnectTimeout(HTTP_TIMEOUT_MS);
      conn.setReadTimeout(HTTP_TIMEOUT_MS);
      conn.setRequestMethod("GET");
      conn.setRequestProperty("Accept", "application/json");
      conn.setInstanceFollowRedirects(true);
      conn.connect();
      int code = conn.getResponseCode();
      String body = readBody(conn, code);
      FileLogger.log(
          context,
          TAG
              + " index fetch reqId="
              + requestId
              + " code="
              + code
              + " bodyLen="
              + (body == null ? 0 : body.length()));
      if (code >= 200 && code < 300 && body != null) {
        return new JSONObject(body);
      }
    } catch (Exception e) {
      FileLogger.log(
          context, TAG + " index fetch error reqId=" + requestId + " err=" + e.getMessage());
    } finally {
      if (conn != null) {
        conn.disconnect();
      }
    }
    return null;
  }

  private static DownloadHandle downloadApk(Context context, String urlStr, String requestId) {
    HttpURLConnection conn = null;
    try {
      URL url = new URL(urlStr);
      conn = (HttpURLConnection) url.openConnection();
      conn.setConnectTimeout(HTTP_TIMEOUT_MS);
      conn.setReadTimeout(30000);
      conn.setInstanceFollowRedirects(true);
      conn.setRequestProperty("Accept-Encoding", "identity");
      conn.connect();
      int code = conn.getResponseCode();
      if (code >= 200 && code < 300) {
        return new DownloadHandle(conn, conn.getInputStream());
      }
      FileLogger.log(
          context,
          TAG + " download failed reqId=" + requestId + " code=" + code + " url=" + urlStr);
    } catch (Exception e) {
      FileLogger.log(
          context, TAG + " download error reqId=" + requestId + " url=" + urlStr + " err=" + e.getMessage());
    }
    if (conn != null) {
      conn.disconnect();
    }
    return null;
  }

  private static JSONArray selectChannel(JSONObject index) {
    JSONObject channels = index.optJSONObject("channels");
    if (channels == null) {
      return null;
    }
    JSONArray stable = channels.optJSONArray("stable");
    if (stable != null && stable.length() > 0) {
      return stable;
    }
    return channels.optJSONArray("beta");
  }

  private static boolean isPackageOutdated(Context context, String pkg, long targetVersion) {
    try {
      PackageInfo info = context.getPackageManager().getPackageInfo(pkg, 0);
      long installed =
          Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? info.getLongVersionCode() : info.versionCode;
      return installed < targetVersion;
    } catch (PackageManager.NameNotFoundException e) {
      return true;
    }
  }

  private static String readBody(HttpURLConnection conn, int code) {
    try {
      InputStream is = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
      if (is == null) {
        return null;
      }
      BufferedReader br = new BufferedReader(new InputStreamReader(is));
      StringBuilder sb = new StringBuilder();
      String line;
      while ((line = br.readLine()) != null) {
        sb.append(line);
      }
      br.close();
      return sb.toString();
    } catch (Exception e) {
      return null;
    }
  }

  private static void notifySupport(Context context, String supportUrl, String reason) {
    final Context app = context.getApplicationContext();
    FileLogger.log(app, TAG + " notify support: " + reason + " supportUrl=" + supportUrl);
    String msg =
        supportUrl == null
            ? "Instalacija baseline aplikacija nije uspjela"
            : "Instalacija baseline aplikacija nije uspjela. Support: " + supportUrl;
    new Handler(Looper.getMainLooper())
        .post(() -> Toast.makeText(app, msg, Toast.LENGTH_LONG).show());
  }

  private static final class DownloadHandle implements AutoCloseable {
    private final HttpURLConnection connection;
    private final InputStream stream;

    DownloadHandle(HttpURLConnection connection, InputStream stream) {
      this.connection = connection;
      this.stream = stream;
    }

    @Override
    public void close() {
      try {
        stream.close();
      } catch (Exception ignore) {
        // ignore
      }
      connection.disconnect();
    }
  }
}
