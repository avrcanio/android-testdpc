/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.afwsamples.testdpc.policy;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ActivityNotFoundException;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.telephony.TelephonyManager;
import android.telephony.SubscriptionManager;
import android.telephony.euicc.DownloadableSubscription;
import android.telephony.euicc.EuiccManager;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Toast;
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;
import com.afwsamples.testdpc.R;
import com.afwsamples.testdpc.common.BaseSearchablePolicyPreferenceFragment;
import com.afwsamples.testdpc.common.ReflectionUtil;
import com.afwsamples.testdpc.common.ReflectionUtil.ReflectionIsTemporaryException;
import com.afwsamples.testdpc.common.preference.DpcPreference;
import java.util.Set;

/** Fragment to control eSIMs. */
@TargetApi(VERSION_CODES.VANILLA_ICE_CREAM)
public class EsimControlFragment extends BaseSearchablePolicyPreferenceFragment
    implements Preference.OnPreferenceClickListener, Preference.OnPreferenceChangeListener {
  private static final String TAG = EsimControlFragment.class.getSimpleName();
  private static final String DOWNLOAD_ESIM = "download_esim";
  private static final String DELETE_ESIM = "delete_esim";
  private static final String GET_MANAGED_ESIM = "get_managed_esim";
  private static final String ACTIVATE_ESIM = "activate_esim";
  private static final String DEACTIVATE_ESIM = "deactivate_esim";
  private static final String POWER_OFF_SIM_SLOT = "power_off_sim_slot";
  private static final String POWER_ON_SIM_SLOT = "power_on_sim_slot";
  private static final String OPEN_ESIM_MANAGEMENT = "open_esim_management";
  private static final String OPEN_SIM_SETTINGS_FOR_ESIM = "open_sim_settings_for_esim";
  private static final String ACTION_MOBILE_NETWORK_SETTINGS =
      "android.settings.MOBILE_NETWORK_SETTINGS";
  private static final String EXTRA_SUBSCRIPTION_ID = "android.telephony.extra.SUBSCRIPTION_ID";
  private static final String EXTRA_SUB_ID = "android.provider.extra.SUB_ID";
  private static final String ACTION_DOWNLOAD_ESIM = "com.afwsamples.testdpc.esim_download";
  private static final String ACTION_DELETE_ESIM = "com.afwsamples.testdpc.esim_delete";
  private static final String ACTION_SWITCH_ESIM = "com.afwsamples.testdpc.esim_switch";
  private static final String EXTRA_SWITCH_ON = "extra_switch_on";

  private DpcPreference mDownloadEsimPreference;
  private DpcPreference mDeleteEsimPreference;
  private DpcPreference mGetManagedEsimPreference;
  private DpcPreference mActivateEsimPreference;
  private DpcPreference mDeactivateEsimPreference;
  private DpcPreference mPowerOffSimPreference;
  private DpcPreference mPowerOnSimPreference;
  private DpcPreference mOpenEsimManagementPreference;
  private DpcPreference mOpenSimSettingsForEsimPreference;
  private DevicePolicyManager mDevicePolicyManager;
  private EuiccManager mEuiccManager;
  private TelephonyManager mTelephonyManager;
  private static final int SIM_POWER_STATE_ON_FALLBACK = 1;
  private static final int SIM_POWER_STATE_OFF_FALLBACK = 0;

  private String getResultText(int resultCode) {
    if (resultCode == EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_OK) {
      return "EMBEDDED_SUBSCRIPTION_RESULT_OK";
    } else if (resultCode == EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR) {
      return "EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR";
    } else if (resultCode == EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_ERROR) {
      return "EMBEDDED_SUBSCRIPTION_RESULT_ERROR";
    }
    return "Uknown: " + resultCode;
  }

  @Override
  public void onCreate(Bundle savedInstanceState) {
    mDevicePolicyManager = getActivity().getSystemService(DevicePolicyManager.class);
    mEuiccManager = getActivity().getSystemService(EuiccManager.class);
    mTelephonyManager = getActivity().getSystemService(TelephonyManager.class);
    getActivity().getActionBar().setTitle(R.string.manage_esim);
    super.onCreate(savedInstanceState);
  }

  private BroadcastReceiver mDownloadESIMReceiver =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

          if (!ACTION_DOWNLOAD_ESIM.equals(intent.getAction())) {
            return;
          }
          int detailedCode =
              intent.getIntExtra(EuiccManager.EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE, -1);
          int errorCode =
              intent.getIntExtra(EuiccManager.EXTRA_EMBEDDED_SUBSCRIPTION_ERROR_CODE, -1);
          int resultCode = getResultCode();

          Log.v(
              TAG,
              "Download result: resultCode: "
                  + getResultText(resultCode)
                  + " detailedCode: "
                  + resultCode
                  + " detailedCode: "
                  + detailedCode
                  + " errorCode: "
                  + errorCode);
          showToast("Download result: " + getResultText(resultCode), Toast.LENGTH_LONG);
          if (resultCode == EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR) {
            try {
              mEuiccManager.startResolutionActivity(
                  getActivity(),
                  resultCode,
                  intent,
                  PendingIntent.getBroadcast(
                      getActivity(),
                      0,
                      new Intent(ACTION_DOWNLOAD_ESIM),
                      PendingIntent.FLAG_MUTABLE
                          | PendingIntent.FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT));
            } catch (Exception e) {
              Log.e(TAG, "Failed to start resolution activity", e);
            }
            return;
          }
          getActivity().unregisterReceiver(mDownloadESIMReceiver);
        }
      };

  private BroadcastReceiver mDeleteESIMReceiver =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          if (!ACTION_DELETE_ESIM.equals(intent.getAction())) {
            return;
          }
          int detailedCode =
              intent.getIntExtra(EuiccManager.EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE, -1);
          Log.v(
              TAG,
              "Delete result: resultCode: "
                  + getResultText(getResultCode())
                  + " detailedCode: "
                  + detailedCode);

          showToast("Delete result: " + getResultText(getResultCode()), Toast.LENGTH_LONG);
          getActivity().unregisterReceiver(mDeleteESIMReceiver);
        }
      };

  private BroadcastReceiver mSwitchEsimReceiver =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          if (!ACTION_SWITCH_ESIM.equals(intent.getAction())) {
            return;
          }
          boolean switchOn = intent.getBooleanExtra(EXTRA_SWITCH_ON, false);
          int resultCode = getResultCode();
          Log.v(TAG, "Switch eSIM result: " + getResultText(resultCode));
          if (resultCode == EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_OK) {
            showToast(
                switchOn
                    ? getString(R.string.esim_switch_success_toast_on)
                    : getString(R.string.esim_switch_success_toast_off),
                Toast.LENGTH_SHORT);
          } else if (resultCode == EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR) {
            try {
              mEuiccManager.startResolutionActivity(
                  getActivity(),
                  resultCode,
                  intent,
                  PendingIntent.getBroadcast(
                      getActivity(),
                      0,
                      new Intent(ACTION_SWITCH_ESIM),
                      PendingIntent.FLAG_MUTABLE
                          | PendingIntent.FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT));
            } catch (Exception e) {
              Log.e(TAG, "Failed to start resolution for switch eSIM", e);
            }
          } else {
            showToast("Switch eSIM failed: " + getResultText(resultCode), Toast.LENGTH_LONG);
          }
          try {
            getActivity().unregisterReceiver(mSwitchEsimReceiver);
          } catch (IllegalArgumentException ignored) {
            // receiver already unregistered
          }
        }
      };

  @Override
  public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
    addPreferencesFromResource(R.xml.esim_control_preferences);

    mDownloadEsimPreference = (DpcPreference) findPreference(DOWNLOAD_ESIM);
    mDownloadEsimPreference.setOnPreferenceClickListener(this);

    mDeleteEsimPreference = (DpcPreference) findPreference(DELETE_ESIM);
    mDeleteEsimPreference.setOnPreferenceClickListener(this);

    mGetManagedEsimPreference = (DpcPreference) findPreference(GET_MANAGED_ESIM);
    mGetManagedEsimPreference.setOnPreferenceClickListener(this);

    mActivateEsimPreference = (DpcPreference) findPreference(ACTIVATE_ESIM);
    mActivateEsimPreference.setOnPreferenceClickListener(this);

    mDeactivateEsimPreference = (DpcPreference) findPreference(DEACTIVATE_ESIM);
    mDeactivateEsimPreference.setOnPreferenceClickListener(this);

    mPowerOffSimPreference = (DpcPreference) findPreference(POWER_OFF_SIM_SLOT);
    mPowerOffSimPreference.setOnPreferenceClickListener(this);

    mPowerOnSimPreference = (DpcPreference) findPreference(POWER_ON_SIM_SLOT);
    mPowerOnSimPreference.setOnPreferenceClickListener(this);

    mOpenEsimManagementPreference = (DpcPreference) findPreference(OPEN_ESIM_MANAGEMENT);
    mOpenEsimManagementPreference.setOnPreferenceClickListener(this);

    mOpenSimSettingsForEsimPreference =
        (DpcPreference) findPreference(OPEN_SIM_SETTINGS_FOR_ESIM);
    mOpenSimSettingsForEsimPreference.setOnPreferenceClickListener(this);
  }

  @Override
  public boolean isAvailable(Context context) {
    return true;
  }

  @Override
  public boolean onPreferenceChange(Preference preference, Object newValue) {
    return false;
  }

  @Override
  public boolean onPreferenceClick(Preference preference) {
    String key = preference.getKey();

    switch (key) {
      case DOWNLOAD_ESIM:
        showDownloadEsimUi();
        return true;
      case DELETE_ESIM:
        showDeleteEsimUi();
        return true;
      case GET_MANAGED_ESIM:
        showManagedEsimUi();
        return true;
      case ACTIVATE_ESIM:
        showSwitchEsimUi(/* switchOn= */ true);
        return true;
      case DEACTIVATE_ESIM:
        showSwitchEsimUi(/* switchOn= */ false);
        return true;
      case POWER_OFF_SIM_SLOT:
        showSimPowerDialog(/* powerOn= */ false);
        return true;
      case POWER_ON_SIM_SLOT:
        showSimPowerDialog(/* powerOn= */ true);
        return true;
      case OPEN_ESIM_MANAGEMENT:
        launchSystemEsimManagement();
        return true;
      case OPEN_SIM_SETTINGS_FOR_ESIM:
        showManagedEsimSettingsUi();
        return true;
    }
    return false;
  }

  private void showManagedEsimUi() {
    Set<Integer> managedSubIds = getSubscriptionIds();
    if (managedSubIds == null || managedSubIds.isEmpty()) {
      showToast(getString(R.string.esim_switch_failed_no_profiles), Toast.LENGTH_LONG);
      return;
    }
    new AlertDialog.Builder(getActivity())
        .setTitle(R.string.get_managed_esim_dialog_title)
        .setItems(managedSubIds.stream().map(String::valueOf).toArray(String[]::new), null)
        .show();
  }

  private Set<Integer> getSubscriptionIds() {
    try {
      // TODO: remove reflection code and call directly once V is released.
      return ReflectionUtil.invoke(mDevicePolicyManager, "getSubscriptionIds");
    } catch (ReflectionIsTemporaryException e) {
      Log.e(TAG, "Error invoking getSubscriptionIds", e);
      showToast("Error getting managed esim information.", Toast.LENGTH_LONG);
    }
    return null;
  }

  private void showSwitchEsimUi(boolean switchOn) {
    Set<Integer> managedSubIds = getSubscriptionIds();
    if (managedSubIds == null || managedSubIds.isEmpty()) {
      showToast(getString(R.string.esim_switch_failed_no_profiles), Toast.LENGTH_LONG);
      return;
    }
    String[] items = managedSubIds.stream().map(String::valueOf).toArray(String[]::new);
    new AlertDialog.Builder(getActivity())
        .setTitle(R.string.select_esim_profile)
        .setItems(
            items,
            (dialog, which) -> {
              int subId = Integer.parseInt(items[which]);
              startSwitchEsim(subId, switchOn);
            })
        .show();
  }

  private void startSwitchEsim(int subId, boolean switchOn) {
    ContextCompat.registerReceiver(
        getActivity(),
        mSwitchEsimReceiver,
        new IntentFilter(ACTION_SWITCH_ESIM),
        ContextCompat.RECEIVER_EXPORTED);
    PendingIntent pi =
        PendingIntent.getBroadcast(
            getActivity(),
            0,
            new Intent(ACTION_SWITCH_ESIM).putExtra(EXTRA_SWITCH_ON, switchOn),
            PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT);
    int targetSubId = switchOn ? subId : SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    try {
      mEuiccManager.switchToSubscription(targetSubId, /* portIndex= */ 0, /* callbackIntent= */ pi);
      showToast(getString(R.string.esim_switch_started), Toast.LENGTH_SHORT);
    } catch (Exception e) {
      Log.e(TAG, "Failed to switch eSIM", e);
      showToast("Failed to start switch: " + e.getMessage(), Toast.LENGTH_LONG);
    }
  }

  private void showSimPowerDialog(boolean powerOn) {
    if (getActivity() == null || getActivity().isFinishing()) {
      return;
    }
    final View dialogView =
        getActivity().getLayoutInflater().inflate(R.layout.simple_edittext, null);
    final EditText slotIndexEditText = dialogView.findViewById(R.id.input);
    slotIndexEditText.setText("1"); // eSIM is typically slot 1
    new AlertDialog.Builder(getActivity())
        .setTitle(R.string.sim_slot_prompt_title)
        .setView(dialogView)
        .setPositiveButton(
            android.R.string.ok,
            (dialogInterface, i) -> {
              try {
                int slotIndex = Integer.parseInt(slotIndexEditText.getText().toString());
                setSimPowerState(slotIndex, /* portIndex= */ 0, powerOn);
              } catch (NumberFormatException e) {
                showToast("Invalid slot index", Toast.LENGTH_LONG);
              }
            })
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  private void setSimPowerState(int slotIndex, int portIndex, boolean powerOn) {
    try {
      if (mTelephonyManager == null) {
        showToast("TelephonyManager unavailable", Toast.LENGTH_LONG);
        return;
      }
      int state = powerOn ? getSimPowerStateOn() : getSimPowerStateOff();
      boolean invoked = false;
      try {
        ReflectionUtil.invoke(
            mTelephonyManager,
            "setSimPowerStateForPort",
            new Class<?>[] {int.class, int.class, int.class},
            slotIndex,
            portIndex,
            state);
        invoked = true;
      } catch (ReflectionUtil.ReflectionIsTemporaryException | RuntimeException e) {
        // Ignore and try slot-level below.
      }
      if (!invoked) {
        try {
          ReflectionUtil.invoke(
              mTelephonyManager,
              "setSimPowerStateForSlot",
              new Class<?>[] {int.class, int.class},
              slotIndex,
              state);
          invoked = true;
        } catch (ReflectionUtil.ReflectionIsTemporaryException | RuntimeException e) {
          Log.e(TAG, "Failed to call setSimPowerStateForSlot", e);
        }
      }
      if (!invoked) {
        showToast(
            getString(R.string.sim_power_toggle_failed, "API not available"), Toast.LENGTH_LONG);
        return;
      }
      showToast(
          powerOn
              ? getString(R.string.sim_power_toggle_success_on)
              : getString(R.string.sim_power_toggle_success_off),
          Toast.LENGTH_SHORT);
    } catch (SecurityException se) {
      Log.e(TAG, "Failed to switch SIM power", se);
      showToast(
          getString(R.string.sim_power_toggle_failed, se.getMessage()), Toast.LENGTH_LONG);
    } catch (Exception e) {
      Log.e(TAG, "Failed to switch SIM power", e);
      showToast(
          getString(R.string.sim_power_toggle_failed, e.getMessage()), Toast.LENGTH_LONG);
    }
  }

  private int getSimPowerStateOn() {
    try {
      return ReflectionUtil.intConstant(TelephonyManager.class, "SIM_POWER_STATE_ON");
    } catch (ReflectionUtil.ReflectionIsTemporaryException e) {
      return SIM_POWER_STATE_ON_FALLBACK;
    }
  }

  private int getSimPowerStateOff() {
    try {
      return ReflectionUtil.intConstant(TelephonyManager.class, "SIM_POWER_STATE_OFF");
    } catch (ReflectionUtil.ReflectionIsTemporaryException e) {
      return SIM_POWER_STATE_OFF_FALLBACK;
    }
  }

  private void launchSystemEsimManagement() {
    startActivitySafe(
        new Intent(EuiccManager.ACTION_MANAGE_EMBEDDED_SUBSCRIPTIONS),
        new Intent(Settings.ACTION_WIRELESS_SETTINGS));
  }

  private void showManagedEsimSettingsUi() {
    Set<Integer> managedSubIds = getSubscriptionIds();
    if (managedSubIds == null || managedSubIds.isEmpty()) {
      showToast(getString(R.string.esim_switch_failed_no_profiles), Toast.LENGTH_LONG);
      return;
    }
    String[] items = managedSubIds.stream().map(String::valueOf).toArray(String[]::new);
    new AlertDialog.Builder(getActivity())
        .setTitle(R.string.select_esim_profile)
        .setItems(
            items,
            (dialog, which) -> {
              int subId = Integer.parseInt(items[which]);
              openSimSettings(subId);
            })
        .show();
  }

  private void openSimSettings(int subId) {
    Intent directFragmentIntent =
        new Intent(Intent.ACTION_MAIN)
            .setClassName(
                "com.android.settings", "com.android.settings.Settings$MobileNetworkActivity")
            .putExtra(EXTRA_SUBSCRIPTION_ID, subId)
            .putExtra(EXTRA_SUB_ID, subId)
            .putExtra("android.telephony.extra.SUBSCRIPTION_INDEX", subId);
    Intent genericIntent = new Intent(ACTION_MOBILE_NETWORK_SETTINGS);
    genericIntent.putExtra(EXTRA_SUBSCRIPTION_ID, subId);
    genericIntent.putExtra(EXTRA_SUB_ID, subId);
    genericIntent.putExtra("android.telephony.extra.SUBSCRIPTION_INDEX", subId);
    startActivitySafe(
        directFragmentIntent,
        genericIntent,
        new Intent(EuiccManager.ACTION_MANAGE_EMBEDDED_SUBSCRIPTIONS),
        new Intent(Settings.ACTION_WIRELESS_SETTINGS));
  }

  private void startActivitySafe(Intent primary, Intent... fallbacks) {
    Intent[] intents = new Intent[fallbacks.length + 1];
    intents[0] = primary;
    System.arraycopy(fallbacks, 0, intents, 1, fallbacks.length);
    for (Intent intent : intents) {
      try {
        startActivity(intent);
        return;
      } catch (ActivityNotFoundException | SecurityException e) {
        // try next
      }
    }
    showToast("Unable to open requested settings screen", Toast.LENGTH_LONG);
  }

  private void showToast(String msg, int duration) {
    Activity activity = getActivity();
    if (activity == null || activity.isFinishing()) {
      Log.w(TAG, "Not toasting '" + msg + "' as activity is finishing or finished");
      return;
    }
    Log.d(TAG, "Showing toast: " + msg);
    Toast.makeText(activity, msg, duration).show();
  }

  private void showDownloadEsimUi() {
    if (getActivity() == null || getActivity().isFinishing()) {
      return;
    }

    final View dialogView =
        getActivity().getLayoutInflater().inflate(R.layout.esim_dialog_layout, null);
    final EditText activationCodeEditText =
        (EditText) dialogView.findViewById(R.id.activation_code);
    final CheckBox activateAfterDownloadCheckBox =
        (CheckBox) dialogView.findViewById(R.id.activate_esim);

    new AlertDialog.Builder(getActivity())
        .setTitle(R.string.esim_activation_code)
        .setView(dialogView)
        .setPositiveButton(
            android.R.string.ok,
            (dialogInterface, i) -> {
              final String activationCodeString = activationCodeEditText.getText().toString();
              startEsimDownload(activationCodeString, activateAfterDownloadCheckBox.isChecked());
            })
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  private void showDeleteEsimUi() {
    if (getActivity() == null || getActivity().isFinishing()) {
      return;
    }

    final View dialogView =
        getActivity().getLayoutInflater().inflate(R.layout.simple_edittext, null);
    final EditText subIdEditText = dialogView.findViewById(R.id.input);

    new AlertDialog.Builder(getActivity())
        .setTitle(R.string.delete_esim_dialog_title)
        .setView(dialogView)
        .setPositiveButton(
            android.R.string.ok,
            (dialogInterface, i) -> {
              final String subId = subIdEditText.getText().toString();
              deleteEsim(Integer.parseInt(subId));
            })
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  private void startEsimDownload(String activationCode, boolean switchAfterDownload) {
    ContextCompat.registerReceiver(
        getActivity(),
        mDownloadESIMReceiver,
        new IntentFilter(ACTION_DOWNLOAD_ESIM),
        ContextCompat.RECEIVER_EXPORTED);
    DownloadableSubscription downloadableSubscription =
        DownloadableSubscription.forActivationCode(activationCode);
    PendingIntent pi =
        PendingIntent.getBroadcast(
            getActivity(),
            0,
            new Intent(ACTION_DOWNLOAD_ESIM),
            PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT);
    mEuiccManager.downloadSubscription(downloadableSubscription, switchAfterDownload, pi);
    Log.v(
        TAG,
        "started downloading eSIM, "
            + "activationCode : "
            + activationCode
            + ", switchAfterDownload : "
            + switchAfterDownload);
    showToast("started downloading eSIM", Toast.LENGTH_LONG);
  }

  private void deleteEsim(int subId) {
    ContextCompat.registerReceiver(
        getActivity(),
        mDeleteESIMReceiver,
        new IntentFilter(ACTION_DELETE_ESIM),
        ContextCompat.RECEIVER_EXPORTED);
    PendingIntent pi =
        PendingIntent.getBroadcast(
            getActivity(),
            0,
            new Intent(ACTION_DELETE_ESIM),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    mEuiccManager.deleteSubscription(subId, pi);

    showToast("started deleting eSIM", Toast.LENGTH_LONG);
  }
}
