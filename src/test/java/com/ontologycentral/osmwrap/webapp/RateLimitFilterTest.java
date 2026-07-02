package com.ontologycentral.osmwrap.webapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Set;
import org.junit.Test;

/** Subnet exemption logic of the per-IP rate limit. */
public class RateLimitFilterTest {

    @Test
    public void localIpv4RangeIsExempt() {
        assertTrue(RateLimitFilter.isExempt("192.168.0.1"));
        assertTrue(RateLimitFilter.isExempt("192.168.0.254"));
        assertTrue(RateLimitFilter.isExempt("192.168.255.255"));
    }

    @Test
    public void fauRangesAreExempt() {
        assertTrue(RateLimitFilter.isExempt("131.188.0.1"));
        assertTrue(RateLimitFilter.isExempt("131.188.255.254"));
        assertTrue(RateLimitFilter.isExempt("2001:638:a000::1"));
        assertTrue(RateLimitFilter.isExempt("2001:638:a000:ffff::1"));
    }

    @Test
    public void fraunhoferRangeIsExempt() {
        assertTrue(RateLimitFilter.isExempt("192.44.12.1"));
        assertFalse(RateLimitFilter.isExempt("192.44.13.1"));
    }

    @Test
    public void localIpv6RangesAreExempt() {
        assertTrue(RateLimitFilter.isExempt("fc00::1"));
        assertTrue(RateLimitFilter.isExempt("fd12:3456:789a::1"));
        assertTrue(RateLimitFilter.isExempt("fe80::1"));
        assertTrue(RateLimitFilter.isExempt("::ffff:192.168.0.1")); // IPv4-mapped resolves to 4 bytes
    }

    @Test
    public void publicAddressesAreNotExempt() {
        assertFalse(RateLimitFilter.isExempt("8.8.8.8"));
        assertFalse(RateLimitFilter.isExempt("192.169.0.1"));
        assertFalse(RateLimitFilter.isExempt("131.189.0.1"));
        assertFalse(RateLimitFilter.isExempt("2001:638:a001::1"));
        assertFalse(RateLimitFilter.isExempt("2a00:1450:4001::1"));
    }

    @Test
    public void garbageInputIsNotExempt() {
        assertFalse(RateLimitFilter.isExempt("not-an-ip !!"));
        assertFalse(RateLimitFilter.isExempt(""));
    }

    @Test
    public void parseKeysSplitsAndTrims() {
        assertEquals(Set.of("abc", "def"), RateLimitFilter.parseKeys(" abc , def "));
        assertEquals(Set.of("abc"), RateLimitFilter.parseKeys("abc,,"));
        assertEquals(Set.of(), RateLimitFilter.parseKeys(null));
        assertEquals(Set.of(), RateLimitFilter.parseKeys("  "));
    }

    @Test
    public void bearerTokenExtraction() {
        assertEquals("abc123", RateLimitFilter.bearerToken("Bearer abc123"));
        assertEquals("abc123", RateLimitFilter.bearerToken("bearer abc123"));
        assertNull(RateLimitFilter.bearerToken(null));
        assertNull(RateLimitFilter.bearerToken("Bearer "));
        assertNull(RateLimitFilter.bearerToken("Basic abc123"));
        assertNull(RateLimitFilter.bearerToken("abc123"));
    }
}
