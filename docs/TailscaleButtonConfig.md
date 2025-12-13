Kada u Lite launcheru klikneš na Set Tailscale config, okida se applyTailscaleConfigAndLaunch() iz src/main/java/com/afwsamples/testdpc/lite/LiteEntryActivity.java (line 319):

    applyTailscaleConfigAndLaunch() je u src/main/java/com/afwsamples/testdpc/lite/LiteEntryActivity.java (oko linije 288) i radi ovaj slijed:

        - Učita Tailscale vrijednosti iz EnrolConfig: LoginURL, ControlURL (ako postoji), AuthKey, Hostname. Ako išta nedostaje, pokaže toast Missing Tailscale config values i prekida.
        - Provjerava je li Tailscale app (com.tailscale.ipn) instaliran; ako nije, toast Tailscale not installed i prekid.
        - Ako je sve spremno, preko DevicePolicyManager postavlja application restrictions za Tailscale paket (LoginURL, ControlURL ili fallback na LoginURL, AuthKey, Hostname).
        - Snimi iste vrijednosti u lokalni tailscale_config.json (internal storage) zajedno s flagovima ForceEnabled, PostureChecking, AllowIncomingConnections, UseTailscaleDNSSettings. Na uspjeh pokaže toast Tailscale config applied; na iznimku pokazuje poruku greške.
        Po- kušava dobiti launch intent za Tailscale i pokrenuti ga s FLAG_ACTIVITY_NEW_TASK; ako ne uspije, toast Failed to launch Tailscale.


Gumb je definiran u src/main/res/layout/activity_lite_entry.xml (@+id/tailscale_config_button) i listener je spojen u onCreate na istoj aktivnosti (LiteEntryActivity.java (lines 136-144)).