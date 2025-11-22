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
