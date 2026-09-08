package com.ontologycentral.osmwrap.webapp;

import com.ontologycentral.osmwrap.ApiConstants;
import com.ontologycentral.osmwrap.BuildInfo;
import com.ontologycentral.osmwrap.HttpClientUtil;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Live reachability + static capability for every upstream this wrapper depends on, for
 * {@link StatusServlet}.
 *
 * <p>Unlike linked-adv's/linked-pdok's {@code ServiceStatusChecker}, there is no per-region
 * {@code ServiceRegistry} here to fan out over - osmwrap has a small, fixed, global set of
 * upstreams (one OSM API, one Nominatim, one Overpass, two key-gated tile/elevation providers),
 * so this reports a flat {@link #checkAll} list rather than a {@code regions.{region}.services[]}
 * tree. A consumer expecting the region-grouped shape (behaim's {@code loadLayerRegistry}, built
 * against linked-adv/-pdok's WMS registries) will need its own osmwrap-specific branch - there is
 * no WMS here to report {@code kind === "wms"} layers for in the first place.
 */
final class ServiceStatusChecker {

    private static final Logger _log = Logger.getLogger(ServiceStatusChecker.class.getName());

    /** Same cache window as linked-adv/-pdok's checker: repeat hits within 5 minutes of each
     *  other don't re-hammer every upstream. */
    private static final long CACHE_MILLIS = 5 * 60 * 1000;

    private static volatile List<ServiceStatus> cached;
    private static volatile long cachedAt;

    private ServiceStatusChecker() {
        // Utility class
    }

    /** One upstream's reachability + static capability. {@code keyConfigured} is {@code null}
     *  for the open APIs (OSM/Nominatim/Overpass need no key) and {@code true}/{@code false} only
     *  for the key-gated ones. {@code layers} is non-null only for the tile-shaped sources, and is
     *  a fixed hand-maintained list (no discovery API exists for Tracestrack's raster map names
     *  the way linked-pdok discovers WMS layers). {@code statusCode} is the raw HTTP status when
     *  a response was actually received (null for a config-only row, or a probe that never got a
     *  response at all - a timeout or DNS failure) - kept separate from {@code detail} so
     *  {@link StatusServlet}'s RDF view can build a proper {@code http:Response} individual
     *  instead of re-parsing it back out of a free-text string. */
    record ServiceStatus(String name, String kind, String uri, Boolean keyConfigured,
            Boolean ok, String detail, long millis, Map<String, Object> layers, Integer statusCode) {}

    static List<ServiceStatus> checkAll() {
        List<ServiceStatus> c = cached;
        if (c != null && System.currentTimeMillis() - cachedAt < CACHE_MILLIS) {
            return c;
        }
        synchronized (ServiceStatusChecker.class) {
            c = cached;
            if (c != null && System.currentTimeMillis() - cachedAt < CACHE_MILLIS) {
                return c;
            }
            List<ServiceStatus> fresh = runChecks();
            cached = fresh;
            cachedAt = System.currentTimeMillis();
            return fresh;
        }
    }

    private static List<ServiceStatus> runChecks() {
        CompletableFuture<ServiceStatus> osm = CompletableFuture.supplyAsync(() ->
                probeText("osm", "osm", ApiConstants.OSM_API_BASE + "/capabilities",
                        ServiceStatusChecker::osmOk));
        CompletableFuture<ServiceStatus> nominatim = CompletableFuture.supplyAsync(() ->
                probeText("nominatim", "nominatim", ApiConstants.NOMINATIM_API_BASE + "/status.php?format=json",
                        ServiceStatusChecker::nominatimOk));
        CompletableFuture<ServiceStatus> overpass = CompletableFuture.supplyAsync(() ->
                probeText("overpass", "overpass",
                        ApiConstants.OVERPASS_API_BASE.replace("/interpreter", "/status"),
                        ServiceStatusChecker::overpassOk));
        CompletableFuture<ServiceStatus> protomaps = CompletableFuture.supplyAsync(
                ServiceStatusChecker::checkProtomaps);
        CompletableFuture<ServiceStatus> tracestrackTile = CompletableFuture.supplyAsync(
                ServiceStatusChecker::checkTracestrackTile);
        ServiceStatus tracestrackElevation = checkTracestrackElevationConfigOnly();

        return List.of(osm.join(), nominatim.join(), overpass.join(), protomaps.join(),
                tracestrackTile.join(), tracestrackElevation);
    }

    /** True iff the OSM API's own {@code /capabilities} document came back - checked by looking
     *  for the {@code <api>} element every version of that document carries. */
    static boolean osmOk(String body) {
        return body.contains("<api>");
    }

    /** Nominatim's {@code status.php?format=json} answers {@code {"status":0,...}} when healthy -
     *  any other status code in the body (or none at all) means degraded/down. */
    static boolean nominatimOk(String body) {
        return body.contains("\"status\":0");
    }

    /** Overpass's {@code /api/status} is plain text, not JSON - it always mentions the rate-limit
     *  section by one of these phrasings depending on backend/version. */
    static boolean overpassOk(String body) {
        String lower = body.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("rate_limit") || lower.contains("slots available");
    }

    /** The Protomaps tile URL for the display/reporting form - {@code key} redacted, never the
     *  real value. Used both when no key is configured (nothing to redact) and after a live
     *  probe (redacting the key the probe itself had to use). */
    static String protomapsDisplayUrl() {
        return String.format(ApiConstants.PROTOMAPS_TILE_TEMPLATE, 0, 0, 0, "...");
    }

    /** Hand-maintained, not discovered: Tracestrack has no capabilities/layer-list API this
     *  wrapper's own passthrough servlets (or callers) can query. Kept in sync with the examples
     *  already served on index.html - update both together. */
    static Map<String, Object> tracestrackTileLayers() {
        Map<String, Object> layers = new LinkedHashMap<>();
        layers.put("topo", Map.of("kind", "raster"));
        layers.put("topo_en", Map.of("kind", "raster"));
        layers.put("en", Map.of("kind", "raster"));
        layers.put("carto", Map.of("kind", "vector", "path", "vt/carto"));
        layers.put("terrain-rgb", Map.of("kind", "terrain-rgb"));
        return layers;
    }

    private static ServiceStatus checkProtomaps() {
        String key = BuildInfo.getProtomapsApiKey();
        String displayUrl = protomapsDisplayUrl();
        if (key.isEmpty()) {
            return new ServiceStatus("protomaps", "tile", displayUrl,
                    false, null, "no key configured - not probed", 0, null, null);
        }
        String url = String.format(ApiConstants.PROTOMAPS_TILE_TEMPLATE, 0, 0, 0, key);
        // Protomaps tiles are VECTOR (.mvt) - a protobuf, not an image - so the expected
        // content-type substring is "protobuf"/"vector-tile", not "image" (that check is for
        // Tracestrack's raster probe below).
        ServiceStatus probed = probeContentType("protomaps", "tile", url,
                ct -> ct.contains("protobuf") || ct.contains("vector-tile"));
        // Redact the key out of the reported URI - probe() only ever sees it for the fetch
        // itself, never for what ends up in the served /status document.
        return new ServiceStatus(probed.name(), probed.kind(), displayUrl, true, probed.ok(),
                probed.detail(), probed.millis(), null, probed.statusCode());
    }

    private static ServiceStatus checkTracestrackTile() {
        String key = BuildInfo.getTracestrackApiKey();
        Map<String, Object> layers = tracestrackTileLayers();
        if (key.isEmpty()) {
            return new ServiceStatus("tracestrack-tile", "tile", ApiConstants.TRACESTRACK_TILE_BASE,
                    false, null, "no key configured - not probed", 0, layers, null);
        }
        String url = ApiConstants.TRACESTRACK_TILE_BASE + "/topo/0/0/0.webp?key=" + key;
        ServiceStatus probed = probeContentType("tracestrack-tile", "tile", url,
                ct -> ct.contains("image"));
        return new ServiceStatus(probed.name(), probed.kind(),
                ApiConstants.TRACESTRACK_TILE_BASE, true, probed.ok(), probed.detail(),
                probed.millis(), layers, probed.statusCode());
    }

    /** Elevation is deliberately NOT live-probed - unlike a single low-zoom tile fetch, every
     *  probe would spend one of the account's paid Tracestrack credits purely to answer a status
     *  check, every 5 minutes, for every visitor. Configuration presence is reported instead. */
    private static ServiceStatus checkTracestrackElevationConfigOnly() {
        String key = BuildInfo.getTracestrackApiKey();
        return new ServiceStatus("tracestrack-elevation", "elevation",
                ApiConstants.TRACESTRACK_TILE_BASE + "/elevation", !key.isEmpty(), null,
                key.isEmpty() ? "no key configured" : "not live-probed (would spend a paid credit)",
                0, null, null);
    }

    /** A text-body reachability probe: GET {@code uri}, ok iff HTTP 200 and {@code bodyOk}
     *  accepts the decoded response body. For an API that answers JSON/XML/plain text - not a
     *  tile fetch, see {@link #probeContentType}. */
    private static ServiceStatus probeText(String name, String kind, String uri,
            java.util.function.Predicate<String> bodyOk) {
        return probe(name, kind, uri,
                p -> p.status == 200 && bodyOk.test(p.bodyText),
                p -> "HTTP " + p.status);
    }

    /** A binary reachability probe: GET {@code uri}, ok iff HTTP 200 and {@code contentTypeOk}
     *  accepts the response's {@code Content-Type} - a tile response isn't text, so the body
     *  bytes are drained (to free the connection) but never inspected as text. */
    private static ServiceStatus probeContentType(String name, String kind, String uri,
            java.util.function.Predicate<String> contentTypeOk) {
        return probe(name, kind, uri,
                p -> p.status == 200 && contentTypeOk.test(p.contentType),
                p -> "HTTP " + p.status + " " + p.contentType);
    }

    /** One fetched response's status/content-type/body, decoded once regardless of which
     *  predicate below actually looks at what. */
    private record Probed(int status, String contentType, String bodyText) {}

    private static ServiceStatus probe(String name, String kind, String uri,
            java.util.function.Predicate<Probed> okCheck,
            java.util.function.Function<Probed, String> detailFn) {
        long start = System.currentTimeMillis();
        try {
            HttpResponse<java.io.InputStream> resp = HttpClientUtil.get(uri);
            byte[] body;
            try (java.io.InputStream is = resp.body()) {
                body = is.readAllBytes();
            }
            long millis = System.currentTimeMillis() - start;
            Probed p = new Probed(resp.statusCode(),
                    resp.headers().firstValue("Content-Type").orElse(""),
                    new String(body, java.nio.charset.StandardCharsets.UTF_8));
            boolean ok = okCheck.test(p);
            String detail = detailFn.apply(p);
            return new ServiceStatus(name, kind, uri, null, ok, detail, millis, null, p.status());
        } catch (IOException e) {
            long millis = System.currentTimeMillis() - start;
            _log.log(Level.WARNING, name + " status probe failed: " + e.getMessage());
            // No response at all (timeout, DNS failure, connection reset, ...) - statusCode
            // stays null, same as a config-only row; StatusServlet's RDF view tells the two
            // apart by whether `ok` is null (never checked) vs false (checked and failed).
            return new ServiceStatus(name, kind, uri, null, false, e.getMessage(), millis, null, null);
        }
    }

    static Instant lastCheckedAt() {
        long at = cachedAt;
        return at == 0 ? Instant.now() : Instant.ofEpochMilli(at);
    }
}
