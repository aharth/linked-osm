package com.ontologycentral.osmwrap.webapp;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Per-IP rate limit: 50 requests per 10 minutes.
 * FAU Erlangen-Nürnberg ranges are exempt:
 *   IPv4 131.188.0.0/16, IPv6 2001:638:a000::/48.
 * Fraunhofer IIS range exempt:
 *   IPv4 192.44.12.0/24.
 * Local ranges exempt:
 *   IPv4 192.168.0.0/16, IPv6 fc00::/7 and fe80::/10.
 *
 * <p>Requests carrying a valid API key in an {@code Authorization: Bearer <key>}
 * header are also exempt. A key is valid if either
 *
 * <ul>
 *   <li>a file named {@code <sha256-hex-of-key>.json} exists in the shared key
 *       directory given by the {@code keys-dir} servlet context parameter. Keys
 *       there are self-issued through the wunderfacts key service after a WebID
 *       login (see {@code wunderfacts/plans/api-keys.md}); deleting the file
 *       revokes the key. The raw key is never stored, only its hash. Unset
 *       {@code keys-dir} disables this lookup; or
 *   <li>it matches one of the static keys in the comma-separated
 *       {@code api-keys} servlet context parameter. This is the legacy shared
 *       token, kept as a fallback while callers move to self-issued keys.
 * </ul>
 *
 * Both parameters are filtered into {@code web.xml} at package time from the
 * {@code keys.dir} / {@code api.keys} Maven properties (set in
 * {@code ~/.m2/settings.xml}, never committed - see {@code pom.xml}). Both a
 * valid bearer key and the whitelisted-subnet/loopback exemptions above also
 * route Overpass-backed servlets to the paid Tracestrack endpoint instead of
 * the free public mirrors - see {@link OverpassRouting}.
 */
public class RateLimitFilter implements Filter {

    private static final int REQUESTS = 50;
    private static final Duration WINDOW = Duration.ofMinutes(10);

    // FAU IPv4: 131.188.0.0/16
    private static final byte[] FAU4_NET = { (byte) 131, (byte) 188, 0, 0 };
    private static final int FAU4_PREFIX = 16;

    // FAU IPv6: 2001:638:a000::/48
    private static final byte[] FAU6_NET;
    private static final int FAU6_PREFIX = 48;

    // Fraunhofer IIS IPv4: 192.44.12.0/24
    private static final byte[] FRAUNHOFER4_NET = { (byte) 192, (byte) 44, (byte) 12, 0 };
    private static final int FRAUNHOFER4_PREFIX = 24;

    // Local IPv4: 192.168.0.0/16
    private static final byte[] LOCAL4_NET = { (byte) 192, (byte) 168, 0, 0 };
    private static final int LOCAL4_PREFIX = 16;

    // Local IPv6: fc00::/7 (Unique Local Addresses)
    private static final byte[] LOCAL6_NET;
    private static final int LOCAL6_PREFIX = 7;

    // Local IPv6 Link-Local: fe80::/10
    private static final byte[] LOCAL6_LINK_NET;
    private static final int LOCAL6_LINK_PREFIX = 10;

    static {
        try {
            FAU6_NET = InetAddress.getByName("2001:638:a000::").getAddress();
            LOCAL6_NET = InetAddress.getByName("fc00::").getAddress();
            LOCAL6_LINK_NET = InetAddress.getByName("fe80::").getAddress();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final String KEYS_PARAM = "api-keys";
    private static final String KEYS_DIR_PARAM = "keys-dir";

    /** Self-issued keys are base64url; anything else cannot be in the store. */
    private static final java.util.regex.Pattern TOKEN_SHAPE =
            java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,128}");

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    private volatile Set<String> apiKeys = Set.of();

    /** Shared key directory, or {@code null} when filesystem keys are off. */
    private volatile Path keysDir;

    @Override
    public void init(FilterConfig cfg) {
        apiKeys = parseKeys(cfg.getServletContext().getInitParameter(KEYS_PARAM));
        keysDir = parseKeysDir(cfg.getServletContext().getInitParameter(KEYS_DIR_PARAM));
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;

        String ip = clientIp(req);
        boolean validKey = hasValidKey(req);
        boolean exempt = isExempt(ip) || isLoopbackRequest(req);
        req.setAttribute(OverpassRouting.TRUSTED_ATTR, validKey || exempt);

        if (!exempt && !validKey) {
            Bucket bucket = buckets.computeIfAbsent(ip, k ->
                    Bucket.builder()
                            .addLimit(Bandwidth.builder()
                                    .capacity(REQUESTS)
                                    .refillGreedy(REQUESTS, WINDOW)
                                    .build())
                            .build());
            if (!bucket.tryConsume(1)) {
                resp.sendError(429, "Too Many Requests");
                return;
            }
        }

        chain.doFilter(request, response);
    }

    @Override
    public void destroy() {}

    private boolean hasValidKey(HttpServletRequest req) {
        String token = bearerToken(req.getHeader("Authorization"));
        if (token == null) {
            return false;
        }
        if (keysDir != null && keyFileExists(keysDir, token)) {
            return true;
        }
        if (apiKeys.isEmpty()) {
            return false;
        }
        byte[] tokenBytes = token.getBytes(StandardCharsets.UTF_8);
        boolean match = false;
        // Constant-time comparison against every key so timing reveals nothing.
        for (String key : apiKeys) {
            match |= MessageDigest.isEqual(tokenBytes, key.getBytes(StandardCharsets.UTF_8));
        }
        return match;
    }

    static Set<String> parseKeys(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        Set<String> keys = new HashSet<>();
        for (String key : csv.split(",")) {
            key = key.trim();
            if (!key.isEmpty()) {
                keys.add(key);
            }
        }
        return Set.copyOf(keys);
    }

    static Path parseKeysDir(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Paths.get(value.trim());
    }

    /**
     * Whether {@code dir/<sha256-hex(token)>.json} exists. The file name is
     * derived from the hash, never from the token itself, so no token can
     * escape the directory; the shape check just skips hashing obvious junk.
     */
    static boolean keyFileExists(Path dir, String token) {
        if (dir == null || token == null || !TOKEN_SHAPE.matcher(token).matches()) {
            return false;
        }
        return Files.isRegularFile(dir.resolve(sha256Hex(token) + ".json"));
    }

    static String sha256Hex(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16))
                   .append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String bearerToken(String authHeader) {
        if (authHeader == null || !authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = authHeader.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) {
            int comma = xff.indexOf(',');
            return (comma >= 0 ? xff.substring(0, comma) : xff).trim();
        }
        return req.getRemoteAddr();
    }

    /**
     * A request that genuinely originated on this machine.
     *
     * <p>Exempted so a local smoke run does not trip the limiter halfway and
     * report throttling as breakage.
     *
     * <p><strong>Loopback is deliberately NOT in {@link #isExempt}.</strong>
     * That set is tested against the client IP, which prefers
     * {@code X-Forwarded-For} — a header anybody may send. Putting 127.0.0.1
     * there would let a request from the internet claim the exemption by writing
     * one line of its own headers. The test here is narrower and unspoofable from
     * outside: the connection's own peer address is loopback AND there is no
     * forwarding header at all. Behind a reverse proxy every request carries one,
     * so this can never exempt public traffic.
     */
    static boolean isLoopbackRequest(HttpServletRequest req) {
        if (req.getHeader("X-Forwarded-For") != null
                || req.getHeader("X-Real-IP") != null) {
            return false;
        }
        try {
            return java.net.InetAddress.getByName(req.getRemoteAddr())
                    .isLoopbackAddress();
        } catch (java.net.UnknownHostException e) {
            return false;
        }
    }

    static boolean isExempt(String ip) {
        try {
            byte[] addr = InetAddress.getByName(ip).getAddress();
            if (addr.length == 4) {
                return inSubnet(addr, FAU4_NET, FAU4_PREFIX)
                        || inSubnet(addr, FRAUNHOFER4_NET, FRAUNHOFER4_PREFIX)
                        || inSubnet(addr, LOCAL4_NET, LOCAL4_PREFIX);
            }
            if (addr.length == 16) {
                return inSubnet(addr, FAU6_NET, FAU6_PREFIX)
                        || inSubnet(addr, LOCAL6_NET, LOCAL6_PREFIX)
                        || inSubnet(addr, LOCAL6_LINK_NET, LOCAL6_LINK_PREFIX);
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static boolean inSubnet(byte[] addr, byte[] net, int prefix) {
        BigInteger a = new BigInteger(1, addr);
        BigInteger n = new BigInteger(1, net);
        int bits = addr.length * 8;
        BigInteger mask = BigInteger.ONE.shiftLeft(bits - prefix).subtract(BigInteger.ONE).not();
        return a.and(mask).equals(n.and(mask));
    }
}
