package mdm.qubit.dpc.mdm;

import android.app.admin.DevicePolicyManager;
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.UserManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import mdm.qubit.dpc.DeviceAdminReceiver;
import mdm.qubit.dpc.FileLogger;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Utility that collects a snapshot of installed packages for MDM acks. */
public final class InventoryReporter {
  private InventoryReporter() {}

  public static JSONArray collect(Context context) {
    JSONArray arr = new JSONArray();
    appendDeviceStatus(context, arr);
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

  private static void appendDeviceStatus(Context context, JSONArray arr) {
    JSONObject status = new JSONObject();
    try {
      status.put("kind", "device_status");
      status.put("location_enabled", isLocationEnabled(context));
      WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
      if (wifi != null) {
        status.put("wifi_enabled", wifi.isWifiEnabled());
        WifiInfo info = wifi.getConnectionInfo();
        if (info != null) {
          status.put("wifi_ssid", sanitizeSsid(info.getSSID()));
          status.put("wifi_bssid", emptyToNull(info.getBSSID()));
        }
      }
      TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
      if (tm != null) {
        status.put("data_enabled", isDataEnabled(tm));
        status.put("data_roaming", tm.isNetworkRoaming());
        status.put("data_state", tm.getDataState());
        status.put("sim_operator", emptyToNull(tm.getSimOperator()));
        status.put("sim_operator_name", emptyToNull(tm.getSimOperatorName()));
      }
      BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
      if (bt != null) {
        status.put("bluetooth_enabled", bt.isEnabled());
      }
      ConnectivityManager cm =
          (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
      if (cm != null) {
        status.put("vpn_active", isVpnActive(cm, status));
      }
      BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
      if (bm != null) {
        status.put("battery_level", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
        status.put("battery_plugged",
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
                == BatteryManager.BATTERY_STATUS_CHARGING);
      } else {
        Intent batt =
            context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batt != null) {
          int level = batt.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
          int scale = batt.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
          status.put("battery_level", level >= 0 ? (level * 100 / scale) : JSONObject.NULL);
          int plugged = batt.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
          status.put("battery_plugged", plugged != 0);
        }
      }
      DevicePolicyManager dpm =
          (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
      ComponentName admin = DeviceAdminReceiver.getComponentName(context);
      if (dpm != null && admin != null) {
        status.put("auto_time", safeBoolean(() -> dpm.getAutoTimeEnabled(admin)));
        status.put("auto_timezone", safeBoolean(() -> dpm.getAutoTimeZoneEnabled(admin)));
        status.put("camera_disabled", dpm.getCameraDisabled(admin));
        status.put("keyguard_disabled_features", dpm.getKeyguardDisabledFeatures(admin));
        List<String> acc =
            dpm.getPermittedAccessibilityServices(admin);
        if (acc != null) {
          status.put("permitted_accessibility_services", new JSONArray(acc));
        }
        List<String> ime =
            dpm.getPermittedInputMethods(admin);
        if (ime != null) {
          status.put("permitted_input_methods", new JSONArray(ime));
        }
      }
      UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
      if (um != null) {
        Bundle restrictions = um.getUserRestrictions();
        JSONObject resObj = new JSONObject();
        if (restrictions != null) {
          Set<String> keys = restrictions.keySet();
          for (String key : keys) {
            resObj.put(key, restrictions.getBoolean(key));
          }
        }
        status.put("user_restrictions", resObj);
      }
    } catch (Exception e) {
      FileLogger.log(context, "InventoryReporter device status error: " + e.getMessage());
    }
    arr.put(status);
  }

  private static boolean isLocationEnabled(Context context) {
    try {
      LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
      return lm != null && lm.isLocationEnabled();
    } catch (Exception e) {
      return false;
    }
  }

  private static boolean isDataEnabled(TelephonyManager tm) {
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        return tm.isDataEnabled();
      }
      return tm.getDataState() == TelephonyManager.DATA_CONNECTED;
    } catch (Exception e) {
      return false;
    }
  }

  private static boolean isVpnActive(ConnectivityManager cm, JSONObject status) {
    boolean active = false;
    try {
      Network[] networks = cm.getAllNetworks();
      if (networks != null) {
        for (Network n : networks) {
          NetworkCapabilities caps = cm.getNetworkCapabilities(n);
          if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            active = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
              Object info = caps.getTransportInfo();
              if (info != null && "android.net.VpnTransportInfo".equals(info.getClass().getName())) {
                try {
                  String session =
                      (String) info.getClass().getMethod("getSession").invoke(info);
                  if (!TextUtils.isEmpty(session)) {
                    status.put("vpn_session", session);
                  }
                  String pkg =
                      (String) info.getClass().getMethod("getVpnManagerPackage").invoke(info);
                  if (!TextUtils.isEmpty(pkg)) {
                    status.put("vpn_package", pkg);
                  }
                } catch (Exception ignore) {
                  // best-effort
                }
              }
            }
            break;
          }
        }
      }
    } catch (Exception ignore) {
      // best-effort
    }
    return active;
  }

  private interface BooleanSupplierWithThrow {
    boolean getAsBoolean() throws Exception;
  }

  private static boolean safeBoolean(BooleanSupplierWithThrow supplier) {
    try {
      return supplier.getAsBoolean();
    } catch (Exception e) {
      return false;
    }
  }

  private static String sanitizeSsid(String ssid) {
    if (ssid == null) return null;
    String trimmed = ssid.replace("\"", "");
    return trimmed.equalsIgnoreCase("<unknown ssid>") ? null : trimmed;
  }

  private static String emptyToNull(String value) {
    return value == null || value.trim().isEmpty() ? null : value;
  }
}
