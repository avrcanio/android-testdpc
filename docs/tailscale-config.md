# Lite Tailscale Config Flow

Quick reference for the **Set Tailscale config** button in Lite launcher (`src/main/java/mdm/qubit/dpc/lite/LiteEntryActivity.java`).

## Flow
- Preconditions: device owner app, `com.tailscale.ipn` installed; otherwise shows toast and stops.
- Clear data: `clearApplicationUserData` for `com.tailscale.ipn`; aborts on failure.
- Clean old file: best-effort delete `tailscale_config.json`.
- Refresh extras: `refreshProvisioningExtras` (non-recursive) POSTs `/api/provisioning/extras` and updates `EnrolConfig` (`LoginURL`, `ControlURL`, `AuthKey`, `Hostname`). Failure stops the flow.
- Apply config: sets application restrictions for `com.tailscale.ipn` and writes `tailscale_config.json` with the same values plus `ForceEnabled`, `PostureChecking`, `AllowIncomingConnections`, and `UseTailscaleDNSSettings`.
- Launch: starts the Tailscale launch intent (NEW_TASK); on failure shows `Failed to launch Tailscale`.
- After VPN up: polling detects VPN transport, then clears `AuthKey` from application restrictions and from `tailscale_config.json` (other fields remain). From Dec 2025 a guard prevents duplicate clears and restrictions include `ForceEnabled=true` when clearing to help disable the toggle in the Tailscale UI.

## UI hook
- Button: `@+id/tailscale_config_button` in `src/main/res/layout/activity_lite_entry.xml`, listener bound in `LiteEntryActivity.onCreate`.
