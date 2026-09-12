package com.ontologycentral.osmwrap.webapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Subnet exemption and API-key lookup logic of the per-IP rate limit. */
public class RateLimitFilterTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

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

    @Test
    public void sha256HexKnownAnswer() {
        // printf 'abc' | sha256sum
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                RateLimitFilter.sha256Hex("abc"));
        assertEquals(64, RateLimitFilter.sha256Hex("").length());
    }

    @Test
    public void keyFileExistsMatchesHashedFileName() throws IOException {
        Path dir = tmp.getRoot().toPath();
        String key = "wf_abcDEF0123456789-_";
        Files.writeString(dir.resolve(RateLimitFilter.sha256Hex(key) + ".json"),
                "{\"principal\":\"https://example.org/me#i\"}");
        assertTrue(RateLimitFilter.keyFileExists(dir, key));
        assertFalse(RateLimitFilter.keyFileExists(dir, "wf_unknown"));
        assertFalse(RateLimitFilter.keyFileExists(dir, "../../etc/passwd"));
        assertFalse(RateLimitFilter.keyFileExists(dir, "has space"));
        assertFalse(RateLimitFilter.keyFileExists(dir, ""));
        assertFalse(RateLimitFilter.keyFileExists(dir, null));
        assertFalse(RateLimitFilter.keyFileExists(dir.resolve("missing"), key));
        assertFalse(RateLimitFilter.keyFileExists(null, key));
    }

    @Test
    public void keyFileMustBeRegularFile() throws IOException {
        Path dir = tmp.getRoot().toPath();
        String key = "wf_dirnotfile";
        Files.createDirectory(dir.resolve(RateLimitFilter.sha256Hex(key) + ".json"));
        assertFalse(RateLimitFilter.keyFileExists(dir, key));
    }

    @Test
    public void parseKeysDirBlankMeansOff() {
        assertNull(RateLimitFilter.parseKeysDir(null));
        assertNull(RateLimitFilter.parseKeysDir("   "));
        assertEquals(Path.of("/var/lib/wunderfacts/keys"),
                RateLimitFilter.parseKeysDir(" /var/lib/wunderfacts/keys "));
    }
}
