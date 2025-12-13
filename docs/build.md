cd C:\Users\avrca\Projekti\testDPC.qubit.mdm\android-testdpc
$env:BAZEL_SH="C:\Program Files\Git\bin\bash.exe"
.\.bazelisk\bazel.exe shutdown
.\.bazelisk\bazel.exe build //:testdpc --verbose_failures
.\.bazelisk\bazel.exe build //:testdpc --define variant=lite


Klijent: HiveMQ MQTT 5 async preko WSS, serverHost/Port dolaze iz forme, default host emqx.tailnet.qubitsecured.online, port 443, path /mqtt, TLS uključen (custom tailnet_ca ako postoji, inače system trust). cleanStart(false), sessionExpiryInterval 24h, keepAlive 60 s, heartbeat publish svake 120 s, manualni reconnect/backoff u servisu.
Transport: WebSocket (webSocketConfig().serverPath(mConfig.path)), radi na WSS 443 kroz NGINX proxy, nema auto-migracije na 8084.
UI: MQTT forma (host/port/path/user/pass/qid/clientId/TLS) + start/stop + status/meta nalazi se na dnu activity_main.xml; PolicyManagementActivity ju inicijalizira, čita/spremà LiteMqttConfig, starta/stopa LiteMqttService, sluša status broadcast.

Default host/port/path: LiteMqttConfig.DEFAULT_HOST = "emqx.tailnet.qubitsecured.online", DEFAULT_PORT = 443, DEFAULT_PATH = "/mqtt" (src/main/java/com/afwsamples/testdpc/lite/LiteMqttConfig.java). TLS je zadano uključeno.