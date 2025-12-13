Klik na **Set Tailscale config** u Lite launcheru pokreće novi flow u `applyTailscaleConfigAndLaunch()` (`src/main/java/com/afwsamples/testdpc/lite/LiteEntryActivity.java`):

- Validacija: mora biti DO i `com.tailscale.ipn` mora biti instaliran; inače toast i prekid.
- Force stop/clear data: `clearApplicationUserData` za `com.tailscale.ipn`; na fail flow staje.
- Brisanje starog configa: best-effort `deleteFile("tailscale_config.json")`.
- Refresh konfiguracije: `refreshProvisioningExtras` (bez auto-rekurzije) dohvaća `/api/provisioning/extras` i osvježava EnrolConfig (`LoginURL`, `ControlURL`, `AuthKey`, `Hostname`); na fail flow staje.
- Primjena nove konfiguracije: postavlja application restrictions za `com.tailscale.ipn` i zapisuje novi `tailscale_config.json` s istim vrijednostima + `ForceEnabled`, `PostureChecking`, `AllowIncomingConnections`, `UseTailscaleDNSSettings`.
- Pokretanje: starta Tailscale launch intent (NEW_TASK); ako nedostaje intent ili baci iznimku, toast `Failed to launch Tailscale`.
- Nakon VPN UP: polling detektira VPN transport i tada briše `AuthKey` iz application restrictions i novog `tailscale_config.json` (ostala polja ostaju), uz kratki toast/log.

Gumb je u `src/main/res/layout/activity_lite_entry.xml` (`@+id/tailscale_config_button`), listener se veže u `onCreate`.
