package mdm.qubit.dpc.lite;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import mdm.qubit.dpc.EnrolState;

/** Helper to sync enrol MQTT credentials into lite config and start the MQTT service. */
public final class LiteMqttAutoConfigurator {

  private static final String TAG = "LiteMqttAutoConfig";

  private LiteMqttAutoConfigurator() {}

  public static boolean applyFromEnrol(
      Context context, String usernameOverride, String passwordOverride) {
    EnrolState enrolState = new EnrolState(context);
    String deviceId = enrolState.getDeviceId();
    String username = !isBlank(usernameOverride) ? usernameOverride : deviceId;
    String password =
        !isBlank(passwordOverride) ? passwordOverride : enrolState.getMqttPassword();

    if (isBlank(username) || isBlank(password)) {
      Log.w(TAG, "Missing enrol MQTT credentials; skip apply");
      return false;
    }

    LiteMqttConfig cfg = new LiteMqttConfig(context);
    boolean changed = false;
    if (isBlank(cfg.getUsername())) {
      cfg.setUsername(username);
      changed = true;
    }
    if (isBlank(cfg.getQid())) {
      cfg.setQid(deviceId);
      changed = true;
    }
    if (isBlank(cfg.getPassword())) {
      cfg.setPassword(password);
      changed = true;
    }
    String storedClientId = cfg.getStoredClientId();
    if (!isBlank(deviceId)
        && (isBlank(storedClientId) || storedClientId.startsWith("lite-"))) {
      cfg.setClientId(deviceId);
      changed = true;
    }
    return changed;
  }

  public static void startService(Context context) {
    Intent startIntent = new Intent(context, LiteMqttService.class);
    startIntent.setAction(LiteMqttService.ACTION_START);
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(startIntent);
      } else {
        context.startService(startIntent);
      }
    } catch (Exception e) {
      Log.w(TAG, "Failed to start LiteMqttService", e);
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }
}
