package mdm.qubit.dpc.lite;

import android.content.Intent;
import android.util.Log;
import mdm.qubit.dpc.FileLogger;

/**
 * Centralized status + log helper to keep file logs and broadcasts consistent.
 */
final class StatusReporter {
  private final LiteMqttService service;
  private static volatile String sLastStatus = null;
  private static volatile String sLastError = null;

  StatusReporter(LiteMqttService service) {
    this.service = service;
  }

  void logToFile(String msg) {
    try {
      String line = "LiteMqttService: " + msg;
      FileLogger.log(service, line);
      FileLogger.logToDownload(service, "mqtt_logs.txt", line);
    } catch (Exception ignore) {
      // best-effort logging
    }
  }

  void broadcastStatus(String status, String error) {
    sLastStatus = status;
    sLastError = error;
    Log.d("LiteMqttStatus", "broadcast status=" + status + (error != null ? " error=" + error : ""));
    LauncherIconSwitcher.apply(service, status);
    Intent intent = new Intent(LiteMqttService.ACTION_STATUS_BROADCAST);
    intent.putExtra(LiteMqttService.EXTRA_STATUS, status);
    if (error != null) {
      intent.putExtra(LiteMqttService.EXTRA_ERROR, error);
    }
    service.sendBroadcast(intent);
    logToFile("status=" + status + (error != null ? " error=" + error : ""));
  }

  static String getLastStatus() {
    return sLastStatus;
  }

  static String getLastError() {
    return sLastError;
  }
}
