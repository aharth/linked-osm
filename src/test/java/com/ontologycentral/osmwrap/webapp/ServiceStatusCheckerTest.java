package com.ontologycentral.osmwrap.webapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;

/**
 * Offline coverage of {@link ServiceStatusChecker}'s per-source body-check predicates and the
 * key-redaction contract - the parts that don't need a live network call. Mirrors linked-pdok's
 * {@code ServiceStatusCheckerTest} in spirit (synthetic bodies standing in for real responses),
 * adapted to osmwrap's fixed set of upstreams instead of a per-region WFS/WMS/Features registry.
 */
public class ServiceStatusCheckerTest {

    @Test
    public void osmOkOnCapabilitiesDocument() {
        assertTrue(ServiceStatusChecker.osmOk(
                "<osm version=\"0.6\"><api><version minimum=\"0.6\" maximum=\"0.6\"/></api></osm>"));
    }

    @Test
    public void osmDownOnUnexpectedBody() {
        assertFalse(ServiceStatusChecker.osmOk("<html><body>502 Bad Gateway</body></html>"));
    }

    @Test
    public void nominatimOkOnStatusZero() {
        assertTrue(ServiceStatusChecker.nominatimOk("{\"status\":0,\"message\":\"OK\"}"));
    }

    @Test
    public void nominatimDownOnNonZeroStatus() {
        assertFalse(ServiceStatusChecker.nominatimOk("{\"status\":700,\"message\":\"Database connection failed\"}"));
    }

    @Test
    public void overpassOkOnRateLimitSection() {
        assertTrue(ServiceStatusChecker.overpassOk("Connected as: 12345\nRate_limit: 2\n2 slots available now."));
    }

    @Test
    public void overpassDownOnUnexpectedBody() {
        assertFalse(ServiceStatusChecker.overpassOk("<html><body>502 Bad Gateway</body></html>"));
    }

    @Test
    public void protomapsDisplayUrlNeverCarriesARealKey() {
        // The literal "..." placeholder, not BuildInfo.getProtomapsApiKey()'s actual value -
        // this is the form that ends up in the served /status document.
        assertEquals("https://api.protomaps.com/tiles/v4/0/0/0.mvt?key=...",
                ServiceStatusChecker.protomapsDisplayUrl());
    }

    @Test
    public void tracestrackTileLayersCoversTheDocumentedExamples() {
        Map<String, Object> layers = ServiceStatusChecker.tracestrackTileLayers();
        assertTrue(layers.containsKey("topo_en"));
        assertTrue(layers.containsKey("carto"));
        assertTrue(layers.containsKey("terrain-rgb"));
    }
}
