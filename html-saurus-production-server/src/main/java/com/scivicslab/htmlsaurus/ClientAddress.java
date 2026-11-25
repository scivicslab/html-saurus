package com.scivicslab.htmlsaurus;

import java.net.InetAddress;

/**
 * The address the log records as {@code %h}: the TCP peer, unless the peer is a configured proxy and the request names a
 * client in {@code X-Forwarded-For}.
 */
final class ClientAddress {

    private ClientAddress() {}

    /**
     * Reads {@code X-Forwarded-For} from the right, the end each proxy appends to: a hop that is itself a trusted proxy is
     * passed over, and the first one that is not is the client. A header that is not made of IP literals is not believed
     * (the peer's address is used), and so is any header from a peer that is not trusted.
     *
     * @param peer          the address of the TCP connection
     * @param forwardedFor  the value of the {@code X-Forwarded-For} header, or {@code null}
     * @param trusted       the proxies whose header is believed
     * @return the address text for {@code %h}
     */
    static String of(InetAddress peer, String forwardedFor, TrustedProxies trusted) {
        String peerText = peer.getHostAddress();
        if (forwardedFor == null || forwardedFor.isBlank() || trusted.isEmpty() || !trusted.trusts(peer)) return peerText;
        String[] hops = forwardedFor.split(",");
        String client = null;
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].strip();
            byte[] bytes = TrustedProxies.literal(hop);
            if (bytes == null) return peerText;
            try {
                InetAddress a = InetAddress.getByAddress(bytes);
                client = a.getHostAddress();
                if (!trusted.trusts(a)) return client;
            } catch (java.net.UnknownHostException e) {
                return peerText;
            }
        }
        return client == null ? peerText : client;
    }
}
