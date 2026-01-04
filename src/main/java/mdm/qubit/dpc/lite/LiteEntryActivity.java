package mdm.qubit.dpc.lite;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import org.json.JSONObject;
import mdm.qubit.dpc.EnrolApiClient;
import mdm.qubit.dpc.EnrolConfig;
import mdm.qubit.dpc.EnrolState;
import mdm.qubit.dpc.R;
import mdm.qubit.dpc.DeviceAdminReceiver;
import mdm.qubit.dpc.mdm.MdmSyncManager;
import mdm.qubit.dpc.lite.LiteMqttConfig;
import mdm.qubit.dpc.lite.LiteMqttService;

/**
 * Minimal launcher for field devices: shows enrol info and triggers enrol call.
 */
public class LiteEntryActivity extends Activity {

  private static final String TAG = "LiteEntryActivity";
  private static final String STATE_STEP = "enrol_all_step";
  private static final String STATE_VPN_DEADLINE = "vpn_deadline";
  private static final int REQ_POST_NOTIFICATIONS = 1001;
  private enum EnrolAllStep {
    IDLE,
    CLEARING_TAILSCALE,
    REFRESHING_EXTRAS,
    WAITING_FOR_VPN,
    ENROLLING,
    COMPLETED
  }

  private static final String TAILSCALE_PKG = "com.tailscale.ipn";
  private static final long VPN_TIMEOUT_MS = 90000L;
  private static final long VPN_POLL_INTERVAL_MS = 3000L;
  private static final long MQTT_AUTOSTART_RETRY_MS = 2000L;
  private static final int MQTT_AUTOSTART_MAX_ATTEMPTS = 15;

  private TextView mDeviceIdView;
  private TextView mEnrolTokenView;
  private Button mEnrolAllButton;
  private EditText mqttHostField;
  private EditText mqttPortField;
  private EditText mqttPathField;
  private EditText mqttUserField;
  private EditText mqttPassField;
  private EditText mqttQidField;
  private EditText mqttClientIdField;
  private CheckBox mqttTlsField;
  private CheckBox mqttEditToggle;
  private TextView mqttStatusView;
  private BroadcastReceiver mqttStatusReceiver;
  private Button mTailscaleButton;
  private boolean mqttUiInitialized = false;
  private Handler mHandler;
  private MqttAutoStartManager mMqttAutoStartManager;
  private boolean mTailscaleBusy = false;
  private Runnable mAuthKeyCleanupRunnable;
  private EnrolAllStep mEnrolAllStep = EnrolAllStep.IDLE;
  private long mVpnWaitDeadlineMs = 0L;
  private VpnWatcher mVpnWatcher;
  private boolean mAuthKeyCleared = false;

  private final BroadcastReceiver mEnrolStateReceiver =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          refreshFields();
          if (mEnrolAllStep == EnrolAllStep.ENROLLING) {
            mEnrolAllStep = EnrolAllStep.COMPLETED;
            stopVpnPolling();
            updateEnrolAllUi();
            autoConfigureAndStartMqttAfterEnrol();
          }
        }
      };

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    LiteLauncherHider.apply(this);
    setContentView(R.layout.activity_lite_entry);
    ensureNotificationPermission();
    mHandler = new Handler(Looper.getMainLooper());
    mMqttAutoStartManager =
        new MqttAutoStartManager(
            this, mHandler, MQTT_AUTOSTART_RETRY_MS, MQTT_AUTOSTART_MAX_ATTEMPTS, this::isVpnUp);
    mVpnWatcher = new VpnWatcher(mHandler, VPN_TIMEOUT_MS, VPN_POLL_INTERVAL_MS, this::isVpnUp);
    IntentFilter enrolFilter = new IntentFilter(EnrolApiClient.ACTION_ENROL_STATE_UPDATED);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      registerReceiver(mEnrolStateReceiver, enrolFilter, Context.RECEIVER_NOT_EXPORTED);
    } else {
      registerReceiver(mEnrolStateReceiver, enrolFilter);
    }
    mDeviceIdView = findViewById(R.id.device_id_value);
    mEnrolTokenView = findViewById(R.id.enrol_token_value);
    mEnrolAllButton = findViewById(R.id.enrol_all_button);
    Button refreshButton = findViewById(R.id.refresh_button);
    Button syncButton = findViewById(R.id.sync_button);
    Button mqttRefreshButton = findViewById(R.id.mqtt_refresh_button);
    Button mqttStartButton = findViewById(R.id.mqtt_start_button);
    Button mqttStopButton = findViewById(R.id.mqtt_stop_button);
    mTailscaleButton = findViewById(R.id.tailscale_config_button);
    Button refreshExtrasButton = findViewById(R.id.refresh_extras_button);
    setupMqttUi();
    mEnrolAllButton.setOnClickListener((View v) -> handleEnrolAllButton());
    refreshButton.setOnClickListener((View v) -> refreshFields());
    syncButton.setOnClickListener((View v) -> triggerSync());
    mqttRefreshButton.setOnClickListener(
        (View v) -> mdm.qubit.dpc.mdm.MqttCredentialRefresher.refresh(this));
    mqttStartButton.setOnClickListener(
        (View v) -> {
          LiteMqttConfig cfg = new LiteMqttConfig(this);
          saveMqttConfig(cfg);
          Intent startIntent = new Intent(this, LiteMqttService.class);
          startIntent.setAction(LiteMqttService.ACTION_START);
          startService(startIntent);
        });
    mqttStopButton.setOnClickListener(
        (View v) -> {
          LiteMqttConfig cfg = new LiteMqttConfig(this);
          saveMqttConfig(cfg);
          Intent stopIntent = new Intent(this, LiteMqttService.class);
          stopIntent.setAction(LiteMqttService.ACTION_STOP);
          startService(stopIntent);
        });
    mTailscaleButton.setOnClickListener((View v) -> applyTailscaleConfigAndLaunch());
    refreshExtrasButton.setOnClickListener((View v) -> refreshProvisioningExtras());
    if (savedInstanceState != null) {
      String savedStep = savedInstanceState.getString(STATE_STEP, EnrolAllStep.IDLE.name());
      try {
        mEnrolAllStep = EnrolAllStep.valueOf(savedStep);
      } catch (IllegalArgumentException ignore) {
        mEnrolAllStep = EnrolAllStep.IDLE;
      }
      mVpnWaitDeadlineMs = savedInstanceState.getLong(STATE_VPN_DEADLINE, 0L);
    }
    refreshFields();
    updateEnrolAllUi();
  }

  @Override
  protected void onDestroy() {
    stopVpnPolling();
    stopAuthKeyCleanupWatch();
    stopMqttAutoStart();
    unregisterMqttReceiver();
    try {
      unregisterReceiver(mEnrolStateReceiver);
    } catch (IllegalArgumentException ignore) {
      // already unregistered
    }
    super.onDestroy();
  }

  @Override
  protected void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);
    outState.putString(STATE_STEP, mEnrolAllStep.name());
    outState.putLong(STATE_VPN_DEADLINE, mVpnWaitDeadlineMs);
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (mEnrolAllStep == EnrolAllStep.WAITING_FOR_VPN) {
      startVpnPolling();
    }
    updateEnrolAllUi();
    registerMqttReceiver();
    maybeAutoStartMqttOnResume();
  }

  @Override
  protected void onPause() {
    super.onPause();
    unregisterMqttReceiver();
  }

  private void ensureNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
      return;
    }
    if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
        != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(
          new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQ_POST_NOTIFICATIONS);
    }
  }

  private void refreshFields() {
    EnrolState state = new EnrolState(this);
    EnrolConfig config = new EnrolConfig(this);
    String deviceId = state.getDeviceId();
    String token = config.getEnrolToken();
    mDeviceIdView.setText(
        isBlank(deviceId) ? getString(R.string.enrol_device_id_empty) : deviceId);
    mEnrolTokenView.setText(isBlank(token) ? getString(R.string.enrol_token_empty) : token);
    if (!isBlank(deviceId) && mEnrolAllStep == EnrolAllStep.IDLE) {
      mEnrolAllStep = EnrolAllStep.COMPLETED;
      updateEnrolAllUi();
    }
  }

  private void setupMqttUi() {
    mqttHostField = findViewById(R.id.mqtt_host);
    mqttStatusView = findViewById(R.id.mqtt_status);
    mqttEditToggle = findViewById(R.id.mqtt_edit_toggle);
    if (mqttHostField == null || mqttStatusView == null) {
      mqttUiInitialized = false;
      return;
    }
    mqttUiInitialized = true;
    mqttPortField = findViewById(R.id.mqtt_port);
    mqttPathField = findViewById(R.id.mqtt_path);
    mqttUserField = findViewById(R.id.mqtt_user);
    mqttPassField = findViewById(R.id.mqtt_pass);
    mqttQidField = findViewById(R.id.mqtt_qid);
    mqttClientIdField = findViewById(R.id.mqtt_client_id);
    mqttTlsField = findViewById(R.id.mqtt_tls);
    LiteMqttConfig cfg = new LiteMqttConfig(this);
    EnrolState enrolState = new EnrolState(this);
    mqttHostField.setText(cfg.getHost());
    mqttPortField.setText(String.valueOf(cfg.getPort()));
    mqttPathField.setText(cfg.getPath());
    String deviceId = enrolState.getDeviceId();
    String savedUser = cfg.getUsername();
    String savedQid = cfg.getQid();
    mqttUserField.setText(!isBlank(savedUser) ? savedUser : deviceId);
    mqttQidField.setText(!isBlank(savedQid) ? savedQid : deviceId);
    String savedPassword = cfg.getPassword();
    mqttPassField.setText(!isBlank(savedPassword) ? savedPassword : enrolState.getMqttPassword());
    mqttClientIdField.setText(cfg.getClientId());
    mqttTlsField.setChecked(cfg.isTlsEnabled());
    setMqttFieldsEnabled(false);
    if (mqttEditToggle != null) {
      mqttEditToggle.setChecked(false);
      mqttEditToggle.setOnCheckedChangeListener(
          (buttonView, isChecked) -> setMqttFieldsEnabled(isChecked));
    }
    mqttStatusView.setText(getString(R.string.mqtt_status_placeholder));
    mqttStatusReceiver =
        new BroadcastReceiver() {
          @Override
          public void onReceive(Context context, Intent intent) {
            String status = intent.getStringExtra(LiteMqttService.EXTRA_STATUS);
            String error = intent.getStringExtra(LiteMqttService.EXTRA_ERROR);
            updateMqttStatus(status, error);
          }
        };
  }

  private void saveMqttConfig(LiteMqttConfig cfg) {
    if (!mqttUiInitialized) {
      return;
    }
    cfg.setHost(mqttHostField.getText().toString().trim());
    cfg.setPort(parsePort(mqttPortField.getText().toString().trim()));
    cfg.setPath(mqttPathField.getText().toString().trim());
    cfg.setUsername(nullIfBlank(mqttUserField.getText().toString()));
    cfg.setPassword(nullIfBlank(mqttPassField.getText().toString()));
    cfg.setQid(nullIfBlank(mqttQidField.getText().toString()));
    cfg.setClientId(nullIfBlank(mqttClientIdField.getText().toString()));
    cfg.setTlsEnabled(mqttTlsField.isChecked());
  }

  private void autoConfigureAndStartMqttAfterEnrol() {
    mMqttAutoStartManager.autoConfigureAndStartAfterEnrol(this::fillMqttFieldsIfBlank);
  }

  private void maybeAutoStartMqttOnResume() {
    mMqttAutoStartManager.maybeAutoStartOnResume(this::fillMqttFieldsIfBlank);
  }

  private void fillMqttFieldsIfBlank(String username, String qid, String password) {
    if (!mqttUiInitialized) {
      return;
    }
    if (mqttUserField != null && isBlank(mqttUserField.getText().toString())) {
      mqttUserField.setText(username);
    }
    if (mqttQidField != null && isBlank(mqttQidField.getText().toString())) {
      mqttQidField.setText(qid);
    }
    if (mqttPassField != null && isBlank(mqttPassField.getText().toString())) {
      mqttPassField.setText(password);
    }
  }

  private void stopMqttAutoStart() {
    mMqttAutoStartManager.stopAutoStart();
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

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  private static String nullIfBlank(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private void updateMqttStatus(String status, String error) {
    if (!mqttUiInitialized || mqttStatusView == null) {
      return;
    }
    Log.d(TAG, "MQTT UI update status=" + status + (error != null ? " error=" + error : ""));
    runOnUiThread(
        () -> {
          mqttStatusView.setText(formatMqttStatus(status, error));
        });
  }

  private void registerMqttReceiver() {
    if (!mqttUiInitialized || mqttStatusReceiver == null) {
      return;
    }
    IntentFilter filter = new IntentFilter(LiteMqttService.ACTION_STATUS_BROADCAST);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      registerReceiver(mqttStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
    } else {
      registerReceiver(mqttStatusReceiver, filter);
    }
    updateMqttStatus(LiteMqttService.getLastStatus(), LiteMqttService.getLastError());
  }

  private void unregisterMqttReceiver() {
    if (!mqttUiInitialized || mqttStatusReceiver == null) {
      return;
    }
    try {
      unregisterReceiver(mqttStatusReceiver);
    } catch (IllegalArgumentException ignore) {
      // receiver not registered
    }
  }

  private int parsePort(String value) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      return LiteMqttConfig.DEFAULT_PORT;
    }
  }

  private String formatMqttStatus(String status, String error) {
    LiteMqttConfig cfg = new LiteMqttConfig(this);
    String endpoint =
        (cfg.getHost() != null ? cfg.getHost() : "host")
            + ":"
            + cfg.getPort()
            + (cfg.isTlsEnabled() ? " (TLS)" : " (no TLS)");
    String detail = (error != null && !error.isEmpty()) ? " (" + error + ")" : "";
    if ("connecting".equals(status)) {
      return "Connecting to " + endpoint + "...";
    } else if ("connected".equals(status)) {
      return "Connected to " + endpoint;
    } else if ("reconnecting".equals(status)) {
      return "Reconnecting to " + endpoint + detail;
    } else if ("heartbeat".equals(status)) {
      return "Connected (heartbeat ok)";
    } else if ("heartbeat_error".equals(status)) {
      return "Heartbeat failed" + detail;
    } else if ("subscribe_error".equals(status)) {
      return "Subscribe failed" + detail;
    } else if ("subscribed".equals(status)) {
      return "Subscribed to " + (error != null ? error : "notify");
    } else if ("sync".equals(status)) {
      return "Syncing inbox" + detail;
    } else if ("error".equals(status)) {
      return "Connection error" + detail;
    } else if ("stopped".equals(status)) {
      return "MQTT stopped";
    }
    return (status != null ? status : "Unknown") + detail;
  }

  private void triggerSync() {
    MdmSyncManager.syncNow(
        this,
        (success, message) ->
            runOnUiThread(
                () ->
                    Toast.makeText(
                            LiteEntryActivity.this,
                            success
                                ? getString(R.string.lite_sync_ok, message)
                            : getString(R.string.lite_sync_fail, message),
                            Toast.LENGTH_SHORT)
                        .show()));
  }

  private void applyTailscaleConfigAndLaunch() {
    if (mTailscaleBusy) {
      return;
    }
    mAuthKeyCleared = false;
    mTailscaleBusy = true;
    setTailscaleButtonEnabled(false);
    DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
    if (dpm == null || !dpm.isDeviceOwnerApp(getPackageName())) {
      Toast.makeText(this, R.string.lite_enrol_all_requires_do, Toast.LENGTH_SHORT).show();
      finishTailscaleFlow();
      return;
    }
    if (!isTailscaleInstalled()) {
      Toast.makeText(this, R.string.tailscale_not_installed, Toast.LENGTH_SHORT).show();
      finishTailscaleFlow();
      return;
    }
    clearTailscaleDataForConfigFlow(
        () -> {
          deleteFile("tailscale_config.json");
          refreshProvisioningExtras(
              false,
              false,
              () -> {
                boolean applied = applySavedTailscaleRestrictions();
                if (applied) {
                  launchTailscaleAndWatchForVpn();
                }
                finishTailscaleFlow();
              },
              (error) -> {
                Toast.makeText(
                        LiteEntryActivity.this,
                        getString(R.string.refresh_extras_fail, error),
                        Toast.LENGTH_LONG)
                    .show();
                finishTailscaleFlow();
              });
        },
        (error) -> {
          Toast.makeText(
                  LiteEntryActivity.this,
                  getString(R.string.lite_enrol_all_clear_failed, error),
                  Toast.LENGTH_LONG)
              .show();
          finishTailscaleFlow();
        });
  }

  private void setTailscaleButtonEnabled(boolean enabled) {
    if (mTailscaleButton != null) {
      mTailscaleButton.setEnabled(enabled);
    }
  }

  private void setMqttFieldsEnabled(boolean enabled) {
    if (!mqttUiInitialized) {
      return;
    }
    mqttHostField.setEnabled(enabled);
    mqttPortField.setEnabled(enabled);
    mqttPathField.setEnabled(enabled);
    mqttUserField.setEnabled(enabled);
    mqttPassField.setEnabled(enabled);
    mqttQidField.setEnabled(enabled);
    mqttClientIdField.setEnabled(enabled);
    mqttTlsField.setEnabled(enabled);
  }

  private void clearTailscaleDataForConfigFlow(Runnable onSuccess, Consumer<String> onError) {
    try {
      DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
      ComponentName admin = DeviceAdminReceiver.getComponentName(this);
      dpm.clearApplicationUserData(
          admin,
          TAILSCALE_PKG,
          getMainExecutor(),
          (packageName, succeeded) -> {
            if (succeeded) {
              runOnUiThread(onSuccess);
            } else {
              runOnUiThread(() -> onError.accept(packageName));
            }
          });
    } catch (Exception e) {
      runOnUiThread(() -> onError.accept(e.getMessage()));
    }
  }

  private boolean applySavedTailscaleRestrictions() {
    EnrolConfig config = new EnrolConfig(this);
    String loginUrl = config.getTailscaleLoginUrl();
    String authKey = config.getTailscaleAuthKey();
    String hostname = config.getTailscaleHostname();
    if (loginUrl == null || authKey == null || hostname == null) {
      Toast.makeText(this, R.string.tailscale_config_missing, Toast.LENGTH_SHORT).show();
      return false;
    }
    final String pkg = "com.tailscale.ipn";
    PackageManager pm = getPackageManager();
    try {
      pm.getPackageInfo(pkg, 0);
    } catch (Exception e) {
      Toast.makeText(this, R.string.tailscale_not_installed, Toast.LENGTH_SHORT).show();
      return false;
    }
    try {
      DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
      ComponentName admin = DeviceAdminReceiver.getComponentName(this);
      Bundle bundle = new Bundle();
      bundle.putString("LoginURL", loginUrl);
      String controlUrl =
          config.getTailscaleControlUrl() != null ? config.getTailscaleControlUrl() : loginUrl;
      bundle.putString("ControlURL", controlUrl);
      bundle.putString("AuthKey", authKey);
      bundle.putString("Hostname", hostname);
      String exitNodeId = config.getTailscaleExitNodeId();
      if (exitNodeId != null) {
        bundle.putString("ExitNodeID", exitNodeId);
      }
      bundle.putBoolean("ForceEnabled", config.isTailscaleForceEnabled());
      dpm.setApplicationRestrictions(admin, pkg, bundle);
      writeTailscaleConfigFile(authKey, hostname, controlUrl);
      Toast.makeText(this, R.string.tailscale_config_ok, Toast.LENGTH_SHORT).show();
    } catch (Exception e) {
      Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
      return false;
    }
    return true;
  }

  private void launchTailscaleAndWatchForVpn() {
    PackageManager pm = getPackageManager();
    try {
      Intent launch = pm.getLaunchIntentForPackage(TAILSCALE_PKG);
      if (launch != null) {
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launch);
        startAuthKeyCleanupWatch();
      } else {
        Toast.makeText(this, R.string.tailscale_launch_failed, Toast.LENGTH_SHORT).show();
      }
    } catch (Exception e) {
      Toast.makeText(this, R.string.tailscale_launch_failed, Toast.LENGTH_SHORT).show();
    }
  }

  private void startAuthKeyCleanupWatch() {
    stopAuthKeyCleanupWatch();
    mAuthKeyCleanupRunnable =
        new Runnable() {
          @Override
          public void run() {
            if (isVpnUp()) {
              clearAuthKeyAfterVpn();
              stopAuthKeyCleanupWatch();
              return;
            }
            mHandler.postDelayed(this, VPN_POLL_INTERVAL_MS);
          }
        };
    mHandler.post(mAuthKeyCleanupRunnable);
  }

  private void stopAuthKeyCleanupWatch() {
    if (mAuthKeyCleanupRunnable != null) {
      mHandler.removeCallbacks(mAuthKeyCleanupRunnable);
      mAuthKeyCleanupRunnable = null;
    }
  }

  private void finishTailscaleFlow() {
    mTailscaleBusy = false;
    setTailscaleButtonEnabled(true);
  }

  private void refreshProvisioningExtras() {
    refreshProvisioningExtras(false, true, null, null);
  }

  private void refreshProvisioningExtras(boolean enrolAllFlow) {
    refreshProvisioningExtras(enrolAllFlow, true, null, null);
  }

  private void refreshProvisioningExtras(
      boolean enrolAllFlow,
      boolean applyTailscale,
      Runnable onSuccess,
      Consumer<String> onError) {
    new Thread(
            () -> {
              try {
                EnrolConfig cfg = new EnrolConfig(this);
                String enrol = cfg.getEnrolToken();
                String apkIndex = cfg.getApkIndexUrl();
                if (enrol == null || apkIndex == null) {
                  runOnUiThread(
                      () ->
                          Toast.makeText(
                                  this, R.string.tailscale_config_missing, Toast.LENGTH_SHORT)
                              .show());
                  return;
                }
                URI uri = URI.create(apkIndex);
                String base = uri.getScheme() + "://" + uri.getHost();
                URL url = new URL(base + "/api/provisioning/extras");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                JSONObject body = new JSONObject();
                body.put("enrol_token", enrol);
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                  os.write(bytes);
                }
                int code = conn.getResponseCode();
                String resp = readBody(conn);
                if (code < 200 || code >= 300 || resp == null) {
                  throw new Exception("HTTP " + code + " body=" + resp);
                }
                JSONObject json = new JSONObject(resp);
                cfg.saveEnrolToken(json.optString("enrol_token", enrol));
                cfg.saveApkIndexUrl(json.optString("apk_index_url", apkIndex));
                String loginUrl = json.optString("LoginURL", cfg.getTailscaleLoginUrl());
                String controlUrl =
                    json.optString("ControlURL", json.optString("LoginURL", cfg.getTailscaleControlUrl()));
                cfg.saveTailscaleLoginUrl(loginUrl);
                cfg.saveTailscaleControlUrl(controlUrl);
                cfg.saveTailscaleAuthKey(json.optString("AuthKey", cfg.getTailscaleAuthKey()));
                cfg.saveTailscaleHostname(json.optString("Hostname", cfg.getTailscaleHostname()));
                cfg.saveTailscaleExitNodeId(json.optString("ExitNodeID", cfg.getTailscaleExitNodeId()));
                cfg.saveTailscaleForceEnabled(
                    json.optBoolean("ForceEnabled", cfg.isTailscaleForceEnabled()));
                JSONObject managedCfg = json.optJSONObject("tailscale_managed_config");
                if (managedCfg != null) {
                  cfg.saveTailscaleManagedConfig(managedCfg.toString());
                }
                runOnUiThread(
                    () -> {
                      refreshFields();
                      Toast.makeText(this, R.string.refresh_extras_ok, Toast.LENGTH_SHORT).show();
                      if (applyTailscale) {
                        applyTailscaleConfigAndLaunch();
                      }
                      if (onSuccess != null) {
                        onSuccess.run();
                      }
                      if (enrolAllFlow) {
                        mEnrolAllStep = EnrolAllStep.WAITING_FOR_VPN;
                        startVpnPolling();
                        updateEnrolAllUi();
                      }
                    });
              } catch (Exception e) {
                runOnUiThread(
                    () ->
                        Toast.makeText(
                                this,
                                getString(R.string.refresh_extras_fail, e.getMessage()),
                                Toast.LENGTH_LONG)
                            .show());
                if (onError != null) {
                  runOnUiThread(() -> onError.accept(e.getMessage()));
                }
              }
            })
        .start();
  }

  private static String readBody(HttpURLConnection conn) {
    try (BufferedReader br =
        new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
      StringBuilder sb = new StringBuilder();
      String line;
      while ((line = br.readLine()) != null) {
        sb.append(line);
      }
      return sb.toString();
    } catch (Exception e) {
      try (BufferedReader br =
          new BufferedReader(
              new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
          sb.append(line);
        }
        return sb.toString();
      } catch (Exception ignore) {
        return null;
      }
    }
  }

  private void writeTailscaleConfigFile(String authKey, String hostname, String controlUrl) {
    try {
      EnrolConfig cfg = new EnrolConfig(this);
      String managedJson = cfg.getTailscaleManagedConfig();
      JSONObject obj =
          managedJson != null && !managedJson.isEmpty() ? new JSONObject(managedJson) : new JSONObject();
      obj.put("AuthKey", authKey);
      obj.put("Hostname", hostname);
      obj.put("ControlURL", controlUrl);
      obj.put("ForceEnabled", cfg.isTailscaleForceEnabled());
      String exitNodeId = cfg.getTailscaleExitNodeId();
      if (exitNodeId != null && !exitNodeId.isEmpty()) {
        obj.put("ExitNodeID", exitNodeId);
      }
      try (FileOutputStream fos = openFileOutput("tailscale_config.json", MODE_PRIVATE)) {
        fos.write(obj.toString().getBytes(StandardCharsets.UTF_8));
      }
    } catch (Exception ignore) {
      // best-effort
    }
  }

  private void startEnrolAllFlow() {
    DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
    if (dpm == null || !dpm.isDeviceOwnerApp(getPackageName())) {
      Toast.makeText(this, R.string.lite_enrol_all_requires_do, Toast.LENGTH_SHORT).show();
      return;
    }
    if (mEnrolAllStep == EnrolAllStep.COMPLETED) {
      updateEnrolAllUi();
      return;
    }
    if (!isTailscaleInstalled()) {
      Toast.makeText(this, R.string.tailscale_not_installed, Toast.LENGTH_SHORT).show();
      return;
    }
    mAuthKeyCleared = false;
    Log.d(TAG, "EnrolAll start: clearing Tailscale data");
    mEnrolAllStep = EnrolAllStep.CLEARING_TAILSCALE;
    Toast.makeText(this, R.string.lite_enrol_all_clearing_tailscale, Toast.LENGTH_SHORT).show();
    updateEnrolAllUi();
    clearTailscaleData();
  }

  private void handleEnrolAllButton() {
    if (mEnrolAllStep == EnrolAllStep.WAITING_FOR_VPN) {
      manualCheckVpn();
    } else if (mEnrolAllStep == EnrolAllStep.IDLE) {
      startEnrolAllFlow();
    } else if (mEnrolAllStep == EnrolAllStep.COMPLETED) {
      updateEnrolAllUi();
    }
  }

  private boolean isTailscaleInstalled() {
    try {
      getPackageManager().getPackageInfo(TAILSCALE_PKG, 0);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private void clearTailscaleData() {
    DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
    ComponentName admin = DeviceAdminReceiver.getComponentName(this);
    try {
      dpm.clearApplicationUserData(
          admin,
          TAILSCALE_PKG,
          getMainExecutor(),
          (packageName, succeeded) -> {
            if (!succeeded) {
              mEnrolAllStep = EnrolAllStep.IDLE;
              Log.w(TAG, "EnrolAll: clear data failed for " + packageName);
              Toast.makeText(
                      LiteEntryActivity.this,
                      getString(R.string.lite_enrol_all_clear_failed, packageName),
                      Toast.LENGTH_LONG)
                  .show();
              updateEnrolAllUi();
              return;
            }
            Log.d(TAG, "EnrolAll: clear data succeeded, refreshing extras");
            mEnrolAllStep = EnrolAllStep.REFRESHING_EXTRAS;
            updateEnrolAllUi();
            refreshProvisioningExtras(true);
          });
    } catch (Exception e) {
      mEnrolAllStep = EnrolAllStep.IDLE;
      Log.w(TAG, "EnrolAll: clear data threw", e);
      Toast.makeText(
              this, getString(R.string.lite_enrol_all_clear_failed, e.getMessage()), Toast.LENGTH_LONG)
          .show();
      updateEnrolAllUi();
    }
  }

  private void startVpnPolling() {
    if (mEnrolAllStep != EnrolAllStep.WAITING_FOR_VPN) {
      return;
    }
    mVpnWaitDeadlineMs =
        mVpnWatcher.start(
            mVpnWaitDeadlineMs,
            new VpnWatcher.Listener() {
              @Override
              public void onVpnUp() {
                Log.d(TAG, "EnrolAll: VPN detected, proceeding");
                clearAuthKeyAfterVpn();
                proceedToEnrol();
              }

              @Override
              public void onTimeout() {
                mEnrolAllStep = EnrolAllStep.IDLE;
                mVpnWaitDeadlineMs = 0L;
                Log.w(TAG, "EnrolAll: VPN wait timeout");
                Toast.makeText(LiteEntryActivity.this, R.string.lite_enrol_all_vpn_timeout, Toast.LENGTH_LONG)
                    .show();
                updateEnrolAllUi();
              }

              @Override
              public void onWaiting() {
                Toast.makeText(LiteEntryActivity.this, R.string.lite_enrol_all_waiting_vpn, Toast.LENGTH_SHORT)
                    .show();
              }
            });
  }

  private void manualCheckVpn() {
    if (isVpnUp()) {
      Log.d(TAG, "EnrolAll: manual VPN check success, proceeding");
      clearAuthKeyAfterVpn();
      proceedToEnrol();
    } else {
      Toast.makeText(this, R.string.lite_enrol_all_waiting_vpn, Toast.LENGTH_SHORT).show();
    }
  }

  private void clearAuthKeyAfterVpn() {
    if (mAuthKeyCleared) {
      return;
    }
    mAuthKeyCleared = true;
    EnrolConfig cfg = new EnrolConfig(this);
    cfg.saveTailscaleAuthKey("");
    DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
    ComponentName admin = DeviceAdminReceiver.getComponentName(this);
    try {
      Bundle bundle = new Bundle();
      String loginUrl = cfg.getTailscaleLoginUrl();
      String controlUrl = cfg.getTailscaleControlUrl() != null ? cfg.getTailscaleControlUrl() : loginUrl;
      bundle.putString("LoginURL", loginUrl);
      bundle.putString("ControlURL", controlUrl);
      bundle.putString("AuthKey", "");
      bundle.putString("Hostname", cfg.getTailscaleHostname());
      String exitNodeId = cfg.getTailscaleExitNodeId();
      if (exitNodeId != null) {
        bundle.putString("ExitNodeID", exitNodeId);
      }
      bundle.putBoolean("ForceEnabled", cfg.isTailscaleForceEnabled());
      dpm.setApplicationRestrictions(admin, TAILSCALE_PKG, bundle);
      writeTailscaleConfigFile("", cfg.getTailscaleHostname(), controlUrl);
      Log.d(TAG, "EnrolAll: AuthKey cleared after VPN up");
      Toast.makeText(this, R.string.lite_enrol_all_authkey_cleared, Toast.LENGTH_SHORT).show();
    } catch (Exception ignore) {
      // best-effort cleanup
    }
  }

  private void proceedToEnrol() {
    mEnrolAllStep = EnrolAllStep.ENROLLING;
    mVpnWaitDeadlineMs = 0L;
    stopVpnPolling();
    updateEnrolAllUi();
    EnrolApiClient.enrolWithSavedToken(LiteEntryActivity.this);
  }

  private void stopVpnPolling() {
    if (mVpnWatcher != null) {
      mVpnWatcher.stop();
    }
  }

  private void updateEnrolAllUi() {
    if (mEnrolAllButton == null) {
      return;
    }
    runOnUiThread(
        () -> {
          switch (mEnrolAllStep) {
            case IDLE:
              Log.d(TAG, "EnrolAll UI: IDLE -> Enrol All");
              mEnrolAllButton.setEnabled(true);
              mEnrolAllButton.setText(R.string.lite_enrol_all_button);
              break;
            case WAITING_FOR_VPN:
              Log.d(TAG, "EnrolAll UI: WAITING_FOR_VPN -> Check VPN");
              mEnrolAllButton.setEnabled(true);
              mEnrolAllButton.setText(R.string.lite_enrol_all_check_vpn);
              break;
            case COMPLETED:
              Log.d(TAG, "EnrolAll UI: COMPLETED -> disabled");
              mEnrolAllButton.setEnabled(false);
              mEnrolAllButton.setText(R.string.lite_enrol_all_done);
              break;
            default:
              Log.d(TAG, "EnrolAll UI: busy state " + mEnrolAllStep);
              mEnrolAllButton.setEnabled(false);
              mEnrolAllButton.setText(R.string.lite_enrol_all_button);
              break;
          }
        });
  }
}
