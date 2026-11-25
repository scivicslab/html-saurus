package com.scivicslab.htmlsaurus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;

import org.junit.jupiter.api.Test;

/** The address of the log is the TCP peer unless the peer is a configured proxy. */
class ClientAddressTest {

    private static InetAddress ip(String s) throws Exception {
        return InetAddress.getByName(s);
    }

    @Test
    void withNoTrustedProxyNoHeaderIsBelieved() throws Exception {
        assertEquals("203.0.113.9", ClientAddress.of(ip("203.0.113.9"), "1.2.3.4", TrustedProxies.NONE));
    }

    @Test
    void anUntrustedPeerCannotForgeTheAddress() throws Exception {
        TrustedProxies proxies = TrustedProxies.parse("10.0.0.0/8");
        assertEquals("198.51.100.7", ClientAddress.of(ip("198.51.100.7"), "6.6.6.6", proxies));
        assertEquals("198.51.100.7", ClientAddress.of(ip("198.51.100.7"), "6.6.6.6, 10.0.0.1", proxies));
    }

    @Test
    void aTrustedProxyNamesTheClient() throws Exception {
        TrustedProxies proxies = TrustedProxies.parse("10.0.0.0/8");
        assertEquals("203.0.113.5", ClientAddress.of(ip("10.1.2.3"), "203.0.113.5", proxies));
    }

    @Test
    void theHopsOfTrustedProxiesAreSkippedFromTheRight_andALeftSideForgeryIsIgnored() throws Exception {
        TrustedProxies proxies = TrustedProxies.parse("10.0.0.0/8, 192.168.1.5");
        // The client sent "6.6.6.6"; the first proxy appended the client's real address; the second appended the first proxy.
        assertEquals("203.0.113.5", ClientAddress.of(ip("192.168.1.5"), "6.6.6.6, 203.0.113.5, 10.0.0.2", proxies));
    }

    @Test
    void aHeaderThatIsNotIpLiteralsFallsBackToThePeer() throws Exception {
        TrustedProxies proxies = TrustedProxies.parse("10.0.0.0/8");
        assertEquals("10.1.2.3", ClientAddress.of(ip("10.1.2.3"), "unknown", proxies));
        assertEquals("10.1.2.3", ClientAddress.of(ip("10.1.2.3"), "1.2.3.4, evil.example.com", proxies));
        assertEquals("10.1.2.3", ClientAddress.of(ip("10.1.2.3"), "", proxies));
        assertEquals("10.1.2.3", ClientAddress.of(ip("10.1.2.3"), null, proxies));
    }

    @Test
    void allHopsTrustedGivesTheLeftmost() throws Exception {
        assertEquals("10.9.9.9", ClientAddress.of(ip("10.1.2.3"), "10.9.9.9, 10.0.0.2", TrustedProxies.parse("10.0.0.0/8")));
    }

    @Test
    void ipv6AndMappedAddressesMatch() throws Exception {
        TrustedProxies proxies = TrustedProxies.parse("fd00::/8, 127.0.0.1");
        assertEquals("2001:db8:0:0:0:0:0:1", ClientAddress.of(ip("fd00::5"), "2001:db8::1", proxies));
        assertEquals("203.0.113.5", ClientAddress.of(ip("::ffff:127.0.0.1"), "203.0.113.5", proxies));
    }

    @Test
    void theRangesAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> TrustedProxies.parse("not-an-ip"));
        assertThrows(IllegalArgumentException.class, () -> TrustedProxies.parse("10.0.0.0/33"));
        assertThrows(IllegalArgumentException.class, () -> TrustedProxies.parse("example.com"));
        assertEquals(true, TrustedProxies.parse(" ").isEmpty());
    }
}
