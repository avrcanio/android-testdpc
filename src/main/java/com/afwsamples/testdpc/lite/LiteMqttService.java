package com.afwsamples.testdpc.lite;

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
import android.os.PowerManager;
import android.content.pm.ServiceInfo;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import com.afwsamples.testdpc.FileLogger;
import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.MqttClientState;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient;
import com.afwsamples.testdpc.EnrolState;
import com.afwsamples.testdpc.mdm.MdmSyncManager;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lightweight MQTT service that connects over WSS using HiveMQ MQTT 5 async client.
 *
 * <p>Configuration is loaded from {@link LiteMqttConfig}. Status updates are broadcast via {@link
 * #ACTION_STATUS_BROADCAST}.
 */
public class LiteMqttService extends Service {

  public static final String ACTION_START = "com.afwsamples.testdpc.lite.action.MQTT_START";
  public static final String ACTION_STOP = "com.afwsamples.testdpc.lite.action.MQTT_STOP";
  public static final String ACTION_BOOT_START = "com.afwsamples.testdpc.lite.action.MQTT_BOOT_START";
  public static final String ACTION_STATUS_BROADCAST =
      "com.afwsamples.testdpc.lite.action.MQTT_STATUS";
  public static final String EXTRA_STATUS = "status";
  public static final String EXTRA_ERROR = "error";

  private static final String TAG = "LiteMqttService";
  private static final long SESSION_EXPIRY_SECONDS = 24 * 60 * 60;
  private static final int KEEP_ALIVE_SECONDS = 120;
  private static final long HEARTBEAT_INTERVAL_SECONDS = 60L;
  private static final long BOOT_VPN_TIMEOUT_MS = 60000L;
  private static final long BOOT_VPN_RETRY_MS = 5000L;


  private static final String CHANNEL_ID = "lite_mqtt";
  private static final int NOTIFICATION_ID = 2002;
  private static volatile String sLastStatus = null;
  private static volatile String sLastError = null;
  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "lite-mqtt"));
  private final AtomicInteger reconnectAttempts = new AtomicInteger(0);

  private Mqtt5AsyncClient client;
  private ScheduledFuture<?> heartbeatTask;

  private ScheduledFuture<?> bootVpnWaitTask;
  private long bootDeadlineMs = 0L;
  private boolean publishHandlerRegistered = false;
  private PowerManager.WakeLock wakeLock;

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent == null || intent.getAction() == null) {
      return START_NOT_STICKY;
    }
    String action = intent.getAction();
    if (ACTION_START.equals(action)) {
      startClient();
    } else if (ACTION_STOP.equals(action)) {
      stopClient();
      stopSelf();
    } else if (ACTION_BOOT_START.equals(action)) {
      startAfterVpn();
    }
    return START_STICKY;
  }

  @Override
  public void onDestroy() {
    stopClient();
    executor.shutdownNow();
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  private void startClient() {
    ensureForeground();
    acquireWakeLock();
    if (client != null && client.getState() == MqttClientState.CONNECTED) {
      broadcastStatus("connected", null);
      logToFile("MQTT startClient: already connected");
      return;
    }
    logToFile("MQTT startClient: connecting...");
    reconnectAttempts.set(0);
    connectWithBackoff();
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
        executor.schedule(this::checkVpnAndStart, BOOT_VPN_RETRY_MS, TimeUnit.MILLISECONDS);
    broadcastStatus("vpn_wait", "Waiting for VPN to start MQTT");
  }

  private void connectWithBackoff() {
    executor.execute(
        () -> {
          LiteMqttConfig config = new LiteMqttConfig(this);
          EnrolState enrolState = new EnrolState(this);
          try {
            if (client == null) {
              client =
                  MqttClient.builder()
                      .useMqttVersion5()
                      .identifier(config.getClientId())
                      .serverHost(config.getHost())
                      .serverPort(config.getPort())
                      .webSocketConfig()
                      .serverPath(config.getPath())
                      .applyWebSocketConfig()
                      .sslWithDefaultConfig()
                      .buildAsync();
              if (!config.isTlsEnabled()) {
                // recreate without SSL if TLS disabled
                client =
                    MqttClient.builder()
                        .useMqttVersion5()
                        .identifier(config.getClientId())
                        .serverHost(config.getHost())
                        .serverPort(config.getPort())
                        .webSocketConfig()
                        .serverPath(config.getPath())
                        .applyWebSocketConfig()
                        .buildAsync();
              }
            }
            broadcastStatus("connecting", null);
            client
                .connectWith()
                .cleanStart(false)
                .sessionExpiryInterval(SESSION_EXPIRY_SECONDS)
                .keepAlive(KEEP_ALIVE_SECONDS)
                .simpleAuth()
                .username(config.getUsername() == null ? "" : config.getUsername())
                .password(
                    config.getPassword() == null
                        ? null
                        : config.getPassword().getBytes(StandardCharsets.UTF_8))
                .applySimpleAuth()
                .send()
                .whenComplete(
                    (ack, error) -> {
                      if (error != null) {
                        Log.w(TAG, "MQTT connect failed", error);
                        logToFile("MQTT connect failed: " + error.getMessage());
                        broadcastStatus("error", error.getMessage());
                        scheduleReconnect();
                        return;
                      }
                      Log.i(TAG, "MQTT connected");
                      logToFile("MQTT connected ok");
                      reconnectAttempts.set(0);
                      broadcastStatus("connected", null);
                      subscribeNotify(enrolState.getDeviceId());
                      registerMessageHandler();
                      startHeartbeat(config, enrolState.getDeviceId());
                    });
          } catch (Exception e) {
            Log.w(TAG, "MQTT connection setup failed", e);
            broadcastStatus("error", e.getMessage());
            scheduleReconnect();
          }
        });
  }

  private void scheduleReconnect() {
    int attempt = reconnectAttempts.incrementAndGet();
    long delay = Math.min(30000L, 2000L * attempt);
    broadcastStatus("reconnecting", "retry in " + delay + "ms");
    logToFile("MQTT reconnect attempt " + attempt + " in " + delay + "ms");
    executor.schedule(this::connectWithBackoff, delay, TimeUnit.MILLISECONDS);
  }

  private void ensureForeground() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      NotificationChannel channel =
          new NotificationChannel(CHANNEL_ID, "Lite MQTT", NotificationManager.IMPORTANCE_LOW);
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
            .build();
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(
          NOTIFICATION_ID,
          notification,
          ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    } else {
      startForeground(NOTIFICATION_ID, notification);
    }
  }

  private void stopClient() {
    releaseWakeLock();
    
    cancelBootVpnWait();
    if (heartbeatTask != null) {
      heartbeatTask.cancel(true);
      heartbeatTask = null;
    }
    if (client != null) {
      client
          .disconnect()
          .whenComplete(
              (v, t) -> {
                if (t != null) {
                  Log.w(TAG, "MQTT disconnect failed", t);
                  logToFile("MQTT disconnect failed: " + t.getMessage());
                }
                logToFile("MQTT stopped");
                broadcastStatus("stopped", null);
              });
    } else {
      broadcastStatus("stopped", null);
      logToFile("MQTT stopped (no client)");
    }
  }

  private void startHeartbeat(LiteMqttConfig config, String deviceId) {
    if (heartbeatTask != null && !heartbeatTask.isCancelled()) {
      return;
    }
    heartbeatTask =
        executor.scheduleAtFixedRate(
            () -> sendHeartbeat(config, deviceId),
            HEARTBEAT_INTERVAL_SECONDS,
            HEARTBEAT_INTERVAL_SECONDS,
            TimeUnit.SECONDS);
  }

  private void sendHeartbeat(LiteMqttConfig config, String deviceId) {
    if (client == null || client.getState() != MqttClientState.CONNECTED) {
      scheduleReconnect();
      return;
    }
    String topic = heartbeatTopic(deviceId);
    try {
      client
          .publishWith()
          .topic(topic)
          .qos(MqttQos.AT_LEAST_ONCE)
          .payload(
              ("{\"status\":\"ok\",\"ts\":" + System.currentTimeMillis() + "}").getBytes(
                  StandardCharsets.UTF_8))
          .send()
          .whenComplete(
              (ack, err) -> {
                if (err != null) {
                  Log.w(TAG, "Heartbeat publish failed", err);
                  logToFile("Heartbeat failed: " + err.getMessage());
                  broadcastStatus("heartbeat_error", err.getMessage());
                  scheduleReconnect();
                } else {
                  broadcastStatus("heartbeat", null);
                  logToFile("Heartbeat ok to " + topic);
                }
              });
    } catch (Exception e) {
      Log.w(TAG, "Heartbeat publish error", e);
      broadcastStatus("heartbeat_error", e.getMessage());
      logToFile("Heartbeat exception: " + e.getMessage());
      scheduleReconnect();
    }
  }

  private String heartbeatTopic(String deviceId) {
    if (deviceId != null && !deviceId.isEmpty()) {
      return "mdm/" + deviceId + "/state";
    }
    return "mdm/unknown/state";
  }

  private void subscribeNotify(String deviceId) {
    if (client == null || client.getState() != MqttClientState.CONNECTED) {
      return;
    }
    if (deviceId == null || deviceId.isEmpty()) {
      broadcastStatus("subscribe_skipped", "missing deviceId");
      return;
    }
    String topic = "mdm/" + deviceId + "/notify";
    client
        .subscribeWith()
        .topicFilter(topic)
        .qos(MqttQos.AT_LEAST_ONCE)
        .send()
        .whenComplete(
            (subAck, error) -> {
              if (error != null) {
                Log.w(TAG, "MQTT subscribe failed for " + topic, error);
                broadcastStatus("subscribe_error", error.getMessage());
              } else {
                broadcastStatus("subscribed", topic);
                logToFile("Subscribed to " + topic);
                triggerInboxSync("subscribe_ack");
              }
            });
  }

  private void registerMessageHandler() {
    if (client == null || publishHandlerRegistered) {
      return;
    }
    client
        .publishes(
            com.hivemq.client.mqtt.MqttGlobalPublishFilter.ALL,
            publish -> {
              String topic = publish.getTopic().toString();
              if (topic.endsWith("/notify")) {
                logToFile("Notify received on " + topic);
                triggerInboxSync("notify");
              }
            });
    publishHandlerRegistered = true;
  }

  private void triggerInboxSync(String reason) {
    broadcastStatus("sync", reason);
    logToFile("Trigger inbox sync: " + reason);
    MdmSyncManager.syncNow(
        this,
        (success, message) -> {
          if (!success) {
            Log.w(TAG, "Inbox sync failed: " + message);
            logToFile("Inbox sync failed: " + message);
          } else {
            logToFile("Inbox sync ok");
          }
        });
  }

  private void logToFile(String msg) {
    try {
      FileLogger.log(this, "LiteMqttService: " + msg);
    } catch (Exception ignore) {
      // best-effort logging
    }
  }

  private void broadcastStatus(String status, String error) {
    sLastStatus = status;
    sLastError = error;
    Intent intent = new Intent(ACTION_STATUS_BROADCAST);
    intent.putExtra(EXTRA_STATUS, status);
    if (error != null) {
      intent.putExtra(EXTRA_ERROR, error);
    }
    sendBroadcast(intent);
  }

  public static String getLastStatus() {
    return sLastStatus;
  }

  public static String getLastError() {
    return sLastError;
  }



  private void cancelBootVpnWait() {
    if (bootVpnWaitTask != null) {
      bootVpnWaitTask.cancel(true);
      bootVpnWaitTask = null;
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

  private void acquireWakeLock() {
    if (wakeLock == null || !wakeLock.isHeld()) {
      PowerManager pm = getSystemService(PowerManager.class);
      if (pm != null) {
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "LiteMqttService:mqtt_connection");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
        logToFile("WakeLock acquired");
      }
    }
  }

  private void releaseWakeLock() {
    if (wakeLock != null && wakeLock.isHeld()) {
      wakeLock.release();
      logToFile("WakeLock released");
    }
  }
}
