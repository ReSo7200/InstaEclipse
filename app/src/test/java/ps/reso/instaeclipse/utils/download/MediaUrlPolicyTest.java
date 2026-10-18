/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package ps.reso.instaeclipse.utils.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Which addresses a save may fetch. The addresses come out of the app's objects,
 * so each of these is a shape something could plant there.
 */
public class MediaUrlPolicyTest {
    /** The shape of a captured story address (host from a real capture, query shortened). */
    private static final String CDN =
            "https://scontent-iad3-1.xx.fbcdn.net/v/t42.1790-2/461234_n.mp4?_nc_cat=1&efg=eyJ2In0&oh=00_AYA&oe=66F0A1B2";

    /** A Meta edge address, as a public lookup answers. */
    private static final String PUBLIC = "157.240.22.19";

    private static MediaUrlPolicy resolvingTo(String... literals) {
        return new MediaUrlPolicy(host -> {
            InetAddress[] addresses = new InetAddress[literals.length];
            // IP literals only, so no lookup ever leaves the test.
            for (int i = 0; i < literals.length; i++) addresses[i] = InetAddress.getByName(literals[i]);
            return addresses;
        });
    }

    private static String refusal(MediaUrlPolicy policy, String url) throws Exception {
        MediaUrlPolicy.Refusal refusal = policy.refusal(new URL(url));
        return refusal == null ? null : refusal.reason;
    }

    /** Through a VPN or a proxy: the phone's own answer isn't where the socket goes. */
    private static MediaUrlPolicy tunneled(MediaUrlPolicy.Resolver resolver) {
        return new MediaUrlPolicy(resolver, url -> false);
    }

    @Test
    public void aMetaMediaAddressOnAPublicHostPasses() throws Exception {
        assertNull(refusal(resolvingTo(PUBLIC), CDN));
        assertNull(refusal(resolvingTo(PUBLIC, "2a03:2880:f12f:83:face:b00c:0:25de"),
                "https://video-iad3-1.xx.fbcdn.net/o1/v/t2/f2/m69/AQM.mp4?efg=x&oh=1&oe=2"));
        assertNull(refusal(resolvingTo(PUBLIC), "https://lookaside.fbsbx.com/lookaside/crawler/media/?media_id=1"));
        assertNull(refusal(resolvingTo(PUBLIC), "https://scontent.cdninstagram.com/v/t51.2885-15/x.jpg"));
        assertNull("a trailing dot names the same host", refusal(resolvingTo(PUBLIC), "https://scontent.xx.fbcdn.net./x.mp4"));
        assertNull("the default port may be written out", refusal(resolvingTo(PUBLIC), "https://scontent.xx.fbcdn.net:443/x.mp4"));
    }

    /**
     * Instagram's own media servers and their regional edges pass over HTTPS, and so does the
     * fbcdn.net edge Instagram also serves from. A host that only carries the name doesn't.
     */
    @Test
    public void instagramsMediaServersPassAndLookalikesDoNot() throws Exception {
        String reel = "https://scontent-lga3-1.cdninstagram.com/o1/v/t16/f2/m86/x.mp4"
                + "?stp=dst-mp4&efg=eyJ2In0&_nc_ht=scontent-lga3-1.cdninstagram.com&oh=00_AYA&oe=66F0A1B2";
        assertNull(refusal(resolvingTo(PUBLIC), reel));
        assertNull(refusal(resolvingTo(PUBLIC), "https://instagram.fphl1-1.fna.fbcdn.net/v/t51.2885-15/x.jpg?oh=1&oe=2"));
        assertNull(refusal(resolvingTo(PUBLIC), "https://SCONTENT.CDNINSTAGRAM.COM/v/t51.2885-15/x.jpg"));
        assertNull(refusal(resolvingTo(PUBLIC), "https://cdninstagram.com/x.jpg"));
        assertTrue(MediaUrlPolicy.isMetaMediaHost("scontent-lga3-1.cdninstagram.com"));

        assertEquals("it is not HTTPS", refusal(resolvingTo(PUBLIC), reel.replace("https://", "http://")));
        for (String url : new String[] {
                "https://www.instagram.com/x.mp4",
                "https://evilcdninstagram.com/x.mp4",
                "https://cdninstagram.com.example.com/x.mp4",
                "https://scontent.cdninstagram.co/x.mp4",
                "https://example.org/o1/v/t16/f2/m86/x.mp4",
        }) {
            assertFalse(url, MediaUrlPolicy.isMetaMediaHost(new URL(url).getHost()));
            assertEquals(url, "its host is not one of Meta's media servers", refusal(resolvingTo(PUBLIC), url));
        }
    }

    @Test
    public void plainHttpIsRefused() throws Exception {
        assertEquals("it is not HTTPS", refusal(resolvingTo(PUBLIC), CDN.replace("https://", "http://")));
    }

    @Test
    public void aUserNameInFrontOfTheHostIsRefused() throws Exception {
        // Reads like fbcdn, goes to example.com.
        assertEquals("it carries a user name",
                refusal(resolvingTo(PUBLIC), "https://scontent.xx.fbcdn.net@example.com/x.mp4"));
        assertEquals("it carries a user name",
                refusal(resolvingTo(PUBLIC), "https://user:pass@scontent.xx.fbcdn.net/x.mp4"));
    }

    @Test
    public void anotherPortIsRefused() throws Exception {
        assertEquals("it names a port other than 443",
                refusal(resolvingTo(PUBLIC), "https://scontent.xx.fbcdn.net:8443/x.mp4"));
    }

    @Test
    public void hostsOutsideMetasMediaServersAreRefused() throws Exception {
        String[] foreign = {
                "https://example.com/x.mp4",
                "https://fbcdn.net.example.com/x.mp4",
                "https://evilfbcdn.net/x.mp4",
                "https://scontent.example.com/x.mp4",
                "https://www.facebook.com/x.mp4",
                "https://fbcdn.net.evil/x.mp4",
        };
        for (String url : foreign) {
            assertEquals(url, "its host is not one of Meta's media servers", refusal(resolvingTo(PUBLIC), url));
        }
    }

    @Test
    public void aBareAddressIsRefused() throws Exception {
        for (String url : new String[] {
                "https://157.240.22.19/x.mp4", "https://[2a03:2880::1]/x.mp4", "https://2130706433/x.mp4",
                "https://0x7f.1/x.mp4" }) {
            String reason = refusal(resolvingTo(PUBLIC), url);
            assertNotNull(url, reason);
        }
    }

    @Test
    public void everyNonPublicAnswerIsRefused() throws Exception {
        String[][] cases = {
                { "127.0.0.1", "loopback" }, { "::1", "loopback" },
                { "10.1.2.3", "private" }, { "172.16.5.4", "private" }, { "172.31.255.1", "private" },
                { "192.168.1.1", "private" }, { "fd12:3456::1", "private" },
                { "169.254.169.254", "link-local" }, { "fe80::1", "link-local" },
                { "224.0.0.1", "multicast" }, { "ff02::1", "multicast" },
                { "0.0.0.0", "unspecified" }, { "::", "unspecified" },
                { "240.0.0.1", "reserved" }, { "255.255.255.255", "reserved" }, { "fc80::1", "private" },
                { "192.0.2.1", "reserved" }, { "203.0.113.9", "reserved" }, { "2001:db8::1", "reserved" },
                { "100.64.0.1", "carrier-grade NAT" },
                { "::ffff:10.0.0.1", "private" }, { "64:ff9b::a00:1", "private" }, { "2002:a00:1::", "private" },
                // Java hands a mapped address back as IPv4, so only the compatible form reaches
                // the embedded-address rule.
                { "::a00:1", "private" }, { "::7f00:1", "loopback" },
                { "198.51.100.7", "reserved" }, { "192.88.99.1", "reserved" }, { "192.0.0.9", "reserved" },
                { "fdfe:dcba:9876:1::1", "private" }, { "fdfe:dcba:9877::1", "private" },
                // One neighbour of the Clash /64 for each byte the rule compares, and the low side.
                { "fdff:dcba:9876::1", "private" }, { "fdfe:ddba:9876::1", "private" },
                { "fdfe:dcbb:9876::1", "private" }, { "fdfe:dcba:9976::1", "private" },
                { "fdfe:dcba:9876:100::1", "private" }, { "fdfe:dcba:9875:ffff::1", "private" },
                // The first byte too: fcfe: is the /64 with fc in front, in fc00::/7 but in neither pool.
                { "fcfe:dcba:9876::1", "private" },
        };
        List<String> accepted = new ArrayList<>();
        for (String[] c : cases) {
            String reason = refusal(resolvingTo(c[0]), CDN);
            if (reason == null || !reason.contains(c[1])) accepted.add(c[0] + " -> " + reason);
        }
        assertTrue("these answers were accepted or misnamed: " + accepted, accepted.isEmpty());
    }

    @Test
    public void onePrivateAnswerAmongPublicOnesIsEnough() throws Exception {
        assertEquals("its host resolves to a private address", refusal(resolvingTo(PUBLIC, "10.0.0.1"), CDN));
    }

    @Test
    public void aHostThatDoesNotResolveIsRefused() throws Exception {
        MediaUrlPolicy nowhere = new MediaUrlPolicy(host -> { throw new UnknownHostException(host); });
        assertEquals("its host does not resolve", refusal(nowhere, CDN));
        MediaUrlPolicy empty = new MediaUrlPolicy(host -> new InetAddress[0]);
        assertEquals("its host does not resolve", refusal(empty, CDN));
    }

    /**
     * A failed lookup or a private answer is the network's problem, not a file that isn't
     * Meta's, and the person saving is told a different thing for each.
     */
    @Test
    public void networkRefusalsAreToldApartFromAddressRefusals() throws Exception {
        MediaUrlPolicy.Refusal lookup = new MediaUrlPolicy(host -> { throw new UnknownHostException(host); })
                .refusal(new URL(CDN));
        assertTrue(lookup.reason, lookup.network);
        MediaUrlPolicy.Refusal hijacked = resolvingTo("10.10.34.34").refusal(new URL(CDN));
        assertTrue(hijacked.reason, hijacked.network);
        MediaUrlPolicy.Refusal foreign = resolvingTo(PUBLIC).refusal(new URL("https://example.com/v.mp4"));
        assertTrue(foreign.reason, !foreign.network);
    }

    /**
     * Through a VPN or a proxy the phone's own answer isn't where the socket goes. Fake-IP proxy
     * clients answer every name from 198.18.0.0/15 or fc00::/18 and carry the connection to the
     * real host, so there the address rules decide and the lookup isn't asked at all.
     */
    @Test
    public void throughATunnelTheLookupIsNotTheGate() throws Exception {
        assertNull(refusal(tunneled(host -> new InetAddress[] { InetAddress.getByName("198.18.0.23") }), CDN));
        assertNull(refusal(tunneled(host -> new InetAddress[] { InetAddress.getByName("fc00::17") }), CDN));
        assertNull("a proxy resolves the name itself",
                refusal(tunneled(host -> { throw new UnknownHostException(host); }), CDN));
        // Not asked at all, rather than asked and then forgiven: the route decides first.
        List<String> asked = new ArrayList<>();
        assertNull(refusal(tunneled(host -> {
            asked.add(host);
            return new InetAddress[] { InetAddress.getByName("10.0.0.1") };
        }), CDN));
        assertEquals("a tunnel route looked the host up", Collections.emptyList(), asked);
        // The address rules still hold.
        MediaUrlPolicy anyAnswer = tunneled(host -> new InetAddress[] { InetAddress.getByName(PUBLIC) });
        assertEquals("it is not HTTPS", refusal(anyAnswer, CDN.replace("https://", "http://")));
        assertEquals("its host is not one of Meta's media servers", refusal(anyAnswer, "https://example.com/v.mp4"));
        assertEquals("its host is a bare IP address", refusal(anyAnswer, "https://10.0.0.1/v.mp4"));
    }

    /**
     * The same fake-IP DNS often runs on the home router instead, and then the phone sees a direct
     * route answering from those pools. Refusing them there failed every save behind such a router,
     * so both pools pass on any route, while the rest of fc00::/7 and every LAN range still don't.
     */
    @Test
    public void aFakeIpAnswerPassesOnADirectRouteToo() throws Exception {
        assertNull(refusal(resolvingTo("198.18.0.23"), CDN));
        assertNull(refusal(resolvingTo("198.19.255.1"), CDN));
        assertNull(refusal(resolvingTo("fc00::17"), CDN));
        assertNull(refusal(resolvingTo("fc00:3fff::1"), CDN));
        assertEquals("its host resolves to a private address", refusal(resolvingTo("fc00:4000::1"), CDN));
        assertEquals("its host resolves to a private address", refusal(resolvingTo("fd00::1"), CDN));
        assertEquals("its host resolves to a private address", refusal(resolvingTo("192.168.1.1"), CDN));
        // Clash and mihomo answer IPv6 from fdfe:dcba:9876::/64, OpenClash's preset, and a router
        // doing both families hands out one of each.
        assertNull(refusal(resolvingTo("fdfe:dcba:9876::c"), CDN));
        assertNull(refusal(resolvingTo("198.18.0.12", "fdfe:dcba:9876::c"), CDN));
        assertNull(refusal(resolvingTo("fdfe:dcba:9876:0:ffff:ffff:ffff:ffff"), CDN));
    }

    /**
     * The lookup asks for the host the way the connection will, trailing dot included, so both
     * read one cache entry rather than two a resolver could answer differently.
     */
    @Test
    public void theLookupUsesTheHostAsTheConnectionWritesIt() throws Exception {
        List<String> asked = new ArrayList<>();
        MediaUrlPolicy recording = new MediaUrlPolicy(host -> {
            asked.add(host);
            return new InetAddress[] { InetAddress.getByName(PUBLIC) };
        });
        assertNull(refusal(recording, "https://Scontent.XX.fbcdn.net./x.mp4"));
        assertEquals(java.util.Collections.singletonList("scontent.xx.fbcdn.net."), asked);
    }

    @Test
    public void aConfiguredProxyCountsAsNotDirect() throws Exception {
        java.net.ProxySelector before = java.net.ProxySelector.getDefault();
        try {
            java.net.ProxySelector.setDefault(null);
            assertTrue("no selector means no proxy", !MediaUrlPolicy.proxied(new URL(CDN)));
            java.net.ProxySelector.setDefault(new java.net.ProxySelector() {
                @Override
                public java.util.List<java.net.Proxy> select(java.net.URI uri) {
                    return java.util.Collections.singletonList(new java.net.Proxy(java.net.Proxy.Type.HTTP,
                            java.net.InetSocketAddress.createUnresolved("proxy.example", 3128)));
                }

                @Override
                public void connectFailed(java.net.URI uri, java.net.SocketAddress address, java.io.IOException e) {
                }
            });
            assertTrue(MediaUrlPolicy.proxied(new URL(CDN)));
        } finally {
            java.net.ProxySelector.setDefault(before);
        }
    }

    @Test
    public void theShapeCheckNeedsNoLookup() {
        assertNull(MediaUrlPolicy.shapeRefusal(CDN));
        assertEquals("it is not HTTPS", MediaUrlPolicy.shapeRefusal("ftp://scontent.xx.fbcdn.net/x.mp4"));
        assertEquals("it is not a well-formed address", MediaUrlPolicy.shapeRefusal("not an address"));
        assertEquals("there is no address", MediaUrlPolicy.shapeRefusal((String) null));
    }
}
