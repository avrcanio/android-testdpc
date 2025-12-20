package mdm.qubit.dpc.lite;

import android.util.Log;
import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.MqttClientState;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5ClientBuilder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import mdm.qubit.dpc.EnrolState;
import mdm.qubit.dpc.mdm.MdmSyncManager;

final class LiteMqttController {
  private static final String TAG = "LiteMqttController";
  private static final long SESSION_EXPIRY_SECONDS = 24 * 60 * 60;
  private static final int KEEP_ALIVE_SECONDS = 60;
  private static final long HEARTBEAT_INTERVAL_SECONDS = 120L;
  private static final long CONNECTION_WATCHDOG_SECONDS = 20L;
  private static final long MAX_RECONNECT_DELAY_MS = 15000L;
  private static final long FORCED_RECONNECT_TIMEOUT_MS = 60000L;

  private final LiteMqttService service;
  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "lite-mqtt"));
  private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
  private final AtomicBoolean connecting = new AtomicBoolean(false);
  private final AtomicBoolean destroying = new AtomicBoolean(false);
  private final AtomicBoolean manualStop = new AtomicBoolean(false);
  private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);
  private final WakeLockGuard wakeLockGuard;

  private Mqtt5AsyncClient client;
  private ScheduledFuture<?> heartbeatTask;
  private ScheduledFuture<?> reconnectTask;
  private ScheduledFuture<?> connectionWatchdogTask;
  private ScheduledFuture<?> forcedReconnectTask;
  private long connectionStartTimeMs = 0;
  private boolean publishHandlerRegistered = false;

  LiteMqttController(LiteMqttService service) {
    this.service = service;
    this.wakeLockGuard = new WakeLockGuard(service);
  }

  void start() {
    manualStop.set(false);
    if (isClientConnectingOrConnected() || connecting.get()) {
      service.broadcastStatus("connecting", null);
      service.logToFile("MQTT start: already connecting/connected, skip");
      return;
    }
    if (!connecting.compareAndSet(false, true)) {
      service.broadcastStatus("connecting", null);
      service.logToFile("MQTT start: connecting flag already set, skip");
      return;
    }
    startConnectionWatchdog();
    startForcedReconnectWatchdog();
    service.logToFile("MQTT start: connecting...");
    reconnectAttempts.set(0);
    connectWithBackoff();
  }

  void stop(boolean manual) {
    manualStop.set(manual);
    wakeLockGuard.releaseIfHeld();
    cancelReconnectTask();
    cancelConnectionWatchdog();
    cancelForcedReconnectTask();
    connecting.set(false);
    reconnectAttempts.set(0);
    cancelHeartbeat();
    publishHandlerRegistered = false;
    Mqtt5AsyncClient clientRef = client;
    client = null;
    if (clientRef != null) {
      clientRef
          .disconnect()
          .whenComplete(
              (v, t) -> {
                if (t != null) {
                  Log.w(TAG, "MQTT disconnect failed", t);
                  service.logToFile("MQTT disconnect failed: " + t.getMessage());
                }
                service.logToFile("MQTT stopped");
                service.broadcastStatus("stopped", null);
              });
    } else {
      service.broadcastStatus("stopped", null);
      service.logToFile("MQTT stopped (no client)");
    }
  }

  void onDestroy() {
    destroying.set(true);
    stop(true);
    try {
      executor.shutdownNow();
    } catch (Exception ignore) {
    }
  }

  private void connectWithBackoff() {
    // connecting flag set by caller
    reconnectScheduled.set(false);
    try {
      executor.execute(
          () -> {
            wakeLockGuard.acquire(60000L);
            LiteMqttConfig config = new LiteMqttConfig(service);
            EnrolState enrolState = new EnrolState(service);
            try {
              if (client == null) {
                Mqtt5ClientBuilder builder =
                    MqttClient.builder()
                        .useMqttVersion5()
                        .identifier(config.getClientId())
                        .serverHost(config.getHost())
                        .serverPort(config.getPort())
                        .addDisconnectedListener(
                            ctx -> {
                              String reason =
                                  ctx.getCause() != null
                                      ? ctx.getCause().getMessage()
                                      : ctx.getSource().name();
                              service.logToFile("MQTT disconnected: " + reason);
                              service.broadcastStatus("disconnected", reason);
                              connecting.set(false);
                              if (!destroying.get() && !manualStop.get()) {
                                scheduleReconnect();
                              }
                            });
                builder.webSocketConfig().serverPath(config.getPath()).applyWebSocketConfig();
                if (config.isTlsEnabled()) {
                  builder = builder.sslWithDefaultConfig();
                }
                client = builder.buildAsync();
              }
              service.broadcastStatus("connecting", null);
              String username =
                  !isBlank(config.getUsername()) ? config.getUsername() : enrolState.getDeviceId();
              String password =
                  !isBlank(config.getPassword())
                      ? config.getPassword()
                      : enrolState.getMqttPassword();
              connectionStartTimeMs = System.currentTimeMillis();
              client
                  .connectWith()
                  .cleanStart(false)
                  .sessionExpiryInterval(SESSION_EXPIRY_SECONDS)
                  .keepAlive(KEEP_ALIVE_SECONDS)
                  .simpleAuth()
                  .username(isBlank(username) ? "" : username)
                  .password(
                      isBlank(password)
                          ? null
                          : password.getBytes(StandardCharsets.UTF_8))
                  .applySimpleAuth()
                  .send()
                  .whenComplete(
                      (ack, error) -> {
                        if (error != null) {
                          Log.w(TAG, "MQTT connect failed", error);
                          service.logToFile("MQTT connect failed: " + error.getMessage());
                          service.broadcastStatus("error", error.getMessage());
                          connecting.set(false);
                          if (!destroying.get() && !manualStop.get()) {
                            scheduleReconnect();
                          }
                          wakeLockGuard.releaseIfHeld();
                          return;
                        }
                        Log.i(TAG, "MQTT connected");
                        service.logToFile("MQTT connected ok");
                        reconnectAttempts.set(0);
                        connecting.set(false);
                        cancelReconnectTask();
                        service.broadcastStatus("connected", null);
                        subscribeNotify(enrolState.getDeviceId());
                        registerMessageHandler();
                        startHeartbeat(config, enrolState.getDeviceId());
                        wakeLockGuard.releaseIfHeld();
                      });
            } catch (Exception e) {
              Log.w(TAG, "MQTT connection setup failed", e);
              service.broadcastStatus("error", e.getMessage());
              connecting.set(false);
              if (!destroying.get() && !manualStop.get()) {
                scheduleReconnect();
              }
              wakeLockGuard.releaseIfHeld();
            }
          });
    } catch (RejectedExecutionException e) {
      connecting.set(false);
      reconnectScheduled.set(false);
      service.logToFile("MQTT connectWithBackoff rejected: executor is shutdown");
    } catch (Exception e) {
      connecting.set(false);
      reconnectScheduled.set(false);
      service.logToFile("MQTT connectWithBackoff schedule failed: " + e.getMessage());
    }
  }

  private boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  private void scheduleReconnect() {
    if (destroying.get() || manualStop.get()) {
      return;
    }
    if (client != null && client.getState() == MqttClientState.CONNECTED) {
      service.logToFile("MQTT reconnect skipped: client already connected");
      return;
    }
    if (connecting.get()) {
      service.logToFile("MQTT reconnect skipped: already connecting");
      return;
    }
    if (!reconnectScheduled.compareAndSet(false, true)) {
      service.logToFile("MQTT reconnect skipped: already scheduled");
      return;
    }
    cancelReconnectTaskFutureOnly();
    int attempt = reconnectAttempts.incrementAndGet();
    // Exponential backoff with jitter: base 500ms, capped at 15s
    int exp = Math.max(0, Math.min(10, attempt - 1));
    long base = 500L * (1L << exp);
    double jitter = ThreadLocalRandom.current().nextDouble(0.5, 1.0);
    long delay = Math.min(MAX_RECONNECT_DELAY_MS, (long) (base * jitter));
    service.broadcastStatus("reconnecting", "retry in " + delay + "ms");
    service.logToFile(
        "MQTT reconnect attempt "
            + attempt
            + " in "
            + delay
            + "ms (base="
            + base
            + " jitter="
            + jitter
            + ")");
    try {
      reconnectTask = executor.schedule(this::connectWithBackoff, delay, TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException e) {
      reconnectScheduled.set(false);
      service.logToFile("MQTT reconnect rejected: executor is shutdown");
    } catch (Exception e) {
      reconnectScheduled.set(false);
      service.logToFile("MQTT reconnect schedule failed: " + e.getMessage());
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
    if (manualStop.get() || destroying.get()) {
      return;
    }
    if (client == null || client.getState() != MqttClientState.CONNECTED || connecting.get()) {
      service.logToFile("Heartbeat skipped: client not connected, scheduling reconnect");
      scheduleReconnect();
      return;
    }
    wakeLockGuard.acquire(15000L);
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
                  service.logToFile("Heartbeat failed: " + err.getMessage());
                  service.broadcastStatus("heartbeat_error", err.getMessage());
                  if (!destroying.get() && !manualStop.get()) {
                    scheduleReconnect();
                  }
                } else {
                  service.broadcastStatus("heartbeat", null);
                  service.logToFile("Heartbeat ok to " + topic);
                }
                wakeLockGuard.releaseIfHeld();
          });
    } catch (Exception e) {
      Log.w(TAG, "Heartbeat publish error", e);
      service.broadcastStatus("heartbeat_error", e.getMessage());
      service.logToFile("Heartbeat exception: " + e.getMessage());
      if (!destroying.get() && !manualStop.get()) {
        scheduleReconnect();
      }
      wakeLockGuard.releaseIfHeld();
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
      service.broadcastStatus("subscribe_skipped", "missing deviceId");
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
                service.broadcastStatus("subscribe_error", error.getMessage());
              } else {
                service.broadcastStatus("subscribed", topic);
                service.logToFile("Subscribed to " + topic);
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
                service.logToFile("Notify received on " + topic);
                triggerInboxSync("notify");
              }
            });
    publishHandlerRegistered = true;
  }

  private void triggerInboxSync(String reason) {
    service.broadcastStatus("sync", reason);
    service.logToFile("Trigger inbox sync: " + reason);
    wakeLockGuard.acquire(120000L);
    try {
      MdmSyncManager.syncNow(
          service,
          (success, message) -> {
            if (!success) {
              Log.w(TAG, "Inbox sync failed: " + message);
              service.logToFile("Inbox sync failed: " + message);
            } else {
              service.logToFile("Inbox sync ok");
            }
            wakeLockGuard.releaseIfHeld();
          });
    } catch (Exception e) {
      wakeLockGuard.releaseIfHeld();
      throw e;
    }
  }

  private void cancelReconnectTask() {
    if (reconnectTask != null) {
      reconnectTask.cancel(true);
      reconnectTask = null;
    }
    reconnectScheduled.set(false);
  }

  private void cancelReconnectTaskFutureOnly() {
    if (reconnectTask != null) {
      reconnectTask.cancel(true);
      reconnectTask = null;
    }
  }

  private void cancelConnectionWatchdog() {
    if (connectionWatchdogTask != null) {
      connectionWatchdogTask.cancel(true);
      connectionWatchdogTask = null;
    }
  }

  private boolean isClientConnectingOrConnected() {
    if (client == null) {
      return false;
    }
    MqttClientState state = client.getState();
    return state == MqttClientState.CONNECTED || state == MqttClientState.CONNECTING;
  }

  private void startConnectionWatchdog() {
    if (connectionWatchdogTask != null && !connectionWatchdogTask.isCancelled()) {
      return;
    }
    connectionWatchdogTask =
        executor.scheduleAtFixedRate(
            () -> {
              MqttClientState state = client != null ? client.getState() : null;
              if (state == MqttClientState.CONNECTED) {
                return;
              }
              if (state == MqttClientState.CONNECTING) {
                return; // client library already connecting
              }
              if (connecting.get()) {
                return;
              }
              service.logToFile(
                  "Watchdog: client not connected, state=" + state + " scheduling reconnect");
              scheduleReconnect();
            },
            CONNECTION_WATCHDOG_SECONDS,
            CONNECTION_WATCHDOG_SECONDS,
            TimeUnit.SECONDS);
  }

  private void startForcedReconnectWatchdog() {
    cancelForcedReconnectTask();
    forcedReconnectTask =
        executor.schedule(
            this::checkAndForceReconnectIfNeeded,
            FORCED_RECONNECT_TIMEOUT_MS,
            TimeUnit.MILLISECONDS);
    service.logToFile("Started forced reconnect watchdog");
  }

  private void checkAndForceReconnectIfNeeded() {
    if (destroying.get()) {
      return;
    }
    if (connectionStartTimeMs == 0) {
      connectionStartTimeMs = System.currentTimeMillis();
      return;
    }
    long elapsedMs = System.currentTimeMillis() - connectionStartTimeMs;
    MqttClientState state = client != null ? client.getState() : null;
    if (state != MqttClientState.CONNECTED
        && !destroying.get()
        && !manualStop.get()
        && elapsedMs > FORCED_RECONNECT_TIMEOUT_MS) {
      service.logToFile("MQTT still not connected after " + elapsedMs + "ms, forcing reconnect");
      service.broadcastStatus("forced_reconnect", "Connection timeout, retrying...");
      scheduleReconnect();
    }
    if (!destroying.get()) {
      cancelForcedReconnectTask();
      forcedReconnectTask =
          executor.schedule(
              this::checkAndForceReconnectIfNeeded,
              FORCED_RECONNECT_TIMEOUT_MS,
              TimeUnit.MILLISECONDS);
    }
  }

  private void cancelForcedReconnectTask() {
    if (forcedReconnectTask != null && !forcedReconnectTask.isCancelled()) {
      forcedReconnectTask.cancel(true);
      forcedReconnectTask = null;
    }
  }

  private void cancelHeartbeat() {
    if (heartbeatTask != null) {
      heartbeatTask.cancel(true);
      heartbeatTask = null;
    }
  }
}
