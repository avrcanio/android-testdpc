package com.afwsamples.testdpc.mdm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;
import com.afwsamples.testdpc.mdm.MdmSyncManager;

public class MdmWakeReceiver extends BroadcastReceiver {

  private static final String TAG = "QbitDPC-MdmWake";

  @Override
  public void onReceive(Context context, Intent intent) {
    String deviceId = intent != null ? intent.getStringExtra("device_id") : null;
    Log.i(TAG, "MdmWakeReceiver onReceive, deviceId=" + deviceId + ", intent=" + intent);

    Toast.makeText(context, "MDM wake-up received for " + deviceId, Toast.LENGTH_SHORT).show();

    MdmSyncManager.syncNow(context.getApplicationContext(), null);
  }
}
