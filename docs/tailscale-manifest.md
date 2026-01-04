# Tailscale Managed Config Keys (reference)

Example payload for `/api/provisioning/extras`:

```json
{
  "LoginURL": "https://hs-control.example.com",
  "AuthKey": "hskey-auth-xxx",
  "Hostname": "android-device-01",
  "ForceEnabled": true,
  "ExitNodeID": "174",
  "ExitNodeAllowLANAccess": true,
  "UseTailscaleSubnets": true,
  "ManagedByCaption": "Your Company",
  "ManagedByOrganizationName": "Your Org",
  "ManagedByURL": "https://yourcompany.com"
}
```

Notes:
- Any keys present in `tailscale_managed_config` are merged as-is; code then overwrites `AuthKey`, `Hostname`, `ControlURL`, `ForceEnabled`, `ExitNodeID` with the latest extras values.
- Removed defaults: we no longer inject `PostureChecking` or `UseTailscaleDNSSettings`; those must come from the server if needed.
