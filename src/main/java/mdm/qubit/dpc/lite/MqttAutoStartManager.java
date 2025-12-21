package mdm.qubit.dpc.lite;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.util.Log;
import java.util.function.BooleanSupplier;
import mdm.qubit.dpc.EnrolState;

class MqttAutoStartManager {

  interface CredentialsApplier {
    void apply(String username, String qid, String password);
  }

  private static final String TAG = "MqttAutoStart";

  private final Context context;
  private final Handler handler;
  private final long retryDelayMs;
  private final int maxAttempts;
  private final BooleanSupplier vpnChecker;

  private Runnable autoStartRunnable;
  private int autoStartAttempts = 0;

  MqttAutoStartManager(
      Context context,
      Handler handler,
      long retryDelayMs,
      int maxAttempts,
      BooleanSupplier vpnChecker) {
    this.context = context;
    this.handler = handler;
    this.retryDelayMs = retryDelayMs;
    this.maxAttempts = maxAttempts;
    this.vpnChecker = vpnChecker;
  }

  void autoConfigureAndStartAfterEnrol(CredentialsApplier applier) {
    stopAutoStart();
    EnrolState enrolState = new EnrolState(context);
    String deviceId = enrolState.getDeviceId();
    String password = enrolState.getMqttPassword();
    if (isBlank(deviceId) || isBlank(password)) {
      Log.w(TAG, "Enrol completed but missing MQTT creds; will retry auto-start");
      scheduleAutoStartRetry(applier);
      return;
    }
    startWithCredentials(deviceId, password, applier, false);
  }

  void maybeAutoStartOnResume(CredentialsApplier applier) {
    String lastStatus = LiteMqttService.getLastStatus();
    if ("connecting".equals(lastStatus) || "connected".equals(lastStatus)) {
      return;
    }
    EnrolState enrolState = new EnrolState(context);
    String deviceId = enrolState.getDeviceId();
    String password = enrolState.getMqttPassword();
    if (isBlank(deviceId) || isBlank(password)) {
      return;
    }
    if (!vpnChecker.getAsBoolean()) {
      Log.i(TAG, "VPN not up; skip MQTT auto-start on resume");
      return;
    }
    startWithCredentials(deviceId, password, applier, true);
  }

  void stopAutoStart() {
    if (autoStartRunnable != null) {
      handler.removeCallbacks(autoStartRunnable);
      autoStartRunnable = null;
    }
    autoStartAttempts = 0;
  }

  private void startWithCredentials(
      String deviceId, String password, CredentialsApplier applier, boolean fromResume) {
    LiteMqttConfig cfg = new LiteMqttConfig(context);
    boolean changed = false;
    if (isBlank(cfg.getUsername())) {
      cfg.setUsername(deviceId);
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

    if (applier != null) {
      applier.apply(deviceId, deviceId, password);
    }

    if (changed) {
      Log.i(
          TAG,
          fromResume
              ? "Auto-filled lite MQTT creds from enrol state on resume"
              : "Auto-filled lite MQTT creds from enrol state");
    }

    String lastStatus = LiteMqttService.getLastStatus();
    if ("connecting".equals(lastStatus) || "connected".equals(lastStatus)) {
      Log.i(TAG, "MQTT already " + lastStatus + "; skipping auto-start");
      return;
    }

    Intent startIntent = new Intent(context, LiteMqttService.class);
    startIntent.setAction(LiteMqttService.ACTION_START);
    context.startService(startIntent);
  }

  private void scheduleAutoStartRetry(CredentialsApplier applier) {
    autoStartAttempts = 0;
    autoStartRunnable =
        new Runnable() {
          @Override
          public void run() {
            autoStartAttempts++;
            EnrolState enrolState = new EnrolState(context);
            String deviceId = enrolState.getDeviceId();
            String password = enrolState.getMqttPassword();
            if (!isBlank(deviceId) && !isBlank(password)) {
              Log.i(TAG, "MQTT creds available after enrol; auto-starting");
              autoConfigureAndStartAfterEnrol(applier);
              return;
            }
            if (autoStartAttempts >= maxAttempts) {
              Log.w(TAG, "MQTT auto-start retry exhausted; creds still missing");
              stopAutoStart();
              return;
            }
            handler.postDelayed(this, retryDelayMs);
          }
        };
    handler.postDelayed(autoStartRunnable, retryDelayMs);
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }
}
