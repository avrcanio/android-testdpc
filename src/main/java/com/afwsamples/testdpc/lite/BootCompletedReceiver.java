package com.afwsamples.testdpc.lite;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Starts LiteMqttService on boot if VPN is up (service itself waits up to 60s for VPN).
 */
public class BootCompletedReceiver extends BroadcastReceiver {

  private static final String TAG = "BootCompletedReceiver";

  @Override
  public void onReceive(Context context, Intent intent) {
    if (intent == null || intent.getAction() == null) {
      return;
    }
    if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
      Log.i(TAG, "Boot completed, requesting MQTT start after VPN check");
      Intent svc = new Intent(context, LiteMqttService.class);
      svc.setAction(LiteMqttService.ACTION_BOOT_START);
      try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
          context.startForegroundService(svc);
        } else {
          context.startService(svc);
        }
      } catch (Exception e) {
        Log.w(TAG, "Failed to start LiteMqttService on boot", e);
      }
    }
  }
}
