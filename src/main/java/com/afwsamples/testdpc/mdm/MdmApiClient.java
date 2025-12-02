package com.afwsamples.testdpc.mdm;

import android.content.Context;
import android.util.Log;
import com.afwsamples.testdpc.EnrolState;
import com.afwsamples.testdpc.FileLogger;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONArray;
import org.json.JSONObject;

/** Minimal HTTP client for Qubit MDM endpoints. */
public final class MdmApiClient {
  private static final String TAG = "MdmApiClient";
  private static final String BASE_URL = "https://user-admin.tailnet.qubitsecured.online/api/mdm";
  private static final int TIMEOUT_MS = 15000;

  private MdmApiClient() {}

  private static HttpURLConnection open(
      Context context, String path, String method, String deviceToken) throws Exception {
    URL url = new URL(BASE_URL + path);
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setConnectTimeout(TIMEOUT_MS);
    conn.setReadTimeout(TIMEOUT_MS);
    conn.setRequestMethod(method);
    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
    conn.setRequestProperty("Accept", "application/json");
    if (deviceToken != null) {
      conn.setRequestProperty("X-Device-Token", deviceToken);
      conn.setRequestProperty("Authorization", "Device " + deviceToken);
    }
    if ("POST".equals(method)) {
      conn.setDoOutput(true);
    }
    return conn;
  }

  private static String readBody(HttpURLConnection conn, int code) {
    try {
      InputStream is =
          code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
      if (is == null) return null;
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

  public static JSONObject getPolicy(Context context) throws Exception {
    String token = new EnrolState(context).getDeviceToken();
    HttpURLConnection conn = open(context, "/policy", "GET", token);
    int code = conn.getResponseCode();
    String body = readBody(conn, code);
    conn.disconnect();
    log(context, "GET /policy code=" + code + " bodyLen=" + (body == null ? 0 : body.length()));
    if (code >= 200 && code < 300 && body != null) {
      return new JSONObject(body);
    }
    throw new Exception("GET /policy failed code=" + code + " body=" + body);
  }

  public static JSONArray postInbox(Context context, JSONObject payload) throws Exception {
    String token = new EnrolState(context).getDeviceToken();
    HttpURLConnection conn = open(context, "/inbox", "POST", token);
    byte[] bytes = (payload != null ? payload : new JSONObject()).toString().getBytes("UTF-8");
    OutputStream os = conn.getOutputStream();
    os.write(bytes);
    os.flush();
    os.close();
    int code = conn.getResponseCode();
    String body = readBody(conn, code);
    conn.disconnect();
    log(context, "POST /inbox code=" + code + " bodyLen=" + (body == null ? 0 : body.length()));
    if (code >= 200 && code < 300 && body != null) {
      JSONObject root = new JSONObject(body);
      return root.optJSONArray("results");
    }
    throw new Exception("POST /inbox failed code=" + code + " body=" + body);
  }

  public static JSONObject postAck(Context context, JSONArray commands) throws Exception {
    String token = new EnrolState(context).getDeviceToken();
    HttpURLConnection conn = open(context, "/ack", "POST", token);
    JSONObject payload = new JSONObject();
    payload.put("commands", commands);
    byte[] bytes = payload.toString().getBytes("UTF-8");
    OutputStream os = conn.getOutputStream();
    os.write(bytes);
    os.flush();
    os.close();
    int code = conn.getResponseCode();
    String body = readBody(conn, code);
    conn.disconnect();
    log(context, "POST /ack code=" + code + " bodyLen=" + (body == null ? 0 : body.length()));
    if (code >= 200 && code < 300 && body != null) {
      return new JSONObject(body);
    }
    throw new Exception("POST /ack failed code=" + code + " body=" + body);
  }

  public static JSONObject postInventory(Context context, JSONArray packages, String requestId)
      throws Exception {
    if (packages == null || packages.length() == 0) {
      return null;
    }
    String token = new EnrolState(context).getDeviceToken();
    HttpURLConnection conn = open(context, "/inventory", "POST", token);
    JSONObject payload = new JSONObject();
    payload.put("request_id", requestId);
    payload.put("timestamp", System.currentTimeMillis() / 1000);
    payload.put("packages", packages);
    payload.put("device_id", new EnrolState(context).getDeviceId());
    byte[] bytes = payload.toString().getBytes("UTF-8");
    OutputStream os = conn.getOutputStream();
    os.write(bytes);
    os.flush();
    os.close();
    int code = conn.getResponseCode();
    String body = readBody(conn, code);
    conn.disconnect();
    log(
        context,
        "POST /inventory code=" + code + " bodyLen=" + (body == null ? 0 : body.length()));
    if (code >= 200 && code < 300) {
      return body != null ? new JSONObject(body) : null;
    }
    if (body != null) {
      log(context, "POST /inventory error code=" + code + " body=" + body);
    }
    throw new Exception("POST /inventory failed code=" + code + " body=" + body);
  }

  public static JSONObject postMqttCredentials(Context context) throws Exception {
    String token = new EnrolState(context).getDeviceToken();
    HttpURLConnection conn = open(context, "/mqtt/credentials", "POST", token);
    int code = conn.getResponseCode();
    String body = readBody(conn, code);
    conn.disconnect();
    log(context, "POST /mqtt/credentials code=" + code + " body=" + body);
    if (code >= 200 && code < 300 && body != null) {
      return new JSONObject(body);
    }
    throw new Exception("POST /mqtt/credentials failed code=" + code + " body=" + body);
  }

  private static void log(Context context, String msg) {
    Log.i(TAG, msg);
    FileLogger.log(context, "MdmApi: " + msg);
  }
}
