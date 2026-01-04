package mdm.qubit.dpc;

import android.content.Context;
import android.content.SharedPreferences;

/** Simple helper to persist and retrieve provisioning enrol tokens. */
public class EnrolConfig {

  private static final String PREFS_NAME = "dpc_config";
  private static final String KEY_ENROL_TOKEN = "enrol_token";
  private static final String KEY_APK_INDEX_URL = "apk_index_url";
  private static final String KEY_SUPPORT_URL = "support_url";
  private static final String KEY_TS_LOGIN_URL = "tailscale_login_url";
  private static final String KEY_TS_CONTROL_URL = "tailscale_control_url";
  private static final String KEY_TS_AUTH_KEY = "tailscale_auth_key";
  private static final String KEY_TS_HOSTNAME = "tailscale_hostname";
  private static final String KEY_TS_MANAGED_CONFIG = "tailscale_managed_config";
  private static final String KEY_TS_EXIT_NODE_ID = "tailscale_exit_node_id";
  private static final String KEY_TS_FORCE_ENABLED = "tailscale_force_enabled";

  private final SharedPreferences prefs;

  public EnrolConfig(Context context) {
    this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
  }

  public void saveEnrolToken(String token) {
    prefs.edit().putString(KEY_ENROL_TOKEN, token).apply();
  }

  public String getEnrolToken() {
    return prefs.getString(KEY_ENROL_TOKEN, null);
  }

  public void saveApkIndexUrl(String url) {
    prefs.edit().putString(KEY_APK_INDEX_URL, url).apply();
  }

  public String getApkIndexUrl() {
    return prefs.getString(KEY_APK_INDEX_URL, null);
  }

  public void saveSupportUrl(String url) {
    prefs.edit().putString(KEY_SUPPORT_URL, url).apply();
  }

  public String getSupportUrl() {
    return prefs.getString(KEY_SUPPORT_URL, null);
  }

  public void saveTailscaleLoginUrl(String url) {
    prefs.edit().putString(KEY_TS_LOGIN_URL, url).apply();
  }

  public String getTailscaleLoginUrl() {
    return prefs.getString(KEY_TS_LOGIN_URL, null);
  }

  public void saveTailscaleControlUrl(String url) {
    prefs.edit().putString(KEY_TS_CONTROL_URL, url).apply();
  }

  public String getTailscaleControlUrl() {
    return prefs.getString(KEY_TS_CONTROL_URL, null);
  }

  public void saveTailscaleAuthKey(String key) {
    prefs.edit().putString(KEY_TS_AUTH_KEY, key).apply();
  }

  public String getTailscaleAuthKey() {
    return prefs.getString(KEY_TS_AUTH_KEY, null);
  }

  public void saveTailscaleHostname(String hostname) {
    prefs.edit().putString(KEY_TS_HOSTNAME, hostname).apply();
  }

  public String getTailscaleHostname() {
    return prefs.getString(KEY_TS_HOSTNAME, null);
  }

  public void saveTailscaleManagedConfig(String json) {
    prefs.edit().putString(KEY_TS_MANAGED_CONFIG, json).apply();
  }

  public String getTailscaleManagedConfig() {
    return prefs.getString(KEY_TS_MANAGED_CONFIG, null);
  }

  public void saveTailscaleExitNodeId(String exitNodeId) {
    prefs.edit().putString(KEY_TS_EXIT_NODE_ID, exitNodeId).apply();
  }

  public String getTailscaleExitNodeId() {
    return prefs.getString(KEY_TS_EXIT_NODE_ID, null);
  }

  public void saveTailscaleForceEnabled(boolean forceEnabled) {
    prefs.edit().putBoolean(KEY_TS_FORCE_ENABLED, forceEnabled).apply();
  }

  public boolean isTailscaleForceEnabled() {
    return prefs.getBoolean(KEY_TS_FORCE_ENABLED, false);
  }
}
