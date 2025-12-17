package mdm.qubit.dpc.lite;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;
import mdm.qubit.dpc.R;

/** Ensures non-lite launchers are hidden when running the lite variant. */
public final class LiteLauncherHider {

  private static final String TAG = "LiteLauncherHider";
  private static final String SETUP_LAUNCHER_CLASS = "mdm.qubit.dpc.SetupManagementLaunchActivity";

  private LiteLauncherHider() {}

  public static void apply(Context context) {
    if (context == null) {
      return;
    }
    try {
      if (context.getResources().getBoolean(R.bool.enable_setup_management_launcher)) {
        return;
      }
      PackageManager pm = context.getPackageManager();
      ComponentName component = new ComponentName(context.getPackageName(), SETUP_LAUNCHER_CLASS);
      pm.setComponentEnabledSetting(
          component,
          PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
          PackageManager.DONT_KILL_APP);
    } catch (Exception e) {
      Log.w(TAG, "Failed to apply launcher hiding", e);
    }
  }
}
