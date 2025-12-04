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
- Downloads stream via `PackageInstaller` with no-cache headers; APKs are buffered to a temp file (200MB guardrail) and SHA-256 is verified before install to avoid OOM.
- If an APK install fails, we log and show toast (with support URL if provided). Manifest/package-name mismatches (e.g., payload says `org.telegram.messenger` but APK is `org.telegram.messenger.web`) will be rejected by PackageInstaller and reported in ACK error/meta.

## Build & deploy
From `android-testdpc/`:
```powershell
.\.bazelisk\bazel.exe build //:testdpc
.\.bazelisk\bazel.exe mobile-install //:testdpc --start_app
.\.bazelisk\bazel.exe build //:testdpc --define variant=lite   # lite/field build
```
If Bazel cache is locked/corrupt: `bazel shutdown` then rebuild. Avoid `clean --expunge` unless needed.

### Lite mode (field devices)
- Flag `config_lite_mode` controls behaviour (default false). Bazel select picks `src/lite/res/**` when `--define variant=lite`, otherwise `src/dev/res/**` keeps full app.
- Lite build lansira `LiteEntryActivity` direktno: prikazuje Device ID (iz enrol state), spremljeni enrol token, te tipke **Enrol** (poziva `EnrolApiClient.enrolWithSavedToken`) i **Refresh** (ponovno učitava ID/token). Ostali Policy Management UI je skriven.
- Dev build (`bazel build //:testdpc`) ostaje nepromijenjen.

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

### DO command transport (`/api/mdm/inbox`, `/api/mdm/ack`)
- Poll: POST `/api/mdm/inbox` with `X-Device-Token` and JSON body; include `{ "is_device_owner": true }` (optional) so backend can set DO flags. Response: `{ "results": [ { command objects... } ], "count": N }`.
- Command shape: `id` (for ACK), `type`/`command_type`, `payload`, `audit` (traceability: `source`, `queued_at`, `requested_by`). Audit is for logs/UI, not required for execution.
- Install payload: `package`, `install_type`, `release_id`, `release_file_id`, and `files` array (`url`, `kind` base/config/obb/apk, `sha256`, `version_code`, `version_name`). No legacy `file_type` field. Steps: download each file (SHA verify, 200MB guardrail), then:
  - If `files` length > 1: open a `PackageInstaller.Session` and write each part (base + config splits) before commit.
  - If `files` length == 1: single APK install path.
  - Log `package`, `release_file_id`, version, timing, and result; keep `release_file_id` unchanged in ACK/meta for backend correlation.
- Ack: POST `/api/mdm/ack` with `[{ "id": <command_id>, "success": true/false, "error": "...", "meta": {...} }]` and `X-Device-Token`. On failure include a human-readable `error` so operators see why it failed.
- Poll cadence: use `poll_interval_sec` from enrolment (currently ~30s). Poll roughly every interval or immediately after finishing a command. Authentication is only the device token (no OAuth/Basic).
- Optional metadata endpoints: `/api/mdm/policy` (GET) for policy fetch; `/api/mdm/post_inventory` (POST) and `/api/mdm/user-restrictions` (GET/POST) are inventory/reporting helpers but not required for install flow.
- Install payload example:
```json
{
  "id": 298,
  "type": "install_apk_package",
  "payload": {
    "package": "com.tailscale.ipn",
    "install_type": "install",
    "files": [
      {
        "url": "https://qubitmdm.online/repo/apk/com.tailscale.ipn/510/Tailscale_VPN_1.90.8-tedc9d2245-gcf2f8cfec.apk",
        "kind": "apk",
        "sha256": "…",
        "version_code": 510,
        "version_name": "1.90.8…"
      }
    ],
    "release_id": 6,
    "release_file_id": 27,
    "version_code": 510,
    "version_name": "1.90.8…"
  }
}
```
- Example success:
```bash
curl -sk https://user-admin.tailnet.qubitsecured.online/api/mdm/inbox \
  -H "Content-Type: application/json" \
  -H "X-Device-Token: $DEVICE_TOKEN" \
  -d '{"is_device_owner": true}'

curl -sk https://user-admin.tailnet.qubitsecured.online/api/mdm/ack \
  -H "Content-Type: application/json" \
  -H "X-Device-Token: $DEVICE_TOKEN" \
  -d '[{ "id": 298, "success": true }]'
```
- Summary flow (agent):
  1. Device enrols and receives `device_id` + `device_token`.
  2. Poll `/api/mdm/inbox` with token headers (optionally `is_device_owner`).
  3. For each `install_apk_package`: iterate `payload.files`, download, verify SHA, install according to `kind` (base/config/obb), respect `install_type`, and use `release_file_id` for logging/correlation.
  4. After processing, POST `/api/mdm/ack` with `{ id, success, error?, meta? }` (one object per command).
  5. Repeat polling; server drops commands once acked.

## MQTT Core credentials handoff
- Provider authority: `com.afwsamples.testdpc.mqttcredentials`, URI `content://com.afwsamples.testdpc.mqttcredentials/credentials`.
- Columns: `device_id`, `mqtt_password`.
- Access: exported, gated to caller package `com.qubit.mqttcore` or self.
