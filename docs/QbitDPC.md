# Qbit DPC Guide

Qbit-specific notes for this TestDPC fork. Core TestDPC docs stay in the upstream README; this file tracks our deltas and workflow.

## Overview
- Device owner / profile owner capable; package `com.afwsamples.testdpc`.
- Custom additions:
  - Baseline bootstrap at provisioning: QR passes `admin extras` with `enrol_token` + `apk_index_url`; we fetch `index.json`, install missing/outdated apps (DO install), then enrol and kick first sync before VPN comes up. Support URL (if present) is logged and shown on failure.
  - One-tap Tailscale install from Policy Management (DO only) via `https://qubitmdm.online/repo/mdm/tailscale.apk`.
  - Inventory upload after app mutations: full package snapshot is attached to ACKs and sent to `/api/mdm/inventory`.
  - Block uninstall command support (`block_uninstall` → `DevicePolicyManager.setUninstallBlocked`).
- Enrol state persists `device_id`, `device_token`, `policy_etag`, `policy_json`, `rotate_required`, `commands_pending`, `poll_interval_sec`, `mqtt_password`.
- MQTT Core credentials provider: `content://com.afwsamples.testdpc.mqttcredentials/credentials`.
- Build: Bazel (bazelisk).

## Prerequisites
- Android API 23+; Device Owner provisioned (QR/afw#/NFC).
- Network access to `qubitmdm.online` for downloads.

## Provisioning / Post-provision
1) Provision as Device Owner with QR that includes `enrol_token`, `apk_index_url`, optional support URL.  
2) BaselineProvisioner runs immediately: GET `apk_index_url` → read `channels.stable|beta` → install any missing/outdated APKs → enrol with `enrol_token` → trigger first sync.  
3) Manual: open app, Policy Management; optional **Install Tailscale** action remains.

Notes:
- Downloads stream via `PackageInstaller` with no-cache headers.
- If an APK install fails, we log and show toast (with support URL if provided).

## Build & deploy
From `android-testdpc/`:
```powershell
.\.bazelisk\bazel.exe build //:testdpc
.\.bazelisk\bazel.exe mobile-install //:testdpc --start_app
```
If Bazel cache is locked/corrupt: `bazel shutdown` then rebuild. Avoid `clean --expunge` unless needed.

## Provisioning logging & enrol
- Logging: `DeviceAdminReceiver`, `ProvisioningSuccessActivity`, `FinalizeActivity`, and `PolicyManagementActivity` log admin extras and enrol tokens to `provision_log.txt` (`/data/user/0/com.afwsamples.testdpc/files`).
- Persistence: `EnrolConfig` stores `enrol_token`, `apk_index_url`, `support_url`.
- Manual enrol: “Enrol Qubit” preference triggers `EnrolApiClient.enrolWithSavedToken(...)`; broadcast `com.qubit.mdm.ACTION_ENROL_STATE_UPDATED` refreshes UI.
- Enrol API: POST `https://user-admin.tailnet.qubitsecured.online/api/mdm/enrol` with `{ enrol_token, is_device_owner, os_version, sdk_int, device_model, device_manufacturer }`. Response includes `device_id`, `device_token`, `policy_etag`, `policy`, `commands_pending`, `poll_interval_sec`, `mqtt_password`. First call usually 201; repeats 409. Logs include requestId, HTTP code, body, TLS cert info.
- Trust: custom CA at `res/raw/qubit_tailnet_ca.crt` via `network_security_config.xml`.

## MDM sync & commands
- Trigger: toolbar sync icon or `com.qubit.mdm.ACTION_MDM_WAKE` broadcast.
- Flow: GET /policy → save (`PolicyConfig`), POST /inbox → handle commands, POST /ack.
- Commands in `MdmSyncManager.processCommand`:
  - `install_apk_package` (download+install; attaches inventory; posts `/inventory`)
  - `uninstall_app` (inventory + `/inventory`)
  - `suspend_app` (inventory + `/inventory`)
  - `hide_app` (inventory + `/inventory`)
  - `block_uninstall` (`setUninstallBlocked`; per-package meta in ACK)
  - `wipe` (maps flags; uses `wipeDevice` on U+, parent DPM for COPE pre-U)
- Inventory payload (in ACK meta and POST `/api/mdm/inventory`): list of `{ package, version_code, enabled_state, hidden, suspended, system_app, installer, first_install, last_update }` plus `request_id`, `timestamp`, `device_id` for the POST.
- Headers: MDM calls send both `X-Device-Token` and `Authorization: Device <token>`.
- Logging: requestId-tagged entries in logcat and `provision_log.txt`; inventory failures log server body on non-2xx.

## MQTT Core credentials handoff
- Provider authority: `com.afwsamples.testdpc.mqttcredentials`, URI `content://com.afwsamples.testdpc.mqttcredentials/credentials`.
- Columns: `device_id`, `mqtt_password`.
- Access: exported, gated to caller package `com.qubit.mqttcore` or self.
