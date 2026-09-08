package com.ontologycentral.osmwrap.webapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** URL-building and geometry math for the family-standard {@code /tile} endpoint. */
public class TileServletTest {

    @Test
    public void rasterUrlWithKey() {
        TileServlet.Tile t = new TileServlet.Tile("tracestrack", "topo", 10, 525, 336, null, null);
        assertEquals("https://tile.tracestrack.com/topo/10/525/336.webp?key=K",
                TileServlet.upstreamUrl(t, "webp", "K"));
    }

    @Test
    public void rasterUrlWithStyle() {
        TileServlet.Tile t = new TileServlet.Tile("tracestrack", "topo_en", 10, 525, 336, "dark2", null);
        assertEquals("https://tile.tracestrack.com/topo_en/10/525/336.png?key=K&style=dark2",
                TileServlet.upstreamUrl(t, "png", "K"));
    }

    @Test
    public void cacheKeyFormOmitsCredentialEntirely() {
        TileServlet.Tile t = new TileServlet.Tile("tracestrack", "topo", 10, 525, 336, null, null);
        String cacheKey = TileServlet.upstreamUrl(t, "webp", null);
        assertEquals("https://tile.tracestrack.com/topo/10/525/336.webp", cacheKey);
        assertFalse("provenance/cache-key URL must never carry the upstream key",
                cacheKey.contains("key="));
    }

    @Test
    public void bbox4326CoversWholeWorldAtZoomZero() {
        double[] b = TileServlet.bbox4326(0, 0, 0);
        assertEquals(-180.0, b[0], 1e-9);
        assertEquals(180.0, b[2], 1e-9);
        assertTrue(b[3] > 85 && b[3] < 86); // Web-Mercator's north limit
        assertTrue(b[1] < -85 && b[1] > -86);
    }

    @Test
    public void resolutionShrinksTowardEquatorFromPole() {
        // Same zoom, tile nearer the pole (smaller y) has coarser ground resolution
        // per pixel due to the cos(lat) Mercator scale factor.
        double resNearPole = TileServlet.resolution(4, 0);
        double resNearEquator = TileServlet.resolution(4, 8);
        assertTrue(resNearPole < resNearEquator);
    }
}
