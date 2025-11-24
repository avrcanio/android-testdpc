package com.afwsamples.testdpc;

import android.content.Context;
import android.content.SharedPreferences;

/** Simple helper to persist and retrieve provisioning enrol tokens. */
public class EnrolConfig {

  private static final String PREFS_NAME = "dpc_config";
  private static final String KEY_ENROL_TOKEN = "enrol_token";

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
}
