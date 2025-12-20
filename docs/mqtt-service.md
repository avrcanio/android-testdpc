# Lite MQTT Service — Architecture & Usage

## Overview
LiteMqttService is a thin Android Service that:
- starts a Foreground Service (notification, DATA_SYNC type)
- waits for VPN on boot (ACTION_BOOT_START) before starting MQTT
- delegates all MQTT work to `LiteMqttController`

Controller handles:
- connect/reconnect with backoff + jitter (500ms → 15s)
- watchdog (20s) and forced reconnect (60s)
- heartbeat publish every 120s (`mdm/<deviceId>/state`)
- subscribe to `mdm/<deviceId>/notify` and trigger inbox sync
- wake lock handling via `WakeLockGuard`
- status + file logging via `StatusReporter`

## Components
- `LiteMqttService` – FGS, intent handling (START/STOP/BOOT_START), VPN wait, delegation.
- `LiteMqttController` – owns state (`connecting`, `reconnectScheduled`, `client`, backoff), executor and all tasks (reconnect, heartbeat, watchdogs).
- `WakeLockGuard` – acquire/release with logging, no refcount.
- `StatusReporter` – file logging (FileLogger) + status broadcast, exposes `getLastStatus()/getLastError()`.

## Lifecycle & statuses
- START → ensureForeground → `controller.start()`
- STOP → `controller.stop(true)` → disconnect, reset flags, cancel tasks
- BOOT_START → VPN wait (60s timeout, retry 5s) → START when VPN detected

Emitted statuses (broadcast): `connecting`, `connected`, `disconnected`, `error`, `reconnecting`, `forced_reconnect`, `heartbeat`, `subscribed`, `sync`, `vpn_detected`, `vpn_wait`, `vpn_timeout`, `stopped`.

## Connection config
- MQTT 5 async client (HiveMQ)
- WSS path from `LiteMqttConfig` (`host`, `port`, `path`, `clientId`)
- TLS if `config.isTlsEnabled()` (default trust store)
- KeepAlive = 60s, Session expiry = 24h
- Heartbeat interval = 120s (payload `{"status":"ok","ts":<epoch>}`)
- Backoff: base 500ms with jitter (0.5–1.0), capped at 15s
- Forced reconnect: if not CONNECTED > 60s, force reconnect (covers “stuck CONNECTING”)

## Logging & last status
- File logs: `StatusReporter` calls `FileLogger.log(...)` prefixed with `LiteMqttService: ...`
- Last state: `LiteMqttService.getLastStatus()/getLastError()`

ADB examples (package: `mdm.qubit.dpc`):
```
adb logcat -s LiteMqttService LiteMqttController
adb shell run-as mdm.qubit.dpc ls files
adb shell run-as mdm.qubit.dpc tail -f files/provision_log.txt
```

## Behavioral notes
- Reconnect guard: `reconnectScheduled` + `connecting` prevent duplicate timers.
- Watchdog (20s): if not CONNECTED/CONNECTING and `connecting=false`, schedules reconnect.
- Forced watchdog (60s): if not CONNECTED within 60s, forces reconnect (even if CONNECTING hung).
- Heartbeat: if client not CONNECTED or `connecting=true`, skip + scheduleReconnect.

## Change guidelines
- Add/remove status events only via `StatusReporter`.
- Touch MQTT tasks/flags only inside `LiteMqttController` (single executor, single source of truth).
- If adjusting intervals (heartbeat, forced reconnect, backoff), align with backend expectations and NAT timeouts.
