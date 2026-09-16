package com.ontologycentral.osmwrap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.junit.Test;

import com.ontologycentral.osmwrap.geometry.MultipolygonHandler;

/**
 * Offline tests for {@link OsmFullDocument}, the StAX parse of a {@code /relation/{id}/full}
 * response, and for the relation output paths that consume it.
 *
 * <p>{@code relation-147-full.xml} is the real OSM API {@code /full} response for relation
 * 147 (Tigris River multipolygon, fetched 2026-09-17): 379 nodes, 3 ways, 1 relation.
 */
public class OsmFullDocumentTest {

    private static OsmFullDocument fixture() throws IOException {
        try (InputStream in = OsmFullDocumentTest.class.getResourceAsStream("/relation-147-full.xml")) {
            assertNotNull("fixture on test classpath", in);
            return OsmFullDocument.parse(in);
        }
    }

    private static OsmFullDocument parse(String xml) throws IOException {
        return OsmFullDocument.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Synthetic /full response in the shape the OSM API really emits: a member relation
     * before the primary one, and a node defined AFTER the way that references it.
     */
    private static final String SYNTHETIC = """
            <?xml version="1.0" encoding="UTF-8"?>
            <osm version="0.6" generator="test &amp; co">
             <way id="10" version="1">
              <nd ref="1"/>
              <nd ref="2"/>
              <nd ref="3"/>
              <nd ref="1"/>
              <tag k="highway" v="residential"/>
             </way>
             <node id="1" lat="1.0" lon="1.0"/>
             <node id="2" lat="1.0" lon="2.0"/>
             <node id="3" lat="2.0" lon="2.0"/>
             <node id="4" lat="9.0" lon="9.0"><tag k="place" v="city"/></node>
             <node id="5" visible="false"/>
             <relation id="99" version="1">
              <tag k="type" v="boundary"/>
             </relation>
             <relation id="42" version="7" changeset="123" timestamp="2020-01-01T00:00:00Z" user="u" uid="1">
              <member type="way" ref="10" role="outer"/>
              <member type="node" ref="4" role="label"/>
              <member type="relation" ref="99" role="subarea"/>
              <tag k="type" v="multipolygon"/>
              <tag k="name" v="A &lt;b&gt; &quot;c&quot;"/>
             </relation>
            </osm>
            """;

    @Test
    public void parsesRealFullResponse() throws IOException {
        OsmFullDocument doc = fixture();
        assertEquals(379, doc.nodes().size());
        assertEquals(3, doc.ways().size());
        assertEquals(1, doc.relations().size());

        OsmFullDocument.Relation rel = doc.relation("147");
        assertNotNull(rel);
        assertEquals("13", rel.attributes().get("version"));
        assertEquals(3, rel.members().size());
        assertEquals("multipolygon", rel.tag("type"));
        assertEquals("Tigris River", rel.tag("name:en"));
        assertTrue(MultipolygonHandler.isMultipolygon(rel));

        double[] c = doc.centroid();
        assertNotNull(c);
        assertTrue("centroid lon in Iraq: " + c[0], c[0] > 43 && c[0] < 46);
        assertTrue("centroid lat in Iraq: " + c[1], c[1] > 32 && c[1] < 35);
    }

    @Test
    public void strippedDocumentKeepsRelationsOnly() throws IOException {
        OsmFullDocument doc = fixture();
        String xml = doc.strippedXml();
        assertFalse("no <node> in stripped doc", xml.contains("<node"));
        assertFalse("no <way> in stripped doc", xml.contains("<way"));
        assertFalse("no <nd> in stripped doc", xml.contains("<nd"));
        assertTrue(xml.contains("<relation id=\"147\""));
        assertTrue("members kept", xml.contains("<member type=\"way\""));
        assertTrue("root attributes kept", xml.contains("generator=\"openstreetmap-cgimap"));
        assertTrue("non-ASCII tag values survive", xml.contains("نهر دجلة"));
    }

    @Test
    public void multipolygonGeometryFromInlineDataWithoutUpstreamCalls() throws IOException {
        OsmFullDocument doc = fixture();
        String json = GeoJsonConverter.relationGeometryJson(doc, "147");
        assertTrue("river area becomes a Polygon: " + json.substring(0, 60),
                json.startsWith("{\"type\":\"Polygon\""));
        String gml = GeoJsonConverter.relationGeometryGml(doc, "147");
        assertNotNull(gml);
        assertTrue(gml.startsWith("<gml:Polygon "));
        assertTrue(gml.contains("<gml:posList>"));
    }

    @Test
    public void geoJsonPropertiesComeFromThePrimaryRelationOnly() throws IOException {
        OsmFullDocument doc = parse(SYNTHETIC);
        OsmFullDocument.Relation rel = doc.relation("42");
        String geometry = GeoJsonConverter.relationGeometryJson(doc, "42");
        String feature = GeoJsonConverter.osmFeatureToGeoJson(rel.tags(), "relation", "42", geometry, "/osm");
        assertTrue(feature.contains("\"/tag/name\":\"A <b> \\\"c\\\"\""));
        assertTrue(feature.contains("\"/tag/type\":\"multipolygon\""));
        assertFalse("member way tag must not leak into relation properties", feature.contains("highway"));
        assertFalse("member node tag must not leak into relation properties", feature.contains("place"));
        assertTrue("closed way ring resolved via nodes defined after the way: " + feature,
                feature.contains("\"type\":\"Polygon\",\"coordinates\":[[[1.0,1.0],[2.0,1.0],[2.0,2.0],[1.0,1.0]]]"));
    }

    @Test
    public void forwardReferencesAndNestedRelations() throws IOException {
        OsmFullDocument doc = parse(SYNTHETIC);
        assertEquals("node without coordinates is skipped", 4, doc.nodes().size());
        assertEquals(List.of("1", "2", "3", "1"), doc.ways().get("10"));
        assertEquals(List.of("99", "42"), List.copyOf(doc.relations().keySet()));
        OsmFullDocument.Relation rel = doc.relation("42");
        assertEquals(new OsmFullDocument.Member("relation", "99", "subarea"), rel.members().get(2));
        assertEquals("A <b> \"c\"", rel.tag("name"));
        assertEquals("boundary", doc.relation("99").tag("type"));
        // centroid over all four nodes with coordinates: lon (1+2+2+9)/4, lat (1+1+2+9)/4
        assertEquals(3.5, doc.centroid()[0], 1e-12);
        assertEquals(3.25, doc.centroid()[1], 1e-12);
    }

    @Test
    public void routeRelationFallsBackToLineString() throws IOException {
        String route = SYNTHETIC.replace("<tag k=\"type\" v=\"multipolygon\"/>", "<tag k=\"type\" v=\"route\"/>");
        OsmFullDocument doc = parse(route);
        String json = GeoJsonConverter.relationGeometryJson(doc, "42");
        // way 10's four nodes in order, then the label node member
        assertEquals("{\"type\":\"LineString\",\"coordinates\":[[1.0,1.0],[2.0,1.0],[2.0,2.0],[1.0,1.0],[9.0,9.0]]}", json);
        assertTrue(GeoJsonConverter.relationGeometryGml(doc, "42").startsWith("<gml:LineString "));
    }

    @Test
    public void missingPrimaryRelationIsHandled() throws IOException {
        OsmFullDocument doc = parse(SYNTHETIC);
        assertNull(doc.relation("7"));
        assertEquals("{\"type\":\"GeometryCollection\",\"geometries\":[]}",
                GeoJsonConverter.relationGeometryJson(doc, "7"));
        assertNull(GeoJsonConverter.relationGeometryGml(doc, "7"));
    }

    @Test
    public void noNodesMeansNoCentroid() throws IOException {
        OsmFullDocument doc = parse("<osm><relation id=\"1\"><tag k=\"type\" v=\"route\"/></relation></osm>");
        assertNull(doc.centroid());
    }

    @Test(expected = IOException.class)
    public void malformedXmlIsAnIOException() throws IOException {
        parse("<osm><relation id=\"1\"><tag k=\"a\"");
    }

    @Test
    public void relationXslRunsOverStrippedDocumentWithCentroidParams() throws Exception {
        OsmFullDocument doc = parse(SYNTHETIC);
        Transformer t = template("relation.xsl").newTransformer();
        t.setParameter("source-prefix", "/osm");
        t.setParameter("upstream-url", "https://api.openstreetmap.org/api/0.6/relation/42/full");
        t.setParameter("element-id", "42");
        t.setParameter("centroid-lon", Double.toString(doc.centroid()[0]));
        t.setParameter("centroid-lat", Double.toString(doc.centroid()[1]));
        t.setParameter("geometry-gml", GeoJsonConverter.relationGeometryGml(doc, "42"));
        StringWriter out = new StringWriter();
        t.transform(new StreamSource(new StringReader(doc.strippedXml())), new StreamResult(out));
        String ttl = out.toString();

        assertTrue(ttl.contains("</osm/relation/42#id> a spatial:Feature, osm:Relation"));
        assertTrue("primary source is the versioned API URL",
                ttl.contains("prov:hadPrimarySource <https://api.openstreetmap.org/api/0.6/relation/42/7>"));
        assertTrue(ttl.contains("geom:geometry </relation/42#geo>"));
        assertTrue(ttl.contains("geo:lat \"3.25\""));
        assertTrue(ttl.contains("geo:long \"3.5\""));
        assertTrue("embedded GML literal", ttl.contains("locn:geometry \"") && ttl.contains("gml:Polygon"));
        assertTrue(ttl.contains("rdfs:seeAlso </way/10>"));
        assertTrue(ttl.contains("rdfs:seeAlso </relation/99>"));
        assertTrue("root @generator survives the strip", ttl.contains("rdfs:comment \"test & co\""));
        assertTrue("tag value with escapes", ttl.contains("</tag/name> \"A <b> \\\"c\\\"\""));
        assertTrue("member relation is still emitted as its own feature",
                ttl.contains("</osm/relation/99#id> a spatial:Feature"));
        assertFalse("but without the primary relation's geometry", ttl.contains("</relation/99#geo>"));
        assertEquals("GML literal emitted exactly once", 1, ttl.split("locn:geometry", -1).length - 1);
    }

    @Test
    public void relationGmlXslRunsOverStrippedDocumentWithCentroidParams() throws Exception {
        OsmFullDocument doc = parse(SYNTHETIC);
        Transformer t = template("relation-gml.xsl").newTransformer();
        t.setParameter("element-id", "42");
        t.setParameter("centroid-lon", Double.toString(doc.centroid()[0]));
        t.setParameter("centroid-lat", Double.toString(doc.centroid()[1]));
        StringWriter out = new StringWriter();
        t.transform(new StreamSource(new StringReader(doc.strippedXml())), new StreamResult(out));
        String gml = out.toString();
        assertTrue(gml.contains("gml:id=\"relation.42\""));
        assertEquals("point geometry only for the requested relation", 1, gml.split("<gml:pos>", -1).length - 1);
        assertTrue(gml.contains("<gml:pos>3.5 3.25</gml:pos>"));
        assertTrue(gml.contains("<osm:member type=\"way\" ref=\"10\" role=\"outer\"/>"));
    }

    private static Templates template(String name) throws Exception {
        File xsl = new File("src/main/webapp/WEB-INF/xsl/" + name);
        assertTrue("stylesheet exists: " + xsl, xsl.isFile());
        TransformerFactory tf = TransformerFactory.newInstance("net.sf.saxon.TransformerFactoryImpl", null);
        return tf.newTemplates(new StreamSource(xsl));
    }
}
