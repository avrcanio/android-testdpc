package com.afwsamples.testdpc;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;

/** Stores enrolment response details for Qubit backend. */
public class EnrolState {
  private static final String PREFS_NAME = "dpc_enrol_state";

  private static final String KEY_DEVICE_ID = "device_id";
  private static final String KEY_DEVICE_TOKEN = "device_token";
  private static final String KEY_POLICY_ETAG = "policy_etag";
  private static final String KEY_COMMANDS_PENDING = "commands_pending";
  private static final String KEY_POLL_INTERVAL_SEC = "poll_interval_sec";
  private static final String KEY_POLICY_JSON = "policy_json";
  private static final String KEY_ROTATE_REQUIRED = "rotate_required";
  private static final String KEY_MQTT_PASSWORD = "mqtt_password";

  private final SharedPreferences prefs;

  public EnrolState(Context context) {
    this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
  }

  public void saveFromResponse(JSONObject json) {
    SharedPreferences.Editor e = prefs.edit();
    e.putString(KEY_DEVICE_ID, json.optString("device_id", null));
    e.putString(KEY_DEVICE_TOKEN, json.optString("device_token", null));
    e.putString(KEY_MQTT_PASSWORD, json.optString("mqtt_password", null));
    e.putString(KEY_POLICY_ETAG, json.optString("policy_etag", null));
    e.putInt(KEY_COMMANDS_PENDING, json.optInt("commands_pending", 0));
    e.putInt(KEY_POLL_INTERVAL_SEC, json.optInt("poll_interval_sec", 0));
    e.putBoolean(KEY_ROTATE_REQUIRED, json.optBoolean("rotate_required", false));
    JSONObject policy = json.optJSONObject("policy");
    if (policy != null) {
      e.putString(KEY_POLICY_JSON, policy.toString());
    }
    e.apply();
  }

  public String getDeviceId() {
    return prefs.getString(KEY_DEVICE_ID, null);
  }

  public String getDeviceToken() {
    return prefs.getString(KEY_DEVICE_TOKEN, null);
  }

  public boolean isRotateRequired() {
    return prefs.getBoolean(KEY_ROTATE_REQUIRED, false);
  }

  public void setRotateRequired(boolean rotateRequired) {
    prefs.edit().putBoolean(KEY_ROTATE_REQUIRED, rotateRequired).apply();
  }

  public String getMqttPassword() {
    return prefs.getString(KEY_MQTT_PASSWORD, null);
  }

  public void setMqttPassword(String password) {
    prefs.edit().putString(KEY_MQTT_PASSWORD, password).apply();
  }
}
