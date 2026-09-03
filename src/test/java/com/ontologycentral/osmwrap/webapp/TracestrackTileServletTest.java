package com.ontologycentral.osmwrap.webapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** URL-building logic for the three Tracestrack tile shapes. */
public class TracestrackTileServletTest {

    @Test
    public void rasterTile() {
        assertEquals("https://tile.tracestrack.com/en/10/525/336.webp?key=K",
                TracestrackTileServlet.upstreamUrl(new String[] {"en", "10", "525", "336.webp"}, "K", null));
    }

    @Test
    public void rasterTileWithRetina() {
        assertEquals("https://tile.tracestrack.com/topo_en/10/525/336@2x.webp?key=K",
                TracestrackTileServlet.upstreamUrl(
                        new String[] {"topo_en", "10", "525", "336@2x.webp"}, "K", null));
    }

    @Test
    public void rasterTileWithStyle() {
        assertEquals("https://tile.tracestrack.com/_/10/525/336.png?key=K&style=contrast%2B",
                TracestrackTileServlet.upstreamUrl(new String[] {"_", "10", "525", "336.png"}, "K", "contrast+"));
    }

    @Test
    public void vectorTile() {
        assertEquals("https://tile.tracestrack.com/vt/carto/10/525/336.pbf?key=K",
                TracestrackTileServlet.upstreamUrl(new String[] {"vt", "carto", "10", "525", "336.pbf"}, "K", null));
    }

    @Test
    public void terrainRgbTile() {
        assertEquals("https://tile.tracestrack.com/terrain-rgb/10/525/336.webp?key=K",
                TracestrackTileServlet.upstreamUrl(new String[] {"terrain-rgb", "10", "525", "336.webp"}, "K", null));
    }

    @Test
    public void rejectsNonNumericZoom() {
        assertNull(TracestrackTileServlet.upstreamUrl(new String[] {"en", "z", "525", "336.webp"}, "K", null));
    }

    @Test
    public void rejectsBadExtension() {
        assertNull(TracestrackTileServlet.upstreamUrl(new String[] {"en", "10", "525", "336.jpg"}, "K", null));
    }

    @Test
    public void rejectsVectorWithBadExtension() {
        assertNull(TracestrackTileServlet.upstreamUrl(
                new String[] {"vt", "carto", "10", "525", "336.png"}, "K", null));
    }

    @Test
    public void rejectsWrongSegmentCount() {
        assertNull(TracestrackTileServlet.upstreamUrl(new String[] {"en", "10", "525"}, "K", null));
    }
}
