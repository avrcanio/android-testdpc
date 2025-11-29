package com.afwsamples.testdpc;

import android.content.Context;
import android.content.SharedPreferences;

/** Simple helper to persist and retrieve provisioning enrol tokens. */
public class EnrolConfig {

  private static final String PREFS_NAME = "dpc_config";
  private static final String KEY_ENROL_TOKEN = "enrol_token";
  private static final String KEY_APK_INDEX_URL = "apk_index_url";
  private static final String KEY_SUPPORT_URL = "support_url";

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
}
