package com.afwsamples.testdpc.mdm;

import android.content.Context;
import android.content.SharedPreferences;

/** Persists policy payload, etag and poll interval. */
public final class PolicyConfig {
  private static final String PREFS = "qbit_policy";
  private static final String KEY_POLICY_JSON = "policy_json";
  private static final String KEY_POLICY_ETAG = "policy_etag";
  private static final String KEY_POLL = "poll_interval_sec";

  private PolicyConfig() {}

  private static SharedPreferences prefs(Context c) {
    return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  public static void savePolicy(Context c, String json, String etag, int poll) {
    prefs(c)
        .edit()
        .putString(KEY_POLICY_JSON, json)
        .putString(KEY_POLICY_ETAG, etag)
        .putInt(KEY_POLL, poll)
        .apply();
  }

  public static String getPolicyJson(Context c) {
    return prefs(c).getString(KEY_POLICY_JSON, null);
  }

  public static String getPolicyEtag(Context c) {
    return prefs(c).getString(KEY_POLICY_ETAG, null);
  }

  public static int getPollInterval(Context c) {
    return prefs(c).getInt(KEY_POLL, 30);
  }
}
