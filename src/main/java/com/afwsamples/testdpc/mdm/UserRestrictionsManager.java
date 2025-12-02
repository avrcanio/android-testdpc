package com.afwsamples.testdpc.mdm;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.UserManager;
import com.afwsamples.testdpc.DeviceAdminReceiver;
import com.afwsamples.testdpc.FileLogger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Helper to snapshot and apply user restrictions with per-key results. */
public final class UserRestrictionsManager {
  private static final Map<String, String> KEY_MAP = new HashMap<>();
  private static final Set<String> SUPPORTED_KEYS = new HashSet<>();

  static {
    addKey("no_add_managed_profile", "no_add_managed_profile");
    addKey("no_add_user", "no_add_user");
    addKey("no_adjust_volume", "no_adjust_volume");
    addKey("no_control_apps", "no_control_apps");
    addKey("no_bluetooth", "no_bluetooth");
    addKey("no_change_wifi_state", "no_change_wifi_state");
    addKey("no_config_bluetooth", "no_config_bluetooth");
    addKey("no_config_cell_broadcasts", "no_config_cell_broadcasts");
    addKey("no_config_credentials", "no_config_credentials");
    addKey("no_config_mobile_networks", "no_config_mobile_networks");
    addKey("no_config_tethering", "no_config_tethering");
    addKey("no_config_vpn", "no_config_vpn");
    addKey("no_config_wifi", "no_config_wifi");
    addKey("no_content_capture", "no_content_capture");
    addKey("no_content_suggestions", "no_content_suggestions");
    addKey("no_create_windows", "no_create_windows");
    addKey("no_system_error_dialogs", "no_system_error_dialogs");
    addKey("no_data_roaming", "no_data_roaming");
    addKey("no_debugging_features", "no_debugging_features");
    addKey("no_factory_reset", "no_factory_reset");
    addKey("no_fun", "no_fun");
    addKey("no_install_apps", "no_install_apps");
    addKey("no_install_unknown_sources", "no_install_unknown_sources");
    addKey("no_install_unknown_sources_globally", "no_install_unknown_sources_globally");
    addKey("no_modify_accounts", "no_modify_accounts");
    addKey("no_physical_media", "no_physical_media");
    addKey("no_network_reset", "no_network_reset");
    addKey("no_outgoing_beam", "no_outgoing_beam");
    addKey("no_outgoing_calls", "no_outgoing_calls");
    addKey("no_remove_managed_profile", "no_remove_managed_profile");
    addKey("no_remove_user", "no_remove_user");
    addKey("no_safe_boot", "no_safe_boot");
    addKey("no_set_user_icon", "no_set_user_icon");
    addKey("no_set_wallpaper", "no_set_wallpaper");
    addKey("no_share_location", "no_share_location");
    addKey("no_sms", "no_sms");
    addKey("no_uninstall_apps", "no_uninstall_apps");
    addKey("no_unmute_microphone", "no_unmute_microphone");
    addKey("no_usb_file_transfer", "no_usb_file_transfer");
    addKey("ensure_verify_apps", "ensure_verify_apps");
    addKey("no_autofill", "no_autofill");
    addKey("no_bluetooth_sharing", "no_bluetooth_sharing");
    addKey("no_user_switch", "no_user_switch");
    addKey("no_config_location", "no_config_location");
    addKey("no_airplane_mode", "no_airplane_mode");
    addKey("no_config_brightness", "no_config_brightness");
    addKey("no_config_date_time", "no_config_date_time");
    addKey("no_config_screen_timeout", "no_config_screen_timeout");
    addKey("no_ambient_display", "no_ambient_display");
    addKey("no_printing", "no_printing");
    addKey("disallow_config_private_dns", "disallow_config_private_dns");
    addKey("disallow_microphone_toggle", "disallow_microphone_toggle");
    addKey("disallow_camera_toggle", "disallow_camera_toggle");
    addKey("no_wifi_tethering", "no_wifi_tethering");
    addKey("no_sharing_admin_configured_wifi", "no_sharing_admin_configured_wifi");
    addKey("no_wifi_direct", "no_wifi_direct");
    addKey("no_add_wifi_config", "no_add_wifi_config");
    addKey("no_cellular_2g", "no_cellular_2g");
    addKey("disallow_config_default_apps", "disallow_config_default_apps");
    addKey("no_config_locale", "no_config_locale");
    addKey("no_ultra_wideband_radio", "no_ultra_wideband_radio");
    addKey("no_assist_content", "no_assist_content");
    addKey("no_sim_globally", "no_sim_globally");
    addKey("no_add_private_profile", "no_add_private_profile");
  }

  private static void addKey(String apiName, String actualKey) {
    KEY_MAP.put(apiName, actualKey);
    KEY_MAP.put(actualKey, actualKey);
    SUPPORTED_KEYS.add(actualKey);
  }

  private UserRestrictionsManager() {}

  public static JSONObject snapshot(Context context) {
    JSONObject meta = new JSONObject();
    JSONObject restrictions = new JSONObject();
    try {
      UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
      Bundle bundle = um != null ? um.getUserRestrictions() : new Bundle();
      for (String key : SUPPORTED_KEYS) {
        boolean val = bundle.getBoolean(key, false);
        restrictions.put(key, val);
      }
      meta.put("restrictions", restrictions);
      meta.put("success", true);
    } catch (Exception e) {
      FileLogger.log(context, "UserRestrictions snapshot error: " + e.getMessage());
      try {
        meta.put("success", false);
        meta.put("error", e.getMessage());
      } catch (Exception ignore) {
        // ignore
      }
    }
    return meta;
  }

  public static JSONObject apply(Context context, JSONObject requested) {
    JSONObject result = new JSONObject();
    JSONArray perKey = new JSONArray();
    boolean allOk = true;

    DevicePolicyManager dpm =
        (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
    ComponentName admin = DeviceAdminReceiver.getComponentName(context);
    DevicePolicyManager target = dpm;
    boolean isOrgOwned =
        dpm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            && dpm.isOrganizationOwnedDeviceWithManagedProfile();
    if (isOrgOwned && dpm != null && admin != null) {
      DevicePolicyManager parent = dpm.getParentProfileInstance(admin);
      if (parent != null) {
        target = parent;
      }
    }

    if (target == null || admin == null) {
      try {
        result.put("all_ok", false);
        result.put("error", "dpm_or_admin_null");
      } catch (Exception ignore) {
        // ignore
      }
      return result;
    }

    Iterator<String> keys = requested.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      JSONObject per = new JSONObject();
      try {
        String actualKey = KEY_MAP.get(key);
        per.put("key", key);
        per.put("resolved_key", actualKey);
        if (actualKey == null || !SUPPORTED_KEYS.contains(actualKey)) {
          allOk = false;
          per.put("success", false);
          per.put("message", "unsupported_key");
          perKey.put(per);
          continue;
        }
        boolean value = requested.optBoolean(key, false);
        try {
          if (value) {
            target.addUserRestriction(admin, actualKey);
          } else {
            target.clearUserRestriction(admin, actualKey);
          }
          per.put("success", true);
          per.put("value", value);
        } catch (Exception e) {
          allOk = false;
          per.put("success", false);
          per.put("value", value);
          per.put("message", e.getMessage());
          FileLogger.log(
              context, "UserRestrictions apply error key=" + actualKey + " err=" + e.getMessage());
        }
      } catch (Exception ignore) {
        // ignore
      }
      perKey.put(per);
    }

    try {
      result.put("all_ok", allOk);
      result.put("per_key", perKey);
    } catch (Exception ignore) {
      // ignore
    }
    return result;
  }

  public static Set<String> supportedKeys() {
    return SUPPORTED_KEYS;
  }
}
