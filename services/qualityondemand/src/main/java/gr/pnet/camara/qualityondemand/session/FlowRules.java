package gr.pnet.camara.qualityondemand.session;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import gr.pnet.camara.qualityondemand.session.Ports.Range;

/**
 * The flow of a session, between the device and the application server: when two flows overlap, and how one is
 * written as TS 29.214 IP filter rules for TEP.
 */
final class FlowRules {

    private static final Range ALL_PORTS = new Range(0, 65535);

    private FlowRules() {
    }

    // Two sessions of a device conflict when their flows can carry the same packets: the servers' networks overlap,
    // and so do the device ports and the server ports
    static boolean overlap(String server, Ports devicePorts, Ports serverPorts, QodSession other) {
        return networksOverlap(server, other.applicationServer())
                && portsOverlap(devicePorts, other.devicePorts())
                && portsOverlap(serverPorts, other.applicationServerPorts());
    }

    // CIDR blocks overlap only when one contains the other, which is when their ranges intersect
    static boolean networksOverlap(String a, String b) {
        long[] x = range(a);
        long[] y = range(b);
        return x[0] <= y[1] && y[0] <= x[1];
    }

    static boolean portsOverlap(Ports a, Ports b) {
        List<Range> x = ranges(a);
        List<Range> y = ranges(b);
        return x.stream().anyMatch(r -> y.stream().anyMatch(s -> r.from() <= s.to() && s.from() <= r.to()));
    }

    // The ranges a port list covers; none means all ports
    private static List<Range> ranges(Ports ports) {
        if (ports == null || ports.any()) {
            return List.of(ALL_PORTS);
        }
        List<Range> ranges = new ArrayList<>(ports.ranges());
        ports.ports().forEach(p -> ranges.add(new Range(p, p)));
        return ranges;
    }

    /**
     * The downlink and uplink rules, both "permit out" as TS 29.214 requires, with the direction in the addresses:
     * {@code permit out ip from <server>[ ports] to <device>[ ports]}, then device to server.
     */
    static List<String> flowDescriptions(String device, Ports devicePorts, String server, Ports serverPorts) {
        String deviceEnd = end(device + "/32", devicePorts);
        String serverEnd = end(network(server), serverPorts);
        return List.of("permit out ip from " + serverEnd + " to " + deviceEnd,
                "permit out ip from " + deviceEnd + " to " + serverEnd);
    }

    private static String end(String address, Ports ports) {
        if (ports == null || ports.any()) {
            return address;
        }
        // Single ports, then ranges: 5060,5070,5010-5020
        String list = Stream.concat(ports.ports().stream().map(String::valueOf),
                ports.ranges().stream().map(r -> r.from() == r.to() ? String.valueOf(r.from()) : r.from() + "-" + r.to()))
                .collect(Collectors.joining(","));
        return address + " " + list;
    }

    // a.b.c.d/n with the host bits cleared, as a filter rule expects; a single address is /32
    static String network(String cidr) {
        long[] r = range(cidr);
        return dotted(r[0]) + "/" + prefix(cidr);
    }

    // The first and last address of an IPv4 address or network, as unsigned 32-bit values
    private static long[] range(String cidr) {
        int prefix = prefix(cidr);
        long address = address(cidr.contains("/") ? cidr.substring(0, cidr.indexOf('/')) : cidr);
        long mask = prefix == 0 ? 0 : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
        long first = address & mask;
        return new long[] { first, first | (~mask & 0xFFFFFFFFL) };
    }

    private static int prefix(String cidr) {
        int prefix = cidr.contains("/") ? Integer.parseInt(cidr.substring(cidr.indexOf('/') + 1)) : 32;
        if (prefix < 0 || prefix > 32) {
            throw new IllegalArgumentException("Not an IPv4 prefix length: " + prefix);
        }
        return prefix;
    }

    private static long address(String dotted) {
        String[] octets = dotted.split("\\.");
        if (octets.length != 4) {
            throw new IllegalArgumentException("Not an IPv4 address: " + dotted);
        }
        long value = 0;
        for (String octet : octets) {
            int part = Integer.parseInt(octet);
            if (part < 0 || part > 255) {
                throw new IllegalArgumentException("Not an IPv4 address: " + dotted);
            }
            value = (value << 8) | part;
        }
        return value;
    }

    private static String dotted(long value) {
        return (value >> 24 & 0xFF) + "." + (value >> 16 & 0xFF) + "." + (value >> 8 & 0xFF) + "." + (value & 0xFF);
    }
}
