# MDM Command Handling (Qbit DPC)

Current implementation (as of this commit) does not have a dedicated `MdmCommandHandler` class. Command handling is performed inside `MdmSyncManager.processCommand(...)`.

Supported commands:
- `wipe` ? calls `DevicePolicyManager.wipeData(0)` and returns ACK `{ "id": <id>, "success": true }`.
- `install_apk_package` ? downloads from `payload.url` and installs via `PackageInstallationUtils.installPackage(...)`; ACK `{ "id": <id>, "success": true }` on success, otherwise `success: false` with an error string.
- `uninstall_app` ? uses `PackageInstallationUtils.uninstallPackage(...)` for `payload.package_name`; ACK `{ "id": <id>, "success": true }` on success, otherwise `success: false` with an error string.
- `suspend_app` ? uses `DevicePolicyManager.setPackagesSuspended(...)` for `payload.packages` to suspend/unsuspend; ACK `{ "id": <id>, "success": true }` on success, otherwise `success: false` with an error string.
- `hide_app` ? uses `DevicePolicyManager.setApplicationHidden(...)` for `payload.packages` to hide/unhide apps; ACK `{ "id": <id>, "success": true }` on success, otherwise `success: false` with an error string.

All other command types are currently marked unsupported and ACK-ed with `success: false` and `error: "unsupported_type"`.

Files:
- Handler logic: `src/main/java/com/afwsamples/testdpc/mdm/MdmSyncManager.java` (`processCommand`).

Note: Extend `processCommand` when new command types are introduced (e.g., install APK, set policy, rotate token).
