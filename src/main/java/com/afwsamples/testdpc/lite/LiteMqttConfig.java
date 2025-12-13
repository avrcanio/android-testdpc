package com.afwsamples.testdpc.lite;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.UUID;

/** Stores Lite MQTT client configuration and provides defaults. */
public class LiteMqttConfig {

  public static final String DEFAULT_HOST = "emqx.tailnet.qubitsecured.online";
  public static final int DEFAULT_PORT = 443;
  public static final String DEFAULT_PATH = "/mqtt";

  private static final String PREFS_NAME = "lite_mqtt_config";
  private static final String KEY_HOST = "mqtt_host";
  private static final String KEY_PORT = "mqtt_port";
  private static final String KEY_PATH = "mqtt_path";
  private static final String KEY_USERNAME = "mqtt_username";
  private static final String KEY_PASSWORD = "mqtt_password";
  private static final String KEY_QID = "mqtt_qid";
  private static final String KEY_CLIENT_ID = "mqtt_client_id";
  private static final String KEY_TLS_ENABLED = "mqtt_tls_enabled";

  private final SharedPreferences prefs;

  public LiteMqttConfig(Context context) {
    this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
  }

  public String getHost() {
    return prefs.getString(KEY_HOST, DEFAULT_HOST);
  }

  public void setHost(String host) {
    prefs.edit().putString(KEY_HOST, host).apply();
  }

  public int getPort() {
    return prefs.getInt(KEY_PORT, DEFAULT_PORT);
  }

  public void setPort(int port) {
    prefs.edit().putInt(KEY_PORT, port).apply();
  }

  public String getPath() {
    return prefs.getString(KEY_PATH, DEFAULT_PATH);
  }

  public void setPath(String path) {
    prefs.edit().putString(KEY_PATH, path).apply();
  }

  public String getUsername() {
    return prefs.getString(KEY_USERNAME, null);
  }

  public void setUsername(String username) {
    prefs.edit().putString(KEY_USERNAME, username).apply();
  }

  public String getPassword() {
    return prefs.getString(KEY_PASSWORD, null);
  }

  public void setPassword(String password) {
    prefs.edit().putString(KEY_PASSWORD, password).apply();
  }

  public String getQid() {
    return prefs.getString(KEY_QID, null);
  }

  public void setQid(String qid) {
    prefs.edit().putString(KEY_QID, qid).apply();
  }

  public String getClientId() {
    String stored = prefs.getString(KEY_CLIENT_ID, null);
    if (stored == null || stored.isEmpty()) {
      stored = "lite-" + UUID.randomUUID().toString();
      prefs.edit().putString(KEY_CLIENT_ID, stored).apply();
    }
    return stored;
  }

  public void setClientId(String clientId) {
    prefs.edit().putString(KEY_CLIENT_ID, clientId).apply();
  }

  public boolean isTlsEnabled() {
    return prefs.getBoolean(KEY_TLS_ENABLED, true);
  }

  public void setTlsEnabled(boolean enabled) {
    prefs.edit().putBoolean(KEY_TLS_ENABLED, enabled).apply();
  }
}
