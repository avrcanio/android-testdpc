package com.afwsamples.testdpc.mdm;

import android.content.Context;
import android.util.Log;
import android.widget.Toast;
import com.afwsamples.testdpc.EnrolState;
import com.afwsamples.testdpc.R;
import org.json.JSONObject;

/** Fetches pending MQTT credentials from backend and stores them locally. */
public final class MqttCredentialRefresher {
  private static final String TAG = "MqttCredentialRefresh";

  private MqttCredentialRefresher() {}

  public static void refresh(final Context context) {
    new Thread(
            () -> {
              try {
                JSONObject resp = MdmApiClient.postMqttCredentials(context);
                String username = resp.optString("username", null);
                String password = resp.optString("password", null);
                if (username == null || password == null) {
                  showToast(context, context.getString(R.string.mqtt_refresh_no_creds));
                  return;
                }
                EnrolState state = new EnrolState(context);
                state.setMqttPassword(password);
                Log.i(TAG, "MQTT credentials refreshed for user=" + username);
                showToast(context, context.getString(R.string.mqtt_refresh_ok, username));
              } catch (Exception e) {
                Log.e(TAG, "Failed to refresh MQTT credentials", e);
                showToast(
                    context,
                    context.getString(R.string.mqtt_refresh_failed, e.getMessage()));
              }
            })
        .start();
  }

  private static void showToast(final Context context, final String msg) {
    new android.os.Handler(context.getMainLooper())
        .post(() -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show());
  }
}
