# Qbit DPC Guide

Short, Qbit-specific notes for working with this TestDPC fork. General TestDPC docs stay in the original README; this file tracks our deltas and workflow.

## Overview
- Device owner / profile owner capable; target package `com.afwsamples.testdpc`.
- Custom addition: one-tap Tailscale install from Policy Management (device owner only) via `https://qubitmdm.online/repo/mdm/tailscale.apk`.
- Build system: Bazel (via bazelisk).

## Prerequisites
- Android device/emulator API 23+ (Tailscale installer uses DO-only action).
- Device provisioned as Device Owner for full policy surface (provisioning QR / afw# / NFC as usual).
- Network access to `qubitmdm.online` for Tailscale download.

## Provisioning / Post-provision
1. Provision as Device Owner (standard TestDPC QR/afw# flow).
2. Open app → Policy Management screen.
3. At top of list tap **Install Tailscale** to fetch and install the APK from the Qbit repo.

Notes:
- Download uses no-cache headers to avoid 304 responses; install streams directly to `PackageInstaller`.
- If Tailscale is already present, PackageInstaller will prompt for update/replace depending on signature.

## Policy Management highlights
- Standard TestDPC policy surface retained (user restrictions, lock task, keyguard, VPN, logging, app visibility, certificates, etc.).
- Qbit customization: **Install Tailscale** preference in `device_policy_header.xml` powered by `PolicyManagementFragment`.

## Build & deploy
From `android-testdpc/`:
```powershell
.\.bazelisk\bazel.exe build //:testdpc          # incremental build (preferred)
.\.bazelisk\bazel.exe mobile-install //:testdpc --start_app  # deploy to device/emulator
```
If Bazel cache is corrupted/locked, try `bazel shutdown` then rebuild. Avoid `clean --expunge` unless necessary.

## Troubleshooting
- **Download shows toast then nothing**: check logcat; ensure network to `qubitmdm.online`. 304 cached responses are bypassed; any non-200 will show a failure toast.
- **OOM during download**: fixed by streaming install (no in-memory buffer). Update to current code before retrying.
- **Bazel “Permission denied” on `libtestdpc_lib.jar`**: close processes locking `_bazel_avrca` cache; run `bazel shutdown`; retry build. Disable AV scan on cache path if repeatable.
- **304 from server**: server says “Not Modified” if cached. Client forces `Accept-Encoding: identity` and no-cache headers, but server-side can also disable ETag or serve versioned APK URLs.

## Enrolment logging & POST to Qubit backend
- Wake action: receiver `MdmWakeReceiver` listens for `com.qubit.mdm.ACTION_MDM_WAKE` (exported) to trigger future sync (currently logs + Toast).
- Provisioning flow now logs extras and enrol token to `provision_log.txt` (internal storage) from:
  - `ProvisioningSuccessActivity`, `FinalizeActivity`, `DeviceAdminReceiver` (onReceive + onProfileProvisioningComplete)
  - `PolicyManagementActivity` logs the saved token on app start.
  - Helper: `FileLogger` (appends to `/data/user/0/com.afwsamples.testdpc/files/provision_log.txt`).
- Token is persisted in `SharedPreferences` via `EnrolConfig` (key `enrol_token`).
- “Enrol Qubit” action is exposed in Policy Management (preference under “Enrollment-specific ID”); click triggers `EnrolApiClient.enrolWithSavedToken(...)`.
- Enrol API:
  - URL: `https://user-admin.tailnet.qubitsecured.online/api/mdm/enrol`
  - Method: POST JSON `{ "enrol_token": "<saved>", "is_device_owner": bool, "os_version": "...", "sdk_int": N, "device_model": "...", "device_manufacturer": "..." }`
  - Response (201 fresh enrol): includes `device_id`, `device_token`, `rotate_required`, `policy`, `policy_etag`, `commands_pending`, `poll_interval_sec`.
  - `EnrolState` persists device_id/device_token/policy metadata; policy stored as raw JSON string.
  - UI: Policy Management shows `Qubit Device ID` (read-only summary from stored device_id).
  - One-time behaviour: first POST returns 201 (created); subsequent POSTs typically 409 (already enrolled).
  - Client logs with requestId, HTTP code, response body (if present), and TLS cert info; see logcat tag `EnrolApiClient` and `provision_log.txt`.
- Trust: custom CA bundled at `res/raw/qubit_tailnet_ca.crt` with `network_security_config.xml` referenced in `AndroidManifest.xml` so app trusts the tailnet CA (no custom TrustManager needed).

## MDM sync & commands
- Manual sync: toolbar sync icon in Policy Management triggers `MdmSyncManager.syncNow()`; MQTT wake broadcast does the same.
- API flow: GET /policy → save via `PolicyConfig`; POST /inbox → process commands; POST /ack with results.
- Supported commands in `MdmSyncManager.processCommand`:
  - `install_apk_package` (download + install via PackageInstaller)
  - `uninstall_app`
  - `suspend_app`
  - `hide_app`
  - `wipe`
- Logging: `MdmApiClient` and `MdmSyncManager` emit requestId-tagged logs to logcat and `provision_log.txt` (codes, body lengths, actions).
