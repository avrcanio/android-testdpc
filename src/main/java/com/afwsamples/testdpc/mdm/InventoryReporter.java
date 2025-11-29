package com.afwsamples.testdpc.mdm;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import com.afwsamples.testdpc.DeviceAdminReceiver;
import com.afwsamples.testdpc.FileLogger;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Utility that collects a snapshot of installed packages for MDM acks. */
public final class InventoryReporter {
  private InventoryReporter() {}

  public static JSONArray collect(Context context) {
    JSONArray arr = new JSONArray();
    try {
      PackageManager pm = context.getPackageManager();
      List<PackageInfo> infos = pm.getInstalledPackages(0);
      DevicePolicyManager dpm =
          (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
      ComponentName admin = DeviceAdminReceiver.getComponentName(context);
      boolean isDo = dpm != null && dpm.isDeviceOwnerApp(context.getPackageName());
      for (PackageInfo info : infos) {
        JSONObject obj = new JSONObject();
        String pkg = info.packageName;
        try {
          ApplicationInfo ai = info.applicationInfo;
          long versionCode =
              Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                  ? info.getLongVersionCode()
                  : info.versionCode;
          obj.put("package", pkg);
          obj.put("version_code", versionCode);
          obj.put("enabled_state", pm.getApplicationEnabledSetting(pkg));
          obj.put("last_update", info.lastUpdateTime / 1000);
          obj.put("first_install", info.firstInstallTime / 1000);
          String installer = pm.getInstallerPackageName(pkg);
          if (installer != null) {
            obj.put("installer", installer);
          }
          boolean hidden =
              isDo && dpm != null && admin != null && dpm.isApplicationHidden(admin, pkg);
          obj.put("hidden", hidden);
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            boolean suspended =
                isDo && dpm != null && admin != null && dpm.isPackageSuspended(admin, pkg);
            obj.put("suspended", suspended);
          }
          obj.put("system_app", (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
          arr.put(obj);
        } catch (Exception perPkg) {
          FileLogger.log(context, "InventoryReporter package skip " + pkg + ": " + perPkg.getMessage());
        }
      }
      FileLogger.log(context, "InventoryReporter collected count=" + arr.length());
    } catch (Exception e) {
      FileLogger.log(context, "InventoryReporter error: " + e.getMessage());
    }
    return arr;
  }
}
