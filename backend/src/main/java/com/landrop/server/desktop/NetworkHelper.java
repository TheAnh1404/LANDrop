package com.landrop.server.desktop;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Utility for discovering LAN IPv4 network interfaces and host IP addresses.
 */
public final class NetworkHelper {

    public record LanInterfaceInfo(String ip, String displayName, String interfaceName) {
        @Override
        public String toString() {
            return String.format("%s (%s - %s)", ip, interfaceName, displayName);
        }
    }

    private NetworkHelper() {}

    /**
     * Retrieves all available non-loopback, active IPv4 LAN interfaces.
     */
    public static List<LanInterfaceInfo> getAvailableLanInterfaces() {
        List<LanInterfaceInfo> interfaces = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> nifs = NetworkInterface.getNetworkInterfaces();
            if (nifs == null) {
                return interfaces;
            }

            for (NetworkInterface nif : Collections.list(nifs)) {
                if (!nif.isUp() || nif.isLoopback() || nif.isPointToPoint()) {
                    continue;
                }

                Enumeration<InetAddress> addresses = nif.getInetAddresses();
                for (InetAddress addr : Collections.list(addresses)) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && !addr.isLinkLocalAddress()) {
                        String hostAddress = addr.getHostAddress();
                        interfaces.add(new LanInterfaceInfo(
                                hostAddress,
                                nif.getDisplayName(),
                                nif.getName()
                        ));
                    }
                }
            }
        } catch (SocketException e) {
            // Fallback gracefully
        }

        // Rank common physical interfaces first (Wi-Fi, Ethernet, wlan, eth, en)
        interfaces.sort((a, b) -> {
            int scoreA = rateInterface(a.interfaceName() + " " + a.displayName());
            int scoreB = rateInterface(b.interfaceName() + " " + b.displayName());
            return Integer.compare(scoreB, scoreA);
        });

        return interfaces;
    }

    private static int rateInterface(String name) {
        String lower = name.toLowerCase();
        if (lower.contains("wi-fi") || lower.contains("wlan") || lower.contains("wireless")) return 100;
        if (lower.contains("ethernet") || lower.contains("eth") || lower.contains("en")) return 90;
        if (lower.contains("local area")) return 80;
        if (lower.contains("virtual") || lower.contains("vEthernet") || lower.contains("wsl") || lower.contains("docker")) return 10;
        return 50;
    }

    /**
     * Finds the most likely primary LAN IP address.
     */
    public static String getPrimaryLanIp() {
        List<LanInterfaceInfo> available = getAvailableLanInterfaces();
        if (!available.isEmpty()) {
            return available.get(0).ip();
        }
        try {
            InetAddress localHost = InetAddress.getLocalHost();
            if (localHost instanceof Inet4Address && !localHost.isLoopbackAddress()) {
                return localHost.getHostAddress();
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }
}
