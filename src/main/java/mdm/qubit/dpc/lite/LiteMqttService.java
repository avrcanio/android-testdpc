package mdm.qubit.dpc.lite;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.content.pm.ServiceInfo;
import androidx.core.app.NotificationCompat;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Lightweight MQTT service that delegates MQTT handling to {@link LiteMqttController}.
 */
public class LiteMqttService extends Service {

  public static final String ACTION_START = "mdm.qubit.dpc.lite.action.MQTT_START";
  public static final String ACTION_STOP = "mdm.qubit.dpc.lite.action.MQTT_STOP";
  public static final String ACTION_BOOT_START = "mdm.qubit.dpc.lite.action.MQTT_BOOT_START";
  public static final String ACTION_STATUS_BROADCAST =
      "mdm.qubit.dpc.lite.action.MQTT_STATUS";
  public static final String EXTRA_STATUS = "status";
  public static final String EXTRA_ERROR = "error";

  private static final long BOOT_VPN_TIMEOUT_MS = 60000L;
  private static final long BOOT_VPN_RETRY_MS = 5000L;
  private static final long FGS_REFRESH_INTERVAL_MS = 10 * 60 * 1000L; // 10 minutes

  // New channel to ensure silent/no-visual foreground notification behavior
  private static final String CHANNEL_ID = "lite_mqtt_silent";
  private static final int NOTIFICATION_ID = 2002;

  private LiteMqttController mqttController;
  private StatusReporter statusReporter;
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final ScheduledExecutorService bootExecutor =
      Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "lite-mqtt-boot"));
  private ScheduledFuture<?> bootVpnWaitTask;
  private ScheduledFuture<?> fgsRefreshTask;
  private long bootDeadlineMs = 0L;
  private volatile boolean inForeground = false;

  @Override
  public void onCreate() {
    super.onCreate();
    statusReporter = new StatusReporter(this);
    mqttController = new LiteMqttController(this);
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent == null || intent.getAction() == null) {
      return START_NOT_STICKY;
    }
    LiteLauncherHider.apply(this);
    String action = intent.getAction();
    logToFile("onStartCommand action=" + action);
    if (ACTION_START.equals(action)) {
      startClient();
    } else if (ACTION_STOP.equals(action)) {
      mqttController.stop(true);
      cancelForegroundRefresh();
      stopForegroundSafe();
      stopSelf();
    } else if (ACTION_BOOT_START.equals(action)) {
      startAfterVpn();
    }
    return START_STICKY;
  }

  @Override
  public void onDestroy() {
    mqttController.stop(true);
    mqttController.onDestroy();
    cancelBootVpnWait();
    cancelForegroundRefresh();
    try {
      bootExecutor.shutdownNow();
    } catch (Exception ignore) {
    }
    stopForegroundSafe();
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  private void startClient() {
    ensureForeground();
    scheduleForegroundRefresh();
    mqttController.start();
  }

  private void startAfterVpn() {
    bootDeadlineMs = System.currentTimeMillis() + BOOT_VPN_TIMEOUT_MS;
    checkVpnAndStart();
  }

  private void checkVpnAndStart() {
    cancelBootVpnWait();
    if (isVpnUp()) {
      broadcastStatus("vpn_detected", null);
      startClient();
      return;
    }
    if (System.currentTimeMillis() > bootDeadlineMs) {
      broadcastStatus("vpn_timeout", "VPN not up after boot");
      stopSelf();
      return;
    }
    bootVpnWaitTask =
        bootExecutor.schedule(this::checkVpnAndStart, BOOT_VPN_RETRY_MS, TimeUnit.MILLISECONDS);
    broadcastStatus("vpn_wait", "Waiting for VPN to start MQTT");
  }

  private void ensureForeground() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      NotificationChannel channel =
          new NotificationChannel(CHANNEL_ID, "Lite MQTT", NotificationManager.IMPORTANCE_MIN);
      channel.setSound(null, null);
      channel.enableVibration(false);
      channel.enableLights(false);
      NotificationManager nm = getSystemService(NotificationManager.class);
      if (nm != null) {
        nm.createNotificationChannel(channel);
      }
    }
    Notification notification =
        new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Lite MQTT")
            .setContentText("Maintaining MQTT connection")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOngoing(true)
            .build();
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(
          NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    } else {
      startForeground(NOTIFICATION_ID, notification);
    }
    inForeground = true;
    logToFile("FGS started");
  }

  void logToFile(String msg) {
    statusReporter.logToFile(msg);
  }

  void broadcastStatus(String status, String error) {
    statusReporter.broadcastStatus(status, error);
  }

  public static String getLastStatus() {
    return StatusReporter.getLastStatus();
  }

  public static String getLastError() {
    return StatusReporter.getLastError();
  }

  private void cancelBootVpnWait() {
    if (bootVpnWaitTask != null) {
      bootVpnWaitTask.cancel(true);
      bootVpnWaitTask = null;
    }
  }

  private void scheduleForegroundRefresh() {
    cancelForegroundRefresh();
    fgsRefreshTask =
        bootExecutor.scheduleAtFixedRate(
            () -> {
              if (!inForeground) {
                return;
              }
              mainHandler.post(
                  () -> {
                    try {
                      stopForeground(false);
                      ensureForeground();
                      logToFile("FGS refreshed");
                    } catch (Exception ignore) {
                      // best-effort refresh to avoid FGS timeout
                    }
                  });
            },
            FGS_REFRESH_INTERVAL_MS,
            FGS_REFRESH_INTERVAL_MS,
            TimeUnit.MILLISECONDS);
    logToFile("FGS refresh scheduled every " + FGS_REFRESH_INTERVAL_MS + "ms");
  }

  private void cancelForegroundRefresh() {
    if (fgsRefreshTask != null) {
      fgsRefreshTask.cancel(true);
      fgsRefreshTask = null;
      logToFile("FGS refresh canceled");
    }
  }

  private void stopForegroundSafe() {
    try {
      stopForeground(true);
    } catch (Exception ignore) {
      // ignore
    } finally {
      inForeground = false;
      logToFile("FGS stopped");
    }
  }

  private boolean isVpnUp() {
    ConnectivityManager cm = getSystemService(ConnectivityManager.class);
    if (cm == null) {
      return false;
    }
    Network active = cm.getActiveNetwork();
    if (active == null) {
      return false;
    }
    NetworkCapabilities caps = cm.getNetworkCapabilities(active);
    return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
  }

}
