cd C:\Users\avrca\Projekti\testDPC.qubit.mdm\android-testdpc
$env:BAZEL_SH="C:\Program Files\Git\bin\bash.exe"
.\.bazelisk\bazel.exe shutdown
.\.bazelisk\bazel.exe build //:testdpc --verbose_failures
.\.bazelisk\bazel.exe build //:testdpc --define variant=lite

Release (lite) potpisivanje / verzija:
- Manifest verzija trenutačno: versionCode=9039, versionName=9.0.39 (up-to-date u src/main/AndroidManifest.xml).
- Postavi lozinke: $env:UPLOAD_KEYSTORE_PASS=G9p#sL8vQ2n!Ya5dWmR3 ; $env:UPLOAD_KEY_PASS=G9p#sL8vQ2n!Ya5dWmR3
- Build + potpis: .\buildlite.bat (koristi upload.keystore, radi zipalign + apksigner); finalni artefakt je bazel-bin\testdpc-lite-release.apk (.idsig prisutan).
- Provjera verzije na APK-u: C:\Users\avrca\AppData\Local\Android\Sdk\build-tools\35.0.0\aapt.exe dump badging bazel-bin\testdpc-lite-release.apk ^| Select-String version

Klijent: HiveMQ MQTT 5 async preko WSS, serverHost/Port dolaze iz forme, default host emqx.tailnet.qubitsecured.online, port 443, path /mqtt, TLS uključen (custom tailnet_ca ako postoji, inače system trust). cleanStart(false), sessionExpiryInterval 24h, keepAlive 60 s, heartbeat publish svake 120 s, manualni reconnect/backoff u servisu.
Transport: WebSocket (webSocketConfig().serverPath(mConfig.path)), radi na WSS 443 kroz NGINX proxy, nema auto-migracije na 8084.
UI: MQTT forma (host/port/path/user/pass/qid/clientId/TLS) + start/stop + status/meta nalazi se na dnu activity_main.xml; PolicyManagementActivity ju inicijalizira, čita/spremá LiteMqttConfig, starta/stopa LiteMqttService, sluša status broadcast.

Default host/port/path: LiteMqttConfig.DEFAULT_HOST = "emqx.tailnet.qubitsecured.online", DEFAULT_PORT = 443, DEFAULT_PATH = "/mqtt" (src/main/java/com/afwsamples/testdpc/lite/LiteMqttConfig.java). TLS je zadano uključen.


