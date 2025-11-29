/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.afwsamples.testdpc.provision;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;
import com.afwsamples.testdpc.EnrolConfig;
import com.afwsamples.testdpc.provision.BaselineProvisioner;
import com.afwsamples.testdpc.R;
import java.util.Locale;

/**
 * Activity that gets launched by the {@link
 * android.app.admin.DevicePolicyManager#ACTION_PROVISIONING_SUCCESSFUL} intent.
 */
public class ProvisioningSuccessActivity extends Activity {
  private static final String TAG = "ProvisioningSuccess";
  private static final String EXTRA_PROVISIONING_SUPPORT_URL =
      "android.app.extra.PROVISIONING_SUPPORT_URL";

  @Override
  public void onCreate(Bundle icicle) {
    super.onCreate(icicle);

    Intent intent = getIntent();
    Bundle adminExtras =
        intent.getBundleExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE);
    String enrolToken = null;
    String apkIndexUrl = null;
    String supportUrl = intent.getStringExtra(EXTRA_PROVISIONING_SUPPORT_URL);
    if (adminExtras != null) {
      enrolToken = adminExtras.getString("enrol_token");
      apkIndexUrl = adminExtras.getString("apk_index_url");
      Log.d(TAG, "adminExtras keys: " + adminExtras.keySet());
      Log.d(TAG, "enrol_token = " + enrolToken);
      Log.d(TAG, "apk_index_url = " + apkIndexUrl);
    } else {
      Log.d(TAG, "No admin extras bundle in intent");
    }

    if (supportUrl != null) {
      Log.d(TAG, "support_url = " + supportUrl);
    }

    EnrolConfig enrolConfig = new EnrolConfig(this);
    if (enrolToken != null) {
      enrolConfig.saveEnrolToken(enrolToken);
    }
    if (apkIndexUrl != null) {
      enrolConfig.saveApkIndexUrl(apkIndexUrl);
    }
    if (supportUrl != null) {
      enrolConfig.saveSupportUrl(supportUrl);
    } else {
      supportUrl = enrolConfig.getSupportUrl();
    }

    BaselineProvisioner.run(
        this,
        apkIndexUrl != null ? apkIndexUrl : enrolConfig.getApkIndexUrl(),
        enrolToken != null ? enrolToken : enrolConfig.getEnrolToken(),
        supportUrl,
        Long.toHexString(System.currentTimeMillis()).toUpperCase(Locale.US));

    PostProvisioningTask task = new PostProvisioningTask(this);
    if (!task.performPostProvisioningOperations(intent)) {
      finish();
      return;
    }

    Intent launchIntent = task.getPostProvisioningLaunchIntent(intent);
    if (launchIntent != null) {
      startActivity(launchIntent);
    } else {
      Log.e(TAG, "ProvisioningSuccessActivity.onCreate() invoked, but ownership " + "not assigned");
      Toast.makeText(this, R.string.device_admin_receiver_failure, Toast.LENGTH_LONG).show();
    }
    finish();
  }
}
