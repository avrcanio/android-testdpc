package com.afwsamples.testdpc;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.cert.Certificate;
import org.json.JSONObject;
import javax.net.ssl.HttpsURLConnection;

/** Fire-and-forget client that posts the saved enrol token to the Qubit endpoint. */
public class EnrolApiClient {

  private static final String ENROL_URL =
      "https://user-admin.tailnet.qubitsecured.online/api/mdm/enrol";

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
                int responseCode = -1;
                String errorMsg = null;
                Throwable errorThrowable = null;
                String responseBody = null;
                String requestId = Long.toHexString(System.currentTimeMillis());
                FileLogger.log(
                    appContext,
                    "EnrolApi start reqId="
                        + requestId
                        + " url="
                        + ENROL_URL
                        + " tokenPresent="
                        + (enrolToken != null));
                try {
                  JSONObject body = new JSONObject();
                  body.put("enrol_token", enrolToken);
                  byte[] payload = body.toString().getBytes("UTF-8");

                  URL url = new URL(ENROL_URL);
                  HttpURLConnection conn = (HttpURLConnection) url.openConnection();
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
                        StringBuilder sb = new StringBuilder("EnrolApi server certs reqId=" + requestId + ": ");
                        for (Certificate c : certs) {
                          sb.append(c.getType()).append(";");
                        }
                        Log.i("EnrolApiClient", sb.toString());
                        FileLogger.log(appContext, sb.toString());
                      }
                    } catch (Exception certEx) {
                      Log.w("EnrolApiClient", "EnrolApi cert info failed reqId=" + requestId, certEx);
                      FileLogger.log(appContext, "EnrolApi cert info failed: " + certEx);
                    }
                  }

                  responseCode = conn.getResponseCode();
                  responseBody = readResponse(conn, responseCode);
                  conn.disconnect();
                } catch (Exception e) {
                  errorThrowable = e;
                  errorMsg = e.getClass().getName() + ": " + e.getMessage();
                  FileLogger.log(appContext, "EnrolApi error reqId=" + requestId + " " + errorMsg);
                }

                final int finalCode = responseCode;
                final String finalError = errorMsg;
                final String finalResponse = responseBody;
                final Throwable finalThrowable = errorThrowable;
                final String finalRequestId = requestId;
                new Handler(Looper.getMainLooper())
                    .post(
                        new Runnable() {
                          @Override
                          public void run() {
                            if (finalError != null) {
                              Log.e(
                                  "EnrolApiClient",
                                  "Enrol error reqId="
                                      + finalRequestId
                                      + " code="
                                      + finalCode
                                      + " msg="
                                      + finalError,
                                  finalThrowable);
                              FileLogger.log(
                                  appContext,
                                  "EnrolApi error reqId="
                                      + finalRequestId
                                      + " code="
                                      + finalCode
                                      + " msg="
                                      + finalError);
                              Toast.makeText(
                                      appContext, "Enrol Qubit greška: " + finalError, Toast.LENGTH_LONG)
                                  .show();
                              if (finalResponse != null) {
                                Log.e("EnrolApiClient", "Response body: " + finalResponse);
                                FileLogger.log(appContext, "EnrolApi error body: " + finalResponse);
                              }
                            } else if (finalCode >= 200 && finalCode < 300) {
                              Toast.makeText(
                                      appContext,
                                      "Enrol Qubit: uspješno (" + finalCode + ")",
                                      Toast.LENGTH_LONG)
                                  .show();
                            } else {
                              Toast.makeText(
                                      appContext,
                                      "Enrol Qubit HTTP kod: " + finalCode,
                                      Toast.LENGTH_LONG)
                                  .show();
                              Log.w(
                                  "EnrolApiClient",
                                  "Enrol HTTP error reqId=" + finalRequestId + " code=" + finalCode);
                              FileLogger.log(
                                  appContext,
                                  "EnrolApi HTTP error reqId=" + finalRequestId + " code=" + finalCode);
                              if (finalResponse != null) {
                                Log.e("EnrolApiClient", "HTTP " + finalCode + " body: " + finalResponse);
                                FileLogger.log(
                                    appContext, "EnrolApi HTTP " + finalCode + " body: " + finalResponse);
                              }
                            }
                          }
                        });
              }
            })
        .start();
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
