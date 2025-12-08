# Password policy & password change flow

## set_password_policy (MDM command)
- `command_type`: `set_password_policy`
- Payload example:
  ```json
  {
    "request_id": "pwdpolicy-6f94b0e3",
    "password_policy": {
      "quality": "complex",
      "expiration_seconds": 0,
      "history_length": 5,
      "min_length": 8,
      "min_letters": 1,
      "min_digits": 1,
      "min_symbols": 1,
      "min_lowercase": 0,
      "min_uppercase": 0,
      "min_nonletter": 0
    }
  }
  ```
- Mapping on device (DO):
  - `quality` → `DevicePolicyManager.setPasswordQuality` (`unspecified|something|numeric|numeric_complex|alphabetic|alphanumeric|complex`).
  - `expiration_seconds` → `setPasswordExpirationTimeout` (seconds → ms).
  - `history_length` → `setPasswordHistoryLength`.
  - `min_*` → corresponding `setPasswordMinimum*` calls.
- ACK meta includes applied values and snapshot of current DPM state.

## set_password_complexity (MDM command)
- `command_type`: `set_password_complexity`
- Payload keys: `password_complexity` or `required_password_complexity` (`none|low|medium|high`).
- Applies `setRequiredPasswordComplexity` (API 30+) and returns meta/snapshot.

## request_password_change (MDM command)
- `command_type`: `request_password_change`
- Payload example:
  ```json
  {
    "request_id": "pwdreset-6f94b0e3",
    "message": "Postavi novu lozinku"
  }
  ```
- Device behavior (DO):
  - Opens `ACTION_SET_NEW_PASSWORD` (shows message if provided).
  - Expires current password immediately (`setPasswordExpirationTimeout(admin, 1000)`) and locks device (`lockNow()`).
  - Stores `request_id` for later telemetry.
- ACK meta: `prompt_shown`, `request_id`, `timestamp`, `lock_invoked`, `expiration_timeout_ms`, and optional `prompt_error` / `lock_error`.

## Telemetry: password change
- When the user actually changes the password (`DeviceAdminReceiver.onPasswordChanged`), DO posts to `/api/mdm/password-change/state`:
  ```json
  {
    "request_id": "<stored_from_command>",
    "timestamp": <epoch_sec>,
    "password_changed": true,
    "status": "changed"
  }
  ```
- On success the stored `request_id` is cleared. Backend uses this to mark the request completed.
