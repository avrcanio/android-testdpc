package mdm.qubit.dpc.mdm;

import android.app.admin.DevicePolicyManager;
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
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
import android.net.Uri;
import mdm.qubit.dpc.DeviceAdminReceiver;
import mdm.qubit.dpc.FileLogger;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Utility that collects a snapshot of installed packages for MDM acks. */
public final class InventoryReporter {
  // TelephonyManager.SIM_STATE_LOADED was introduced in newer SDKs; define locally for compilation.
  private static final int SIM_STATE_LOADED = 10;
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
        status.put("network_operator", emptyToNull(tm.getNetworkOperator()));
        status.put("network_operator_name", emptyToNull(tm.getNetworkOperatorName()));
        status.put("network_country_iso", emptyToNull(tm.getNetworkCountryIso()));
        status.put("sim_country_iso", emptyToNull(tm.getSimCountryIso()));
        status.put("is_roaming", tm.isNetworkRoaming());
        status.put("sim_state", simStateToString(tm.getSimState()));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
          int carrierId = tm.getSimCarrierId();
          if (carrierId != TelephonyManager.UNKNOWN_CARRIER_ID) {
            status.put("carrier_id", carrierId);
          }
        }
        appendApnInfo(context, status);
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
        try {
          String vpnPkg = dpm.getAlwaysOnVpnPackage(admin);
          if (!TextUtils.isEmpty(vpnPkg)) {
            status.put("always_on_vpn_package", vpnPkg);
          }
          status.put("always_on_vpn_lockdown", dpm.isAlwaysOnVpnLockdownEnabled(admin));
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Set<String> exempt = dpm.getAlwaysOnVpnLockdownWhitelist(admin);
            if (exempt != null && !exempt.isEmpty()) {
              status.put("always_on_vpn_exempted", new JSONArray(exempt));
            }
          }
        } catch (Exception e) {
          FileLogger.log(context, "InventoryReporter vpn status error: " + e.getMessage());
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

  private static String simStateToString(int state) {
    switch (state) {
      case TelephonyManager.SIM_STATE_ABSENT:
        return "absent";
      case TelephonyManager.SIM_STATE_PIN_REQUIRED:
        return "pin_required";
      case TelephonyManager.SIM_STATE_PUK_REQUIRED:
        return "puk_required";
      case TelephonyManager.SIM_STATE_NETWORK_LOCKED:
        return "network_locked";
      case TelephonyManager.SIM_STATE_READY:
        return "ready";
      case TelephonyManager.SIM_STATE_NOT_READY:
        return "not_ready";
      case TelephonyManager.SIM_STATE_PERM_DISABLED:
        return "perm_disabled";
      case TelephonyManager.SIM_STATE_CARD_IO_ERROR:
        return "card_io_error";
      case TelephonyManager.SIM_STATE_CARD_RESTRICTED:
        return "card_restricted";
      case SIM_STATE_LOADED:
        return "loaded";
      case TelephonyManager.SIM_STATE_UNKNOWN:
      default:
        return "unknown";
    }
  }

  private static void appendApnInfo(Context context, JSONObject status) {
    ContentResolver cr = context.getContentResolver();
    Cursor cursor = null;
    Cursor pref = null;
    boolean anyApn = false;
    String apnError = null;
    try {
      Uri carriers = Uri.parse("content://telephony/carriers");
      String[] cols = new String[] {"name", "apn", "type", "mcc", "mnc", "numeric", "current"};
      cursor = cr.query(carriers, cols, null, null, null);
      JSONArray list = new JSONArray();
      if (cursor != null) {
        while (cursor.moveToNext()) {
          anyApn = true;
          JSONObject apn = new JSONObject();
          apn.put("name", emptyToNull(cursor.getString(cursor.getColumnIndex("name"))));
          apn.put("apn", emptyToNull(cursor.getString(cursor.getColumnIndex("apn"))));
          apn.put("type", emptyToNull(cursor.getString(cursor.getColumnIndex("type"))));
          apn.put("mcc", emptyToNull(cursor.getString(cursor.getColumnIndex("mcc"))));
          apn.put("mnc", emptyToNull(cursor.getString(cursor.getColumnIndex("mnc"))));
          apn.put("numeric", emptyToNull(cursor.getString(cursor.getColumnIndex("numeric"))));
          String current = cursor.getString(cursor.getColumnIndex("current"));
          if (!TextUtils.isEmpty(current)) {
            apn.put("current", "1".equals(current) || "true".equalsIgnoreCase(current));
          }
          list.put(apn);
        }
      }
      if (list.length() > 0) {
        status.put("apn_list", list);
      }
      Uri preferred = Uri.parse("content://telephony/carriers/preferapn");
      pref = cr.query(preferred, cols, null, null, null);
      if (pref != null && pref.moveToFirst()) {
        anyApn = true;
        JSONObject apn = new JSONObject();
        apn.put("name", emptyToNull(pref.getString(pref.getColumnIndex("name"))));
        apn.put("apn", emptyToNull(pref.getString(pref.getColumnIndex("apn"))));
        apn.put("type", emptyToNull(pref.getString(pref.getColumnIndex("type"))));
        apn.put("mcc", emptyToNull(pref.getString(pref.getColumnIndex("mcc"))));
        apn.put("mnc", emptyToNull(pref.getString(pref.getColumnIndex("mnc"))));
        apn.put("numeric", emptyToNull(pref.getString(pref.getColumnIndex("numeric"))));
        status.put("apn_preferred", apn);
      }
    } catch (Exception e) {
      apnError = e.getClass().getSimpleName();
      FileLogger.log(context, "InventoryReporter APN query error: " + e.getMessage());
    } finally {
      closeQuietly(cursor);
      closeQuietly(pref);
      if (!anyApn && apnError != null) {
        // Let backend know APN query failed (likely due to missing carrier permissions on non-priv builds)
        try {
          status.put("apn_query_error", apnError);
        } catch (Exception ignore) {
          // ignore
        }
      }
    }
  }

  private static String emptyToNull(String value) {
    return value == null || value.trim().isEmpty() ? null : value;
  }

  private static void closeQuietly(Cursor c) {
    if (c != null) {
      try {
        c.close();
      } catch (Exception ignore) {
        // ignore
      }
    }
  }
}
