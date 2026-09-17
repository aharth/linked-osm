package com.ontologycentral.osmwrap;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLSession;

import org.junit.Test;

import com.ontologycentral.osmwrap.UpstreamCache.Fetched;
import com.ontologycentral.osmwrap.UpstreamCache.UpstreamException;

/** Offline tests for {@link UpstreamCache}: cache hits, in-flight dedup, error propagation. */
public class UpstreamCacheTest {

    /** A canned HTTP response; no network. */
    private static HttpResponse<InputStream> response(String url, int status, String body, Map<String, List<String>> headers) {
        return new HttpResponse<>() {
            @Override public int statusCode() { return status; }
            @Override public HttpRequest request() { return HttpRequest.newBuilder(URI.create(url)).build(); }
            @Override public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
            @Override public HttpHeaders headers() { return HttpHeaders.of(headers, (a, b) -> true); }
            @Override public InputStream body() { return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)); }
            @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
            @Override public URI uri() { return URI.create(url); }
            @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_2; }
        };
    }

    @Test
    public void secondRequestIsServedFromCache() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        UpstreamCache cache = new UpstreamCache(1 << 20, Duration.ofMinutes(1), url -> {
            calls.incrementAndGet();
            return response(url, 200, "<osm/>", Map.of("content-length", List.of("6")));
        });
        Fetched a = cache.fetch("u1");
        Fetched b = cache.fetch("u1");
        assertSame(a, b);
        assertEquals(1, calls.get());
        assertEquals(6, a.byteCount());
        assertArrayEquals("<osm/>".getBytes(StandardCharsets.UTF_8), a.body());
        assertEquals(1, cache.size());
        cache.fetch("u2");
        assertEquals(2, calls.get());
    }

    @Test
    public void missingContentLengthIsMinusOne() throws IOException {
        UpstreamCache cache = new UpstreamCache(1 << 20, Duration.ofMinutes(1),
                url -> response(url, 200, "<osm/>", Map.of()));
        assertEquals(-1, cache.fetch("u").byteCount());
    }

    @Test
    public void non200IsAnUpstreamExceptionAndNotCached() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        UpstreamCache cache = new UpstreamCache(1 << 20, Duration.ofMinutes(1), url -> {
            calls.incrementAndGet();
            return response(url, 404, "gone", Map.of("Content-Type", List.of("text/plain")));
        });
        for (int i = 0; i < 2; i++) {
            try {
                cache.fetch("u");
                fail("expected UpstreamException");
            } catch (UpstreamException e) {
                assertEquals(404, e.status());
                assertEquals("text/plain", e.contentType());
                assertEquals("gone", new String(e.body(), StandardCharsets.UTF_8));
            }
        }
        assertEquals("errors are retried, not cached", 2, calls.get());
        assertEquals(0, cache.size());
    }

    @Test
    public void transportFailurePropagatesAndIsNotCached() {
        AtomicInteger calls = new AtomicInteger();
        UpstreamCache cache = new UpstreamCache(1 << 20, Duration.ofMinutes(1), url -> {
            calls.incrementAndGet();
            throw new java.net.http.HttpTimeoutException("slow");
        });
        for (int i = 0; i < 2; i++) {
            try {
                cache.fetch("u");
                fail("expected IOException");
            } catch (IOException e) {
                assertEquals(504, HttpClientUtil.errorStatus(e));
            }
        }
        assertEquals(2, calls.get());
    }

    @Test
    public void concurrentRequestsShareOneFetch() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        UpstreamCache cache = new UpstreamCache(1 << 20, Duration.ofMinutes(1), url -> {
            calls.incrementAndGet();
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
            return response(url, 200, "<osm/>", Map.of());
        });
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Future<Fetched> first = pool.submit(() -> cache.fetch("u"));
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
            List<Future<Fetched>> others = List.of(
                    pool.submit(() -> cache.fetch("u")),
                    pool.submit(() -> cache.fetch("u")),
                    pool.submit(() -> cache.fetch("u")));
            Thread.sleep(100); // let the joiners register
            release.countDown();
            Fetched f = first.get(5, java.util.concurrent.TimeUnit.SECONDS);
            for (Future<Fetched> o : others) {
                assertSame(f, o.get(5, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertEquals("one upstream call for four concurrent requests", 1, calls.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void joinersSeeTheSameUpstreamError() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        UpstreamCache cache = new UpstreamCache(1 << 20, Duration.ofMinutes(1), url -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
            return response(url, 509, "limit", Map.of());
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Fetched> first = pool.submit(() -> cache.fetch("u"));
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
            Future<Fetched> joiner = pool.submit(() -> cache.fetch("u"));
            Thread.sleep(100);
            release.countDown();
            for (Future<Fetched> f : List.of(first, joiner)) {
                try {
                    f.get(5, java.util.concurrent.TimeUnit.SECONDS);
                    fail("expected UpstreamException");
                } catch (java.util.concurrent.ExecutionException e) {
                    assertTrue(e.getCause() instanceof UpstreamException);
                    assertEquals(509, ((UpstreamException) e.getCause()).status());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
