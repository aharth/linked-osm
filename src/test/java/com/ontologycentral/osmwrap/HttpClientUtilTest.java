package com.ontologycentral.osmwrap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;

import org.junit.Test;

/**
 * Offline tests for {@link HttpClientUtil}. The bulk node/way fetchers that used to live
 * here are gone: every element is read from one {@code /full} response via
 * {@link OsmDocument}, see {@link OsmDocumentTest} and {@link UpstreamCacheTest}.
 */
public class HttpClientUtilTest {

    @Test
    public void timeoutMapsTo504() {
        assertEquals(504, HttpClientUtil.errorStatus(new HttpTimeoutException("slow")));
    }

    @Test
    public void otherIoFailuresMapTo500() {
        assertEquals(500, HttpClientUtil.errorStatus(new ConnectException("refused")));
        assertEquals(500, HttpClientUtil.errorStatus(new IOException("malformed OSM XML")));
    }

    @Test
    public void userAgentIdentifiesTheWrapper() {
        String ua = BuildInfo.getUserAgent();
        assertTrue(ua, ua.toLowerCase().contains("osm"));
    }
}
