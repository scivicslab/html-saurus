package com.scivicslab.htmlsaurus;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * The proxies whose {@code X-Forwarded-For} is believed: a list of addresses and CIDR ranges, written as literals (no
 * name is looked up). Empty by default, so no header is trusted: a client that sends {@code X-Forwarded-For} itself
 * cannot choose the address the log shows.
 */
final class TrustedProxies {

    static final TrustedProxies NONE = new TrustedProxies(List.of());

    private record Range(byte[] network, int prefix) {
        boolean contains(byte[] address) {
            if (address.length != network.length) return false;
            int full = prefix / 8;
            for (int i = 0; i < full; i++) if (address[i] != network[i]) return false;
            int rest = prefix % 8;
            if (rest == 0) return true;
            int mask = (0xff << (8 - rest)) & 0xff;
            return (address[full] & mask) == (network[full] & mask);
        }
    }

    private final List<Range> ranges;

    private TrustedProxies(List<Range> ranges) {
        this.ranges = ranges;
    }

    /**
     * @param spec comma separated addresses or CIDR ranges ({@code 10.0.0.0/8,192.168.1.5,fd00::/8}); {@code null} or
     *             blank trusts none
     * @throws IllegalArgumentException for an entry that is not an IP literal or a range of one
     */
    static TrustedProxies parse(String spec) {
        if (spec == null || spec.isBlank()) return NONE;
        List<Range> out = new ArrayList<>();
        for (String raw : spec.split(",")) {
            String entry = raw.strip();
            if (entry.isEmpty()) continue;
            int slash = entry.indexOf('/');
            byte[] bytes = literal(slash < 0 ? entry : entry.substring(0, slash));
            if (bytes == null) throw new IllegalArgumentException("not an IP address or range: '" + entry + "'");
            int prefix = bytes.length * 8;
            if (slash >= 0) {
                try {
                    prefix = Integer.parseInt(entry.substring(slash + 1));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("not an IP address or range: '" + entry + "'");
                }
                if (prefix < 0 || prefix > bytes.length * 8) throw new IllegalArgumentException("prefix out of range: '" + entry + "'");
            }
            out.add(new Range(bytes, prefix));
        }
        return out.isEmpty() ? NONE : new TrustedProxies(List.copyOf(out));
    }

    boolean isEmpty() {
        return ranges.isEmpty();
    }

    boolean trusts(InetAddress address) {
        byte[] a = normalized(address.getAddress());
        for (Range r : ranges) if (r.contains(a)) return true;
        return false;
    }

    /** @return the address bytes of an IP literal, or {@code null} when {@code text} is not one (nothing is resolved) */
    static byte[] literal(String text) {
        String t = text.strip();
        if (t.startsWith("[") && t.endsWith("]")) t = t.substring(1, t.length() - 1);
        boolean v4 = t.matches("\\d{1,3}(\\.\\d{1,3}){3}");
        boolean v6 = t.contains(":") && t.matches("[0-9A-Fa-f:.]+");
        if (!v4 && !v6) return null;
        try {
            return normalized(InetAddress.getByName(t).getAddress());
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /** An IPv4-mapped IPv6 address is the IPv4 address. */
    private static byte[] normalized(byte[] a) {
        if (a.length == 16) {
            boolean mapped = true;
            for (int i = 0; i < 10; i++) if (a[i] != 0) mapped = false;
            if (mapped && a[10] == (byte) 0xff && a[11] == (byte) 0xff) return new byte[] {a[12], a[13], a[14], a[15]};
        }
        return a;
    }
}
