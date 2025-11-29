package com.afwsamples.testdpc;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import com.afwsamples.testdpc.common.Util;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import org.json.JSONObject;
import javax.net.ssl.HttpsURLConnection;

/** Fire-and-forget client that posts the saved enrol token to the Qubit endpoint. */
public class EnrolApiClient {

  public static final String ACTION_ENROL_STATE_UPDATED = "com.qubit.mdm.ACTION_ENROL_STATE_UPDATED";

  private static final String ENROL_URL =
      "https://user-admin.tailnet.qubitsecured.online/api/mdm/enrol";

  public static final class EnrolResult {
    public boolean success;
    public int responseCode;
    public String errorMessage;
    public String responseBody;
    public String requestId;
  }

  public static void enrolWithSavedToken(final Context context) {
    final Context appContext = context.getApplicationContext();

    EnrolConfig config = new EnrolConfig(appContext);
    final String enrolToken = config.getEnrolToken();
    if (enrolToken == null || enrolToken.isEmpty()) {
      Toast.makeText(appContext, "Nema spremljenog enrol tokena", Toast.LENGTH_LONG).show();
      return;
    }

    new Thread(
            new Runnable() {
              @Override
              public void run() {
                EnrolResult result = enrolBlocking(appContext, enrolToken);
                new Handler(Looper.getMainLooper())
                    .post(
                        new Runnable() {
                          @Override
                          public void run() {
                            if (!result.success) {
                              Log.e(
                                  "EnrolApiClient",
                                  "Enrol error reqId="
                                      + result.requestId
                                      + " code="
                                      + result.responseCode
                                      + " msg="
                                      + result.errorMessage);
                              Toast.makeText(
                                      appContext,
                                      "Enrol Qubit greska: "
                                          + (result.errorMessage == null
                                              ? "nepoznata"
                                              : result.errorMessage),
                                      Toast.LENGTH_LONG)
                                  .show();
                            } else {
                              Toast.makeText(
                                      appContext,
                                      "Enrol Qubit: uspjesno (" + result.responseCode + ")",
                                      Toast.LENGTH_LONG)
                                  .show();
                            }
                          }
                        });
              }
            })
        .start();
  }

  public static EnrolResult enrolBlocking(final Context context, final String enrolToken) {
    final Context appContext = context.getApplicationContext();
    EnrolResult result = new EnrolResult();
    result.requestId = Long.toHexString(System.currentTimeMillis());
    if (enrolToken == null || enrolToken.isEmpty()) {
      result.errorMessage = "missing_enrol_token";
      return result;
    }
    FileLogger.log(
        appContext,
        "EnrolApi start reqId="
            + result.requestId
            + " url="
            + ENROL_URL
            + " tokenPresent="
            + (enrolToken != null));
    HttpURLConnection conn = null;
    try {
      JSONObject body = new JSONObject();
      body.put("enrol_token", enrolToken);
      body.put("is_device_owner", Util.isDeviceOwner(appContext));
      body.put("os_version", Build.VERSION.RELEASE);
      body.put("sdk_int", Build.VERSION.SDK_INT);
      body.put("device_model", Build.MODEL);
      body.put("device_manufacturer", Build.MANUFACTURER);
      byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);

      URL url = new URL(ENROL_URL);
      conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod("POST");
      conn.setConnectTimeout(15000);
      conn.setReadTimeout(15000);
      conn.setDoOutput(true);
      conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
      conn.setFixedLengthStreamingMode(payload.length);

      OutputStream os = conn.getOutputStream();
      os.write(payload);
      os.flush();
      os.close();

      if (conn instanceof HttpsURLConnection) {
        try {
          Certificate[] certs = ((HttpsURLConnection) conn).getServerCertificates();
          if (certs != null && certs.length > 0) {
            StringBuilder sb = new StringBuilder("EnrolApi server certs reqId=" + result.requestId + ": ");
            for (Certificate c : certs) {
              sb.append(c.getType()).append(";");
            }
            Log.i("EnrolApiClient", sb.toString());
            FileLogger.log(appContext, sb.toString());
          }
        } catch (Exception certEx) {
          Log.w("EnrolApiClient", "EnrolApi cert info failed reqId=" + result.requestId, certEx);
          FileLogger.log(appContext, "EnrolApi cert info failed: " + certEx);
        }
      }

      result.responseCode = conn.getResponseCode();
      result.responseBody = readResponse(conn, result.responseCode);
      if (result.responseCode >= 200 && result.responseCode < 300 && result.responseBody != null) {
        JSONObject json = new JSONObject(result.responseBody);
        EnrolState state = new EnrolState(appContext);
        state.saveFromResponse(json);
        result.success = true;
        FileLogger.log(
            appContext,
            "EnrolApi success reqId="
                + result.requestId
                + " device_id="
                + json.optString("device_id", "null"));
        Intent intent = new Intent(ACTION_ENROL_STATE_UPDATED);
        intent.setPackage(appContext.getPackageName());
        appContext.sendBroadcast(intent);
      } else {
        result.errorMessage = "http_" + result.responseCode;
        if (result.responseBody != null) {
          FileLogger.log(
              appContext, "EnrolApi HTTP " + result.responseCode + " body: " + result.responseBody);
        }
      }
    } catch (Exception e) {
      result.errorMessage = e.getClass().getName() + ": " + e.getMessage();
      FileLogger.log(appContext, "EnrolApi error reqId=" + result.requestId + " " + result.errorMessage);
    } finally {
      if (conn != null) {
        conn.disconnect();
      }
    }
    return result;
  }

  private static String readResponse(HttpURLConnection conn, int responseCode) {
    InputStream is = null;
    try {
      is = responseCode >= 200 && responseCode < 300 ? conn.getInputStream() : conn.getErrorStream();
      if (is == null) {
        return null;
      }
      BufferedReader reader = new BufferedReader(new InputStreamReader(is));
      StringBuilder sb = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) {
        sb.append(line);
      }
      reader.close();
      return sb.toString();
    } catch (Exception e) {
      return null;
    }
  }
}
