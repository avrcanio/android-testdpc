package mdm.qubit.dpc.lite;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Log;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.Collections;

/**
 * Best-effort Tailscale health checker and logger.
 *
 * <p>We cannot talk to Tailscale's internal APIs directly, so we infer readiness from:
 * - VPN transport presence
 * - tailscaleX interface existence
 * - DNS resolution of the MQTT host
 * - TCP connect reachability to the MQTT broker
 */
final class TailscaleHealthMonitor {
  private static final String TAG = "TailscaleHealth";
  private static final String TAILSCALE_PACKAGE = "com.tailscale.ipn";
  private static final String TAILSCALE_IF_PREFIX = "tailscale";
  private static final int REACHABILITY_TIMEOUT_MS = 2000;

  private final LiteMqttService service;
  private volatile String targetHost = LiteMqttConfig.DEFAULT_HOST;
  private volatile int targetPort = LiteMqttConfig.DEFAULT_PORT;

  static final class Health {
    final boolean vpnUp;
    final boolean hasTailInterface;
    final boolean dnsResolved;
    final boolean tcpReachable;
    final String resolvedIp;

    Health(
        boolean vpnUp,
        boolean hasTailInterface,
        boolean dnsResolved,
        boolean tcpReachable,
        String resolvedIp) {
      this.vpnUp = vpnUp;
      this.hasTailInterface = hasTailInterface;
      this.dnsResolved = dnsResolved;
      this.tcpReachable = tcpReachable;
      this.resolvedIp = resolvedIp;
    }

    boolean isReadyForMqtt() {
      return (vpnUp || hasTailInterface) && (dnsResolved || tcpReachable);
    }

    String summary() {
      return "vpnUp="
          + vpnUp
          + " tailIf="
          + hasTailInterface
          + " dns="
          + dnsResolved
          + (resolvedIp != null ? " ip=" + resolvedIp : "")
          + " tcp="
          + tcpReachable;
    }
  }

  TailscaleHealthMonitor(LiteMqttService service) {
    this.service = service;
  }

  void updateTarget(String host, int port) {
    if (host != null && !host.isEmpty()) {
      this.targetHost = host;
    }
    if (port > 0) {
      this.targetPort = port;
    }
  }

  Health checkAndLog(String reason) {
    Health health = checkInternal();
    service.logToFile(
        "Tailscale health [" + reason + "]: " + health.summary() + " target=" + targetHost + ":" + targetPort);
    return health;
  }

  void nudgeTailscaleIfPossible() {
    if (!isTailscaleInstalled()) {
      service.logToFile("Tailscale nudge skipped: app not installed");
      return;
    }
    try {
      Intent svcIntent = new Intent();
      svcIntent.setClassName(TAILSCALE_PACKAGE, "com.tailscale.ipn.IPNService");
      service.startService(svcIntent);
      service.logToFile("Tailscale nudge: startService(IPNService) sent");
    } catch (Exception e) {
      service.logToFile("Tailscale nudge failed to start service: " + e.getMessage());
      Log.w(TAG, "Failed to start Tailscale service", e);
    }
  }

  private boolean isTailscaleInstalled() {
    PackageManager pm = service.getPackageManager();
    try {
      pm.getPackageInfo(TAILSCALE_PACKAGE, 0);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private Health checkInternal() {
    boolean vpnUp = isVpnTransportUp();
    boolean hasTailInterface = hasTailscaleInterface();
    String resolvedIp = null;
    boolean dnsResolved = false;
    try {
      InetAddress address = InetAddress.getByName(targetHost);
      resolvedIp = address.getHostAddress();
      dnsResolved = true;
    } catch (Exception e) {
      dnsResolved = false;
    }
    boolean tcpReachable = canReachBroker();
    return new Health(vpnUp, hasTailInterface, dnsResolved, tcpReachable, resolvedIp);
  }

  private boolean canReachBroker() {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(targetHost, targetPort), REACHABILITY_TIMEOUT_MS);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private boolean isVpnTransportUp() {
    ConnectivityManager cm = service.getSystemService(ConnectivityManager.class);
    if (cm == null) {
      return false;
    }
    Network active = cm.getActiveNetwork();
    if (active == null) {
      return false;
    }
    NetworkCapabilities caps = cm.getNetworkCapabilities(active);
    return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
  }

  private boolean hasTailscaleInterface() {
    try {
      for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
        if (nif == null) {
          continue;
        }
        String name = nif.getName();
        if (name != null && name.startsWith(TAILSCALE_IF_PREFIX) && nif.isUp()) {
          return true;
        }
      }
    } catch (Exception e) {
      Log.w(TAG, "Checking tailscale interface failed", e);
    }
    return false;
  }
}
