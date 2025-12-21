package mdm.qubit.dpc.lite;

import android.os.Handler;
import java.util.function.BooleanSupplier;

/**
 * Polls for VPN connectivity with timeout and notifies listener.
 */
class VpnWatcher {

  interface Listener {
    void onVpnUp();

    void onTimeout();

    void onWaiting();
  }

  private final Handler handler;
  private final long timeoutMs;
  private final long pollIntervalMs;
  private final BooleanSupplier vpnChecker;

  private long deadlineMs = 0L;
  private Listener listener;
  private final Runnable pollRunnable =
      new Runnable() {
        @Override
        public void run() {
          poll();
        }
      };

  VpnWatcher(
      Handler handler, long timeoutMs, long pollIntervalMs, BooleanSupplier vpnChecker) {
    this.handler = handler;
    this.timeoutMs = timeoutMs;
    this.pollIntervalMs = pollIntervalMs;
    this.vpnChecker = vpnChecker;
  }

  long start(long existingDeadlineMs, Listener listener) {
    stop();
    this.listener = listener;
    deadlineMs =
        existingDeadlineMs != 0L ? existingDeadlineMs : System.currentTimeMillis() + timeoutMs;
    handler.post(pollRunnable);
    return deadlineMs;
  }

  void stop() {
    handler.removeCallbacks(pollRunnable);
    listener = null;
    deadlineMs = 0L;
  }

  long getDeadlineMs() {
    return deadlineMs;
  }

  private void poll() {
    if (listener == null) {
      return;
    }
    if (vpnChecker.getAsBoolean()) {
      Listener l = listener;
      stop();
      l.onVpnUp();
      return;
    }
    if (System.currentTimeMillis() > deadlineMs) {
      Listener l = listener;
      stop();
      l.onTimeout();
      return;
    }
    listener.onWaiting();
    handler.postDelayed(pollRunnable, pollIntervalMs);
  }
}
