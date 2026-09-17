package com.ontologycentral.osmwrap.geometry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.junit.Test;

import com.ontologycentral.osmwrap.OsmDocument;
import com.ontologycentral.osmwrap.OsmDocumentTest;

/**
 * {@link GeometryBuilder} is the single source of every geometry the wrapper emits. These
 * tests pin down which shape each element kind becomes and that all four serialisations of
 * that shape agree.
 */
public class GeometryBuilderTest {

    // --- node ---

    @Test
    public void nodeBecomesAPointInEveryFormat() throws IOException {
        OsmDocument doc = OsmDocumentTest.fixture("node-1675605507.xml", "node", "1675605507");
        Geometry g = GeometryBuilder.build(doc);
        assertTrue(g instanceof PointGeometry);
        assertEquals("{\"type\":\"Point\",\"coordinates\":[11.0741441,49.4574744]}", g.toGeoJSON());
        assertEquals("POINT(11.0741441 49.4574744)", g.toWKT());
        assertEquals("<gml:Point xmlns:gml=\"http://www.opengis.net/gml/3.2\" "
                + "srsName=\"http://www.opengis.net/def/crs/OGC/1.3/CRS84\">"
                + "<gml:pos>11.0741441 49.4574744</gml:pos></gml:Point>", g.toGML());
        assertEquals("<Point><coordinates>11.0741441,49.4574744,0</coordinates></Point>", g.toKML());
    }

    // --- way ---

    @Test
    public void closedWayBecomesAPolygon() throws IOException {
        OsmDocument doc = OsmDocumentTest.fixture("way-32113829-full.xml", "way", "32113829");
        Geometry g = GeometryBuilder.build(doc);
        assertTrue(g instanceof MultipolygonGeometry);
        MultipolygonGeometry mp = (MultipolygonGeometry) g;
        assertEquals(1, mp.getOuterRingCount());
        assertEquals(12, mp.getOuterRings().get(0).size());
        assertTrue(g.toGeoJSON().startsWith("{\"type\":\"Polygon\",\"coordinates\":[[["));
        assertTrue(g.toWKT().startsWith("POLYGON(("));
        assertTrue(g.toGML().startsWith("<gml:Polygon xmlns:gml="));
        assertTrue(g.toGML().contains("<gml:exterior><gml:LinearRing><gml:posList>"));
        assertTrue(g.toKML().startsWith("<Polygon><outerBoundaryIs>"));
    }

    @Test
    public void openWayBecomesALineString() throws IOException {
        OsmDocument doc = OsmDocumentTest.parse(OsmDocumentTest.WAY_FULL, "way", "10");
        Geometry g = GeometryBuilder.build(doc);
        assertTrue(g instanceof LineStringGeometry);
        assertEquals("{\"type\":\"LineString\",\"coordinates\":[[1.0,1.0],[2.0,1.0],[2.0,2.0]]}", g.toGeoJSON());
        assertEquals("LINESTRING(1.0 1.0,2.0 1.0,2.0 2.0)", g.toWKT());
        assertEquals("<gml:LineString xmlns:gml=\"http://www.opengis.net/gml/3.2\" "
                + "srsName=\"http://www.opengis.net/def/crs/OGC/1.3/CRS84\">"
                + "<gml:posList>1.0 1.0 2.0 1.0 2.0 2.0</gml:posList></gml:LineString>", g.toGML());
        assertEquals("<LineString><coordinates>1.0,1.0,0 2.0,1.0,0 2.0,2.0,0</coordinates></LineString>", g.toKML());
    }

    @Test
    public void wayWithUnresolvableNodesIsSkippedNotBroken() throws IOException {
        String xml = "<osm><node id=\"1\" lat=\"1\" lon=\"1\"/><way id=\"10\"><nd ref=\"1\"/><nd ref=\"404\"/></way></osm>";
        Geometry g = GeometryBuilder.build(OsmDocumentTest.parse(xml, "way", "10"));
        assertTrue("one resolvable node → Point", g instanceof PointGeometry);
        String none = "<osm><way id=\"10\"><nd ref=\"404\"/></way></osm>";
        assertSame(Geometry.EMPTY, GeometryBuilder.build(OsmDocumentTest.parse(none, "way", "10")));
    }

    // --- relation ---

    @Test
    public void multipolygonRelationFromInlineDataWithoutUpstreamCalls() throws IOException {
        OsmDocument doc = OsmDocumentTest.fixture("relation-147-full.xml", "relation", "147");
        Geometry g = GeometryBuilder.build(doc);
        assertTrue(g instanceof MultipolygonGeometry);
        assertTrue("river area becomes a Polygon: " + g.toGeoJSON().substring(0, 40),
                g.toGeoJSON().startsWith("{\"type\":\"Polygon\""));
        assertTrue(g.toGML().startsWith("<gml:Polygon "));
        assertTrue(g.toWKT().startsWith("POLYGON(("));
    }

    @Test
    public void relationGeometryUsesOnlyThePrimaryRelationsMembers() throws IOException {
        OsmDocument doc = OsmDocumentTest.parse(OsmDocumentTest.RELATION_FULL, "relation", "42");
        Geometry g = GeometryBuilder.build(doc);
        assertEquals("closed way ring resolved via nodes defined after the way",
                "{\"type\":\"Polygon\",\"coordinates\":[[[1.0,1.0],[2.0,1.0],[2.0,2.0],[1.0,1.0]]]}", g.toGeoJSON());
        // relation 99 (a member) has no members of its own → empty, not Germany's shape
        assertSame(Geometry.EMPTY, GeometryBuilder.build(OsmDocumentTest.parse(OsmDocumentTest.RELATION_FULL, "relation", "99")));
    }

    @Test
    public void routeRelationFallsBackToLineStringOverMembers() throws IOException {
        String route = OsmDocumentTest.RELATION_FULL.replace("<tag k=\"type\" v=\"multipolygon\"/>", "<tag k=\"type\" v=\"route\"/>");
        Geometry g = GeometryBuilder.build(OsmDocumentTest.parse(route, "relation", "42"));
        // way 10's four nodes in order, then the label node member
        assertEquals("{\"type\":\"LineString\",\"coordinates\":[[1.0,1.0],[2.0,1.0],[2.0,2.0],[1.0,1.0],[9.0,9.0]]}", g.toGeoJSON());
        assertTrue(g.toGML().startsWith("<gml:LineString "));
    }

    @Test
    public void multipolygonRingWithThreePointsIsClosedAutomatically() throws IOException {
        // Ring assembly closes an open outer segment of >= 3 distinct points back to its start.
        String open = OsmDocumentTest.RELATION_FULL.replace("  <nd ref=\"1\"/>\n  <tag k=\"highway\"", "  <tag k=\"highway\"");
        Geometry g = GeometryBuilder.build(OsmDocumentTest.parse(open, "relation", "42"));
        assertEquals("{\"type\":\"Polygon\",\"coordinates\":[[[1.0,1.0],[2.0,1.0],[2.0,2.0],[1.0,1.0]]]}", g.toGeoJSON());
    }

    @Test
    public void multipolygonWithoutAssemblableRingFallsBackToLineString() throws IOException {
        // Only two points on the sole outer way: no ring can be built → members flattened.
        String open = OsmDocumentTest.RELATION_FULL
                .replace("  <nd ref=\"3\"/>\n  <nd ref=\"1\"/>\n  <tag k=\"highway\"", "  <tag k=\"highway\"");
        Geometry g = GeometryBuilder.build(OsmDocumentTest.parse(open, "relation", "42"));
        assertTrue("not closable → members flattened: " + g.toGeoJSON(), g instanceof LineStringGeometry);
        assertEquals("{\"type\":\"LineString\",\"coordinates\":[[1.0,1.0],[2.0,1.0],[9.0,9.0]]}", g.toGeoJSON());
    }

    @Test
    public void missingPrimaryIsEmpty() throws IOException {
        Geometry g = GeometryBuilder.build(OsmDocumentTest.parse(OsmDocumentTest.RELATION_FULL, "relation", "7"));
        assertSame(Geometry.EMPTY, g);
        assertTrue(g.isEmpty());
        assertEquals("{\"type\":\"GeometryCollection\",\"geometries\":[]}", g.toGeoJSON());
        assertEquals("GEOMETRYCOLLECTION()", g.toWKT());
        assertNull(g.toGML());
        assertEquals("", g.toKML());
        assertFalse(new PointGeometry(0, 0).isEmpty());
    }

    // --- fromCoordinates rules ---

    @Test
    public void fromCoordinatesRules() {
        java.util.List<double[]> tri = java.util.List.of(new double[]{0, 0}, new double[]{1, 0}, new double[]{1, 1}, new double[]{0, 0});
        assertTrue("4 coords, closed → polygon", GeometryBuilder.fromCoordinates(tri) instanceof MultipolygonGeometry);
        java.util.List<double[]> tooShort = java.util.List.of(new double[]{0, 0}, new double[]{1, 0}, new double[]{0, 0});
        assertTrue("3 coords → line even if closed", GeometryBuilder.fromCoordinates(tooShort) instanceof LineStringGeometry);
        assertTrue(GeometryBuilder.fromCoordinates(java.util.List.of(new double[]{0, 0})) instanceof PointGeometry);
        assertSame(Geometry.EMPTY, GeometryBuilder.fromCoordinates(java.util.List.of()));
    }
}
