# Lite Tailscale Config Flow

Quick reference for the **Set Tailscale config** button in the Lite launcher (`src/main/java/mdm/qubit/dpc/lite/LiteEntryActivity.java`).

## Flow
- Preconditions: device owner app, `com.tailscale.ipn` installed; otherwise shows a toast and stops.
- Clear data: `clearApplicationUserData` for `com.tailscale.ipn`; aborts on failure.
- Clean old file: best-effort delete `tailscale_config.json`.
- Refresh extras: `refreshProvisioningExtras` (non-recursive) POSTs `/api/provisioning/extras` and updates `EnrolConfig` (`LoginURL`, `ControlURL`, `AuthKey`, `Hostname`, `ExitNodeID`, `ForceEnabled`, optional `tailscale_managed_config`). Failure stops the flow.
- Apply config: sets application restrictions for `com.tailscale.ipn` and writes `tailscale_config.json` with values from extras/config (including `ForceEnabled`/`ExitNodeID` if present).
- Launch: starts the Tailscale launch intent (NEW_TASK); on failure shows `Failed to launch Tailscale`.
- After VPN up: polling detects VPN transport, then clears `AuthKey` from application restrictions and from `tailscale_config.json` (other fields remain). A guard prevents double-clearing.

## UI hook
- Button: `@+id/tailscale_config_button` in `src/main/res/layout/activity_lite_entry.xml`, listener bound in `LiteEntryActivity.onCreate`.
