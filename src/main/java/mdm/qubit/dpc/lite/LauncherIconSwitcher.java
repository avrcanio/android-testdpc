package mdm.qubit.dpc.lite;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

/**
 * Switches launcher aliases between red/green icons based on MQTT connectivity status.
 */
final class LauncherIconSwitcher {

  private static final String TAG = "LauncherIconSwitcher";
  private static final String GREEN_ALIAS = "mdm.qubit.dpc.LauncherIconGreen";
  private static final String RED_ALIAS = "mdm.qubit.dpc.LauncherIconRed";
  private static volatile Boolean sLastGreenEnabled = null;

  private LauncherIconSwitcher() {}

  static void apply(Context context, String status) {
    if (context == null || status == null) {
      return;
    }
    boolean shouldBeGreen = isGreenStatus(status);
    if (sLastGreenEnabled != null && sLastGreenEnabled == shouldBeGreen) {
      return;
    }
    try {
      PackageManager pm = context.getPackageManager();
      ComponentName green = new ComponentName(context, GREEN_ALIAS);
      ComponentName red = new ComponentName(context, RED_ALIAS);
      pm.setComponentEnabledSetting(
          green,
          shouldBeGreen
              ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
              : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
          PackageManager.DONT_KILL_APP);
      pm.setComponentEnabledSetting(
          red,
          shouldBeGreen
              ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
              : PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
          PackageManager.DONT_KILL_APP);
      sLastGreenEnabled = shouldBeGreen;
    } catch (Exception e) {
      Log.w(TAG, "Failed to switch launcher icon", e);
    }
  }

  private static boolean isGreenStatus(String status) {
    switch (status) {
      case "connected":
      case "subscribed":
      case "sync":
      case "heartbeat":
        return true;
      default:
        return false;
    }
  }
}
