package com.ontologycentral.osmwrap;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Cache of raw upstream response bodies keyed by URL, with in-flight deduplication: while
 * a URL is being fetched, every other request for it waits for that one fetch instead of
 * starting its own. Shared by all servlets that read OSM API elements, so a given element
 * costs the upstream at most one request per cache window whatever its size or popularity.
 *
 * <p>Bodies are kept as bytes, not strings: {@link OsmDocument} and Saxon both read bytes
 * directly, and a {@code /full} relation response can be tens of MB.
 */
public final class UpstreamCache {
    private static final Logger _log = Logger.getLogger(UpstreamCache.class.getName());

    /** Servlet-context attribute under which {@code Listener} publishes the shared instance. */
    public static final String ATTR = "upstream-cache";

    /**
     * A cached upstream response: the raw body and the {@code Content-Length} the upstream
     * declared (-1 when it sent none, e.g. chunked {@code /full} responses).
     */
    public record Fetched(byte[] body, long byteCount) {}

    /** Upstream answered with a non-200 status; carries what it sent so callers can relay it. */
    public static final class UpstreamException extends IOException {
        private static final long serialVersionUID = 1L;
        private final int status;
        private final String contentType;
        private final byte[] body;

        public UpstreamException(String url, int status, String contentType, byte[] body) {
            super("upstream " + status + " from " + url);
            this.status = status;
            this.contentType = contentType;
            this.body = body;
        }

        public int status() {
            return status;
        }

        public String contentType() {
            return contentType;
        }

        public byte[] body() {
            return body;
        }
    }

    private final Cache<String, Fetched> cache;
    private final ConcurrentHashMap<String, CompletableFuture<Fetched>> inFlight = new ConcurrentHashMap<>();
    private final Fetcher fetcher;

    /** How a URL is actually fetched; replaceable for tests. */
    @FunctionalInterface
    public interface Fetcher {
        HttpResponse<InputStream> get(String url) throws IOException;
    }

    /** @param maxWeightBytes total body bytes to keep; @param ttl how long an entry stays valid */
    public UpstreamCache(long maxWeightBytes, Duration ttl) {
        this(maxWeightBytes, ttl, HttpClientUtil::get);
    }

    public UpstreamCache(long maxWeightBytes, Duration ttl, Fetcher fetcher) {
        this.fetcher = fetcher;
        this.cache = Caffeine.newBuilder()
                .maximumWeight(maxWeightBytes)
                .weigher((String k, Fetched v) -> v.body().length)
                .expireAfterWrite(ttl)
                .build();
    }

    /**
     * The body of {@code url}, from cache, from a fetch already in progress, or freshly fetched.
     *
     * @throws UpstreamException if upstream answered with a non-200 status
     * @throws IOException       on transport failure (timeouts map to 504 via
     *                           {@link HttpClientUtil#errorStatus})
     */
    public Fetched fetch(String url) throws IOException {
        Fetched cached = cache.getIfPresent(url);
        if (cached != null) {
            _log.info("cache hit: " + url);
            return cached;
        }
        CompletableFuture<Fetched> fresh = new CompletableFuture<>();
        CompletableFuture<Fetched> existing = inFlight.putIfAbsent(url, fresh);
        if (existing != null) {
            _log.info("joining in-flight fetch: " + url);
            try {
                return existing.join();
            } catch (CompletionException ce) {
                Throwable cause = ce.getCause();
                if (cause instanceof IOException) throw (IOException) cause;
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                throw new IOException(cause);
            }
        }
        try {
            _log.info("retrieving " + url);
            HttpResponse<InputStream> response = fetcher.get(url);
            byte[] body;
            try (InputStream is = response.body()) {
                body = is.readAllBytes();
            }
            if (response.statusCode() != 200) {
                throw new UpstreamException(url, response.statusCode(),
                        response.headers().firstValue("Content-Type").orElse(null), body);
            }
            long byteCount = response.headers().firstValueAsLong("content-length").orElse(-1L);
            Fetched result = new Fetched(body, byteCount);
            cache.put(url, result);
            fresh.complete(result);
            return result;
        } catch (IOException | RuntimeException e) {
            fresh.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(url, fresh);
        }
    }

    /** Number of entries currently cached (for tests and status pages). */
    public long size() {
        cache.cleanUp();
        return cache.estimatedSize();
    }
}
