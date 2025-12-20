package mdm.qubit.dpc.lite;

import android.os.PowerManager;

final class WakeLockGuard {
  private final LiteMqttService service;
  private final PowerManager.WakeLock wakeLock;

  WakeLockGuard(LiteMqttService service) {
    this.service = service;
    PowerManager pm = service.getSystemService(PowerManager.class);
    if (pm != null) {
      wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LiteMqttService:mqtt_connection");
      wakeLock.setReferenceCounted(false);
    } else {
      wakeLock = null;
      service.logToFile("WakeLock unavailable: PowerManager null");
    }
  }

  void acquire(long timeoutMs) {
    if (timeoutMs <= 0 || wakeLock == null) {
      return;
    }
    try {
      if (wakeLock.isHeld()) {
        try {
          wakeLock.release();
        } catch (Exception e) {
          service.logToFile("WakeLock release error (pre-acquire): " + e.getMessage());
        }
      }
      wakeLock.acquire(timeoutMs);
      service.logToFile("WakeLock acquired for " + timeoutMs + "ms");
    } catch (Exception e) {
      service.logToFile("WakeLock acquire error: " + e.getMessage());
    }
  }

  void releaseIfHeld() {
    if (wakeLock != null && wakeLock.isHeld()) {
      try {
        wakeLock.release();
        service.logToFile("WakeLock released");
      } catch (Exception e) {
        service.logToFile("WakeLock release error: " + e.getMessage());
      }
    }
  }
}
