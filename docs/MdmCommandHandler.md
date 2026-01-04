# MDM Command Handling (Qbit DPC)

Command handling is performed inside `MdmSyncManager.processCommand(...)` (`android-testdpc/src/main/java/mdm/qubit/dpc/mdm/MdmSyncManager.java`).

Supported commands:
- `install_apk_package`, `uninstall_app`, `suspend_app`, `hide_app`, `block_uninstall`, `set_user_restrictions`, `set_location`, `wipe`, `set_lock_screen`, `set_password_policy`, `set_password_complexity`, `request_password_change`: see `MdmSyncManager` for per-command payloads and meta. Inventory is attached for install/uninstall/suspend/hide.
- `set_override_apn`: pushes override APN profiles to the device (P+). Uses `DevicePolicyManager.addOverrideApn/removeOverrideApn/setOverrideApnsEnabled`.
  - Payload shape:
    ```json
    {
      "enable": true,
      "clear_existing": true,
      "apns": [
        {
          "operator_numeric": "20404",      // MCC+MNC (required)
          "entry_name": "globalnet",        // required
          "apn_name": "globalnet",          // required
          "apn_type_bitmask": 17,           // required (default|supl etc.)
          "protocol": 0,                    // optional (-1 unset, 0 IPV4V6, 1 IPV4, 2 IPV6)
          "roaming_protocol": 0,
          "auth_type": -1,                  // -1 unset, 0 none, 1 PAP, 2 CHAP, 3 PAP/CHAP
          "user": "", "password": "",
          "proxy": "", "proxy_port": -1,
          "mmsc": "", "mms_proxy": "", "mms_port": -1,
          "carrier_enabled": true,
          "network_type_bitmask": 0,
          "mvno_type": -1,                  // -1 unset; 0 spn, 1 imsi, 2 gid, 3 iccid
          "mvno_match_data": ""             // required if mvno_type set
        }
      ]
    }
    ```
  - ACK meta: `cleared`, `inserted`, `enabled`, `apns` (per APN: id/error + entry/apn/operator), plus `clear_error`/`enable_error` if thrown. `success=false` on partial failures.
- `set_always_on_vpn`: enable/disable always-on VPN and optional lockdown.
  - Payload:
    ```json
    {
      "package": "com.vpn.app",     // required when enable=true; ignored when enable=false
      "enable": true,               // default true; false clears always-on VPN
      "lockdown": true,             // block connections without VPN
      "exempted_packages": ["com.android.phone", "com.android.mms"]  // optional, Q+ only
    }
    ```
  - Behavior:
    - Q+: `setAlwaysOnVpnPackage(admin, pkg|null, lockdown, exempted_packages|null)`
    - N–P: `setAlwaysOnVpnPackage(admin, pkg|null, lockdown)` (no whitelist support)
    - Pre-N: error `requires_api_24`.
  - ACK meta: `enable`, `package` (null when disabled), `lockdown`, optional `exempted_packages`, `apply_error` if DPM throws; `success=false` on errors.
All other command types are currently marked unsupported and ACK-ed with `success: false` and `error: "unsupported_type"`.
