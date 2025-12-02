package com.afwsamples.testdpc.lite;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import com.afwsamples.testdpc.EnrolApiClient;
import com.afwsamples.testdpc.EnrolConfig;
import com.afwsamples.testdpc.EnrolState;
import com.afwsamples.testdpc.R;
import com.afwsamples.testdpc.mdm.MdmSyncManager;

/**
 * Minimal launcher for field devices: shows enrol info and triggers enrol call.
 */
public class LiteEntryActivity extends Activity {

  private TextView mDeviceIdView;
  private TextView mEnrolTokenView;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_lite_entry);
    mDeviceIdView = findViewById(R.id.device_id_value);
    mEnrolTokenView = findViewById(R.id.enrol_token_value);
    Button enrolButton = findViewById(R.id.enrol_button);
    Button refreshButton = findViewById(R.id.refresh_button);
    Button syncButton = findViewById(R.id.sync_button);
    Button mqttRefreshButton = findViewById(R.id.mqtt_refresh_button);
    enrolButton.setOnClickListener(
        (View v) -> EnrolApiClient.enrolWithSavedToken(LiteEntryActivity.this));
    refreshButton.setOnClickListener((View v) -> refreshFields());
    syncButton.setOnClickListener((View v) -> triggerSync());
    mqttRefreshButton.setOnClickListener(
        (View v) -> com.afwsamples.testdpc.mdm.MqttCredentialRefresher.refresh(this));
    refreshFields();
  }

  private void refreshFields() {
    EnrolState state = new EnrolState(this);
    EnrolConfig config = new EnrolConfig(this);
    String deviceId = state.getDeviceId();
    String token = config.getEnrolToken();
    mDeviceIdView.setText(deviceId == null ? getString(R.string.enrol_device_id_empty) : deviceId);
    mEnrolTokenView.setText(token == null ? getString(R.string.enrol_token_empty) : token);
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
}
