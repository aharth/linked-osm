package com.ontologycentral.osmwrap.webapp;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import com.ontologycentral.osmwrap.OsmDocument;
import com.ontologycentral.osmwrap.UrlBuilder;
import com.ontologycentral.osmwrap.geometry.GeometryBuilder;

/**
 * /geo/overpass reads Overpass's OSM XML with the parser and geometry builder of /geo/osm.
 * Before, it scraped Overpass JSON with regexes that expected [lon,lat] pairs where
 * Overpass writes {"lat":…,"lon":…}, and answered "LINESTRING( { "lat": … } … )".
 */
public class GeometryOverpassTest {

    @Test
    public void queryAsksForTheFullElementAsXml() {
        assertEquals("[out:xml];way(32949878);(._;>;);out body;",
                UrlBuilder.buildOverpassGeometryQuery("way", "32949878"));
    }

    @Test
    public void aClosedBuildingWayIsAPolygon() throws Exception {
        // the shape Overpass answers for that query (nodes first, then the way)
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><osm version=\"0.6\" generator=\"Overpass API\">"
                + "<node id=\"1\" lat=\"50.9402213\" lon=\"6.9559305\"/>"
                + "<node id=\"2\" lat=\"50.9402334\" lon=\"6.9559953\"/>"
                + "<node id=\"3\" lat=\"50.9401432\" lon=\"6.9559837\"/>"
                + "<way id=\"32949878\"><nd ref=\"1\"/><nd ref=\"2\"/><nd ref=\"3\"/><nd ref=\"1\"/>"
                + "<tag k=\"building\" v=\"yes\"/></way></osm>";
        OsmDocument doc = OsmDocument.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "way", "32949878");
        assertEquals("POLYGON((6.9559305 50.9402213,6.9559953 50.9402334,6.9559837 50.9401432,6.9559305 50.9402213))",
                GeometryBuilder.build(doc).toWKT());
    }
}
