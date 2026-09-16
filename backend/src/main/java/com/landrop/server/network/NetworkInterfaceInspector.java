package com.landrop.server.network;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.StringJoiner;

/**
 * Dynamic network interfaces inspector for LAN discovery and Server Control Center (FOUNDATION-03A.4).
 */
public final class NetworkInterfaceInspector {

    private NetworkInterfaceInspector() {}

    public record InterfaceAddressInfo(
        String ip,
        String type,
        int prefixLength,
        boolean isLoopback,
        boolean isLinkLocal,
        boolean isSiteLocal,
        boolean isPrimaryBind
    ) {}

    public record InterfaceDetail(
        String name,
        String displayName,
        String macAddress,
        boolean isUp,
        boolean isLoopback,
        boolean isVirtual,
        boolean supportsMulticast,
        List<InterfaceAddressInfo> addresses
    ) {}

    public static List<InterfaceDetail> inspectAll(String bindHost) {
        List<InterfaceDetail> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces == null) return result;

            while (ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                result.add(toDetail(iface, bindHost));
            }
        } catch (SocketException ignored) {}
        return result;
    }

    public static List<InterfaceDetail> inspectLanInterfaces(String bindHost) {
        List<InterfaceDetail> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces == null) return result;

            while (ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                try {
                    if (!iface.isUp() || iface.isLoopback() || iface.isVirtual()) {
                        continue;
                    }
                } catch (SocketException e) {
                    continue;
                }

                InterfaceDetail detail = toDetail(iface, bindHost);
                boolean hasLanIp = detail.addresses().stream().anyMatch(a -> !a.isLoopback() && !a.isLinkLocal());
                if (hasLanIp) {
                    result.add(detail);
                }
            }
        } catch (SocketException ignored) {}
        return result;
    }

    private static InterfaceDetail toDetail(NetworkInterface iface, String bindHost) {
        String mac = "";
        try {
            byte[] hardwareAddress = iface.getHardwareAddress();
            if (hardwareAddress != null && hardwareAddress.length > 0) {
                StringJoiner sj = new StringJoiner(":");
                for (byte b : hardwareAddress) {
                    sj.add(String.format("%02X", b));
                }
                mac = sj.toString();
            }
        } catch (SocketException ignored) {}

        boolean isUp = false;
        boolean isLoopback = false;
        boolean isVirtual = false;
        boolean supportsMulticast = false;

        try {
            isUp = iface.isUp();
            isLoopback = iface.isLoopback();
            isVirtual = iface.isVirtual();
            supportsMulticast = iface.supportsMulticast();
        } catch (SocketException ignored) {}

        List<InterfaceAddressInfo> addrs = new ArrayList<>();
        for (InterfaceAddress ia : iface.getInterfaceAddresses()) {
            InetAddress addr = ia.getAddress();
            if (addr == null) continue;

            String type = (addr instanceof Inet4Address) ? "IPv4" : "IPv6";
            String ip = addr.getHostAddress();
            int scopeIdx = ip.indexOf('%');
            if (scopeIdx != -1) {
                ip = ip.substring(0, scopeIdx);
            }

            boolean isLinkLocal = addr.isLinkLocalAddress();
            boolean isSiteLocal = addr.isSiteLocalAddress();
            boolean isAddrLoopback = addr.isLoopbackAddress();
            boolean isPrimary = (bindHost != null && (bindHost.equals(ip) || ("0.0.0.0".equals(bindHost) && isSiteLocal && "IPv4".equals(type))));

            addrs.add(new InterfaceAddressInfo(
                ip,
                type,
                ia.getNetworkPrefixLength(),
                isAddrLoopback,
                isLinkLocal,
                isSiteLocal,
                isPrimary
            ));
        }

        return new InterfaceDetail(
            iface.getName(),
            iface.getDisplayName(),
            mac,
            isUp,
            isLoopback,
            isVirtual,
            supportsMulticast,
            addrs
        );
    }
}
