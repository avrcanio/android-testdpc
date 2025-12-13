/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.afwsamples.testdpc;

import android.Manifest;
import android.R.id;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Build;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.afwsamples.testdpc.EnrolConfig;
import com.afwsamples.testdpc.EnrolState;
import com.afwsamples.testdpc.lite.LiteMqttConfig;
import com.afwsamples.testdpc.lite.LiteMqttService;
import com.afwsamples.testdpc.mdm.MdmSyncManager;
import com.afwsamples.testdpc.common.DumpableActivity;
import com.afwsamples.testdpc.common.OnBackPressedHandler;
import com.afwsamples.testdpc.policy.PolicyManagementFragment;
import com.afwsamples.testdpc.search.PolicySearchFragment;
import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * An entry activity that shows a profile setup fragment if the app is not a profile or device
 * owner. Otherwise, a policy management fragment is shown.
 */
public class PolicyManagementActivity extends DumpableActivity
    implements FragmentManager.OnBackStackChangedListener {

  private static final String TAG = PolicyManagementActivity.class.getSimpleName();

  private static final String CMD_LOCK_TASK_MODE = "lock-task-mode";
  private static final String LOCK_MODE_ACTION_START = "start";
  private static final String LOCK_MODE_ACTION_STATUS = "status";
  private static final String LOCK_MODE_ACTION_STOP = "stop";

  private boolean mLockTaskMode;
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
  private boolean mqttUiInitialized = false;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    if (getResources().getBoolean(R.bool.config_lite_mode)) {
      startActivity(new Intent(this, com.afwsamples.testdpc.lite.LiteEntryActivity.class));
      finish();
      return;
    }
    FileLogger.log(this, "TEST: app main activity started");
    EnrolConfig config = new EnrolConfig(this);
    String savedToken = config.getEnrolToken();
    FileLogger.log(this, "TEST: saved enrol_token from prefs = " + savedToken);
    setContentView(R.layout.activity_main);
    setupMqttUi();
    if (savedInstanceState == null) {
      getFragmentManager()
          .beginTransaction()
          .add(
              R.id.container, new PolicyManagementFragment(), PolicyManagementFragment.FRAGMENT_TAG)
          .commit();
    }
    getFragmentManager().addOnBackStackChangedListener(this);
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
    Button startButton = findViewById(R.id.mqtt_start_button);
    Button stopButton = findViewById(R.id.mqtt_stop_button);

    LiteMqttConfig cfg = new LiteMqttConfig(this);
    EnrolState enrolState = new EnrolState(this);
    mqttHostField.setText(cfg.getHost());
    mqttPortField.setText(String.valueOf(cfg.getPort()));
    mqttPathField.setText(cfg.getPath());
    String deviceId = enrolState.getDeviceId();
    String savedUser = cfg.getUsername();
    String savedQid = cfg.getQid();
    mqttUserField.setText(savedUser != null ? savedUser : deviceId);
    mqttQidField.setText(savedQid != null ? savedQid : deviceId);
    String savedPassword = cfg.getPassword();
    mqttPassField.setText(savedPassword != null ? savedPassword : enrolState.getMqttPassword());
    mqttClientIdField.setText(cfg.getClientId());
    mqttTlsField.setChecked(cfg.isTlsEnabled());
    setMqttFieldsEnabled(false);
    if (mqttEditToggle != null) {
      mqttEditToggle.setChecked(false);
      mqttEditToggle.setOnCheckedChangeListener(
          (buttonView, isChecked) -> setMqttFieldsEnabled(isChecked));
    }
    mqttStatusView.setText(getString(R.string.mqtt_status_placeholder));

    startButton.setOnClickListener(
        (View v) -> {
          saveMqttConfig(cfg);
          Intent startIntent = new Intent(this, LiteMqttService.class);
          startIntent.setAction(LiteMqttService.ACTION_START);
          startService(startIntent);
        });
    stopButton.setOnClickListener(
        (View v) -> {
          saveMqttConfig(cfg);
          Intent stopIntent = new Intent(this, LiteMqttService.class);
          stopIntent.setAction(LiteMqttService.ACTION_STOP);
          startService(stopIntent);
        });
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
    cfg.setUsername(mqttUserField.getText().toString().trim());
    cfg.setPassword(mqttPassField.getText().toString());
    cfg.setQid(mqttQidField.getText().toString().trim());
    cfg.setClientId(mqttClientIdField.getText().toString().trim());
    cfg.setTlsEnabled(mqttTlsField.isChecked());
  }

  private void updateMqttStatus(String status, String error) {
    if (!mqttUiInitialized || mqttStatusView == null) {
      return;
    }
    mqttStatusView.setText(formatMqttStatus(status, error));
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

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    getMenuInflater().inflate(R.menu.policy_management_menu, menu);
    return true;
  }

  @Override
  public boolean onMenuItemSelected(int featureId, MenuItem item) {
    int itemId = item.getItemId();
    if (itemId == R.id.action_show_search) {
      getFragmentManager()
          .beginTransaction()
          .replace(R.id.container, PolicySearchFragment.newInstance())
          .addToBackStack("search")
          .commit();
      return true;
    } else if (itemId == R.id.action_sync) {
      Log.i(TAG, "Manual sync triggered from toolbar");
      MdmSyncManager.syncNow(
          this,
          (success, message) ->
              runOnUiThread(
                  () ->
                      Toast.makeText(
                              this,
                              success
                                  ? "Sync ok: " + message
                                  : "Sync failed: " + (message == null ? "" : message),
                              Toast.LENGTH_SHORT)
                          .show()));
      return true;
    } else if (itemId == id.home) {
      getFragmentManager().popBackStack();
      return true;
    }
    return false;
  }

  @Override
  protected void onResume() {
    super.onResume();
    registerMqttReceiver();

    String lockModeCommand = getIntent().getStringExtra(CMD_LOCK_TASK_MODE);
    if (lockModeCommand != null) {
      setLockTaskMode(lockModeCommand);
    }

    askNotificationPermission();
  }

  @Override
  protected void onPause() {
    super.onPause();
    unregisterMqttReceiver();
  }

  @Override
  public void onBackStackChanged() {
    // Show the up button in actionbar if back stack has any entry.
    getActionBar().setDisplayHomeAsUpEnabled(getFragmentManager().getBackStackEntryCount() > 0);
  }

  @Override
  public void onBackPressed() {
    Fragment currFragment = getFragmentManager().findFragmentById(R.id.container);
    boolean onBackPressHandled = false;
    if (currFragment != null && currFragment instanceof OnBackPressedHandler) {
      onBackPressHandled = ((OnBackPressedHandler) currFragment).onBackPressed();
    }
    if (!onBackPressHandled) {
      super.onBackPressed();
    }
  }

  @Override
  public void onDestroy() {
    unregisterMqttReceiver();
    super.onDestroy();
    getFragmentManager().removeOnBackStackChangedListener(this);
  }

  @Override
  public void dump(String prefix, FileDescriptor fd, PrintWriter pw, String[] args) {
    if (args != null && args.length > 0 && args[0].equals(CMD_LOCK_TASK_MODE)) {
      String action = args.length == 1 ? LOCK_MODE_ACTION_STATUS : args[1];
      switch (action) {
        case LOCK_MODE_ACTION_START:
          pw.println("Starting lock-task mode");
          startLockTaskMode();
          break;
        case LOCK_MODE_ACTION_STOP:
          pw.println("Stopping lock-task mode");
          stopLockTaskMode();
          break;
        case LOCK_MODE_ACTION_STATUS:
          dumpLockModeStatus(pw);
          break;
        default:
          pw.printf("Invalid lock-task mode action: %s\n", action);
      }
      return;
    }
    pw.print(prefix);
    dumpLockModeStatus(pw);

    super.dump(prefix, fd, pw, args);
  }

  private void startLockTaskMode() {
    if (mLockTaskMode) Log.w(TAG, "startLockTaskMode(): mLockTaskMode already true");
    mLockTaskMode = true;

    Log.i(TAG, "startLockTaskMode(): calling Activity.startLockTask()");
    startLockTask();
  }

  private void stopLockTaskMode() {
    if (!mLockTaskMode) Log.w(TAG, "startLockTaskMode(): mLockTaskMode already false");
    mLockTaskMode = false;

    Log.i(TAG, "stopLockTaskMode(): calling Activity.stopLockTask()");
    stopLockTask();
  }

  private void dumpLockModeStatus(PrintWriter pw) {
    pw.printf("lock-task mode: %b\n", mLockTaskMode);
  }

  private void setLockTaskMode(String action) {
    switch (action) {
      case LOCK_MODE_ACTION_START:
        startLockTaskMode();
        break;
      case LOCK_MODE_ACTION_STOP:
        stopLockTaskMode();
        break;
      case LOCK_MODE_ACTION_STATUS:
        Log.d(TAG, "lock-task mode status: " + mLockTaskMode);
        break;
      default:
        Log.e(TAG, "invalid lock-task action: " + action);
    }
  }

  private void askNotificationPermission() {
    // This is only necessary for API level >= 33 (TIRAMISU)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
          == PackageManager.PERMISSION_GRANTED) {
        Log.d(TAG, "Notification permission granted");
      } else {
        Log.e(TAG, "Notification permission missing");
        // Directly ask for the permission
        ActivityCompat.requestPermissions(
            this, new String[] {Manifest.permission.POST_NOTIFICATIONS}, 101);
      }
    }
  }
}
