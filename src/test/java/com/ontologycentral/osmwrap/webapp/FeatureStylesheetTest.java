package com.ontologycentral.osmwrap.webapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.junit.Test;

import com.ontologycentral.osmwrap.OsmDocument;
import com.ontologycentral.osmwrap.OsmDocumentTest;
import com.ontologycentral.osmwrap.OsmElement;
import com.ontologycentral.osmwrap.UpstreamCache.Fetched;
import com.ontologycentral.osmwrap.geometry.GeometryBuilder;

/**
 * Runs the real {@code feature.xsl} and {@code feature-gml.xsl} through Saxon over parsed
 * fixtures, with the parameters {@link FeatureServlet#setFeatureParameters} sets. No network:
 * an {@link OsmElement} is assembled from the fixture bytes directly.
 */
public class FeatureStylesheetTest {

    private static OsmElement element(String xml, String type, String id) throws IOException {
        OsmDocument doc = OsmDocumentTest.parse(xml, type, id);
        return new OsmElement(type, id, OsmElement.upstreamUrl(type, id),
                new Fetched(xml.getBytes(StandardCharsets.UTF_8), xml.length()), doc, GeometryBuilder.build(doc));
    }

    private static OsmElement fixture(String resource, String type, String id) throws IOException {
        OsmDocument doc = OsmDocumentTest.fixture(resource, type, id);
        return new OsmElement(type, id, OsmElement.upstreamUrl(type, id),
                new Fetched(new byte[0], -1), doc, GeometryBuilder.build(doc));
    }

    private static String transform(String stylesheet, OsmElement e) throws Exception {
        File xsl = new File("src/main/webapp/WEB-INF/xsl/" + stylesheet);
        assertTrue("stylesheet exists: " + xsl, xsl.isFile());
        TransformerFactory tf = TransformerFactory.newInstance("net.sf.saxon.TransformerFactoryImpl", null);
        Templates tmpl = tf.newTemplates(new StreamSource(xsl));
        Transformer t = tmpl.newTransformer();
        FeatureServlet.setFeatureParameters(t, e);
        StringWriter out = new StringWriter();
        t.transform(new StreamSource(new StringReader(e.doc().strippedXml())), new StreamResult(out));
        return out.toString();
    }

    private static int count(String s, String needle) {
        return s.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    // --- Turtle ---

    @Test
    public void nodeTurtle() throws Exception {
        String ttl = transform("feature.xsl", fixture("node-1675605507.xml", "node", "1675605507"));
        assertTrue(ttl.contains("</osm/node/1675605507#id> a spatial:Feature, osm:Node ;"));
        assertTrue("node keeps its own lat/lon on the feature", ttl.contains("    geo:lat \"49.4574744\" ;"));
        assertTrue(ttl.contains("prov:hadPrimarySource <https://api.openstreetmap.org/api/0.6/node/1675605507/21>"));
        assertTrue(ttl.contains("geom:geometry </osm/node/1675605507#geo>"));
        assertTrue(ttl.contains("</osm/node/1675605507#geo> a geom:Geometry ;"));
        assertTrue("same #geo shape for every type", ttl.contains("    foaf:page </geo/osm/node/1675605507> ;"));
        assertTrue(ttl.contains("locn:geometry \"<gml:Point "));
        assertTrue(ttl.contains("</tag/amenity> \"cafe\""));
        assertTrue("wikidata-style keys are plain tags here", ttl.contains("</tag/name:etymology:wikidata> \"Q1462729\""));
        assertTrue(ttl.contains("</changeset/182366113> a prov:Activity"));
        assertFalse("dcat:byteSize absent without Content-Length", ttl.contains("dcat:byteSize"));
    }

    @Test
    public void wayTurtle() throws Exception {
        String ttl = transform("feature.xsl", fixture("way-32113829-full.xml", "way", "32113829"));
        assertTrue(ttl.contains("</osm/way/32113829#id> a spatial:Feature, osm:Way ;"));
        assertFalse("ways carry no lat/lon on the feature itself", ttl.contains("#id> a spatial:Feature, osm:Way ;\n    dcterms:identifier \"32113829\" ;\n    geo:lat"));
        assertTrue(ttl.contains("</osm/way/32113829#geo> a geom:Geometry ;"));
        assertTrue("centroid of the way's nodes", ttl.contains("    geo:lat \"49.4"));
        assertTrue("closed way → Polygon literal", ttl.contains("locn:geometry \"<gml:Polygon "));
        assertEquals("exactly one geometry literal", 1, count(ttl, "locn:geometry"));
        assertFalse("nd refs are not emitted", ttl.contains("rdfs:seeAlso </node/"));
        assertFalse("member nodes are not features", ttl.contains("osm:Node"));
        assertTrue(ttl.contains("</tag/building> \""));
    }

    @Test
    public void openWayTurtleHasLineString() throws Exception {
        OsmElement e = element(OsmDocumentTest.WAY_FULL, "way", "10");
        String ttl = transform("feature.xsl", e);
        assertTrue(ttl.contains("locn:geometry \"<gml:LineString "));
        assertTrue(ttl.contains("dcat:byteSize \"" + OsmDocumentTest.WAY_FULL.length() + "\"^^xsd:decimal"));
        assertTrue(ttl.contains("prov:hadPrimarySource <https://api.openstreetmap.org/api/0.6/way/10/3>"));
    }

    @Test
    public void relationTurtle() throws Exception {
        String ttl = transform("feature.xsl", element(OsmDocumentTest.RELATION_FULL, "relation", "42"));
        assertTrue(ttl.contains("</osm/relation/42#id> a spatial:Feature, osm:Relation"));
        assertTrue("primary source is the versioned API URL of the requested relation, not the first one",
                ttl.contains("prov:hadPrimarySource <https://api.openstreetmap.org/api/0.6/relation/42/7>"));
        assertTrue(ttl.contains("geom:geometry </osm/relation/42#geo>"));
        assertTrue(ttl.contains("</osm/relation/42#geo> a geom:Geometry ;"));
        assertTrue(ttl.contains("geo:lat \"3.25\""));
        assertTrue(ttl.contains("geo:long \"3.5\""));
        assertTrue("embedded GML literal", ttl.contains("locn:geometry \"") && ttl.contains("gml:Polygon"));
        assertTrue(ttl.contains("rdfs:seeAlso </way/10>"));
        assertTrue(ttl.contains("rdfs:seeAlso </relation/99>"));
        assertTrue("root @generator survives the strip", ttl.contains("rdfs:comment \"test & co\""));
        assertTrue("tag value with escapes", ttl.contains("</tag/name> \"A <b> \\\"c\\\"\""));
        assertTrue("member relation is still emitted as its own feature",
                ttl.contains("</osm/relation/99#id> a spatial:Feature"));
        assertFalse("but without the primary relation's geometry", ttl.contains("</osm/relation/99#geo>"));
        assertEquals("GML literal emitted exactly once", 1, count(ttl, "locn:geometry"));
        assertFalse("member way tags do not leak", ttl.contains("highway"));
    }

    @Test
    public void relationTurtleWithoutGeometryHasNoGeoBlock() throws Exception {
        String xml = "<osm><relation id=\"1\" version=\"2\"><tag k=\"type\" v=\"route\"/><member type=\"way\" ref=\"5\" role=\"\"/></relation></osm>";
        String ttl = transform("feature.xsl", element(xml, "relation", "1"));
        assertFalse(ttl.contains("geom:geometry"));
        assertFalse(ttl.contains("#geo>"));
        assertTrue(ttl.contains("rdfs:seeAlso </way/5>"));
    }

    @Test
    public void labelsAndSameAsLinksAreUniformAcrossTypes() throws Exception {
        String xml = "<osm><node id=\"1\" lat=\"1\" lon=\"2\"><tag k=\"name:en\" v=\"X\"/><tag k=\"wikidata\" v=\"Q1\"/><tag k=\"wikipedia\" v=\"de:Y Z\"/></node></osm>";
        String ttl = transform("feature.xsl", element(xml, "node", "1"));
        assertTrue("name:en gives rdfs:label on nodes too", ttl.contains("rdfs:label \"X\""));
        assertTrue(ttl.contains("owl:sameAs <http://www.wikidata.org/entity/Q1>"));
        assertTrue(ttl.contains("foaf:page <http://de.wikipedia.org/wiki/Y%20Z>"));
        assertTrue(ttl.contains("owl:sameAs <http://de.dbpedia.org/resource/Y%20Z>"));
    }

    // --- GML ---

    @Test
    public void nodeGml() throws Exception {
        String gml = transform("feature-gml.xsl", fixture("node-1675605507.xml", "node", "1675605507"));
        assertTrue(gml.contains("<osm:node") && gml.contains("gml:id=\"node.1675605507\""));
        assertTrue(gml.contains("<gml:pos>11.0741441 49.4574744</gml:pos>"));
        assertTrue(gml.contains("<osm:tag key=\"amenity\" value=\"cafe\"/>"));
    }

    @Test
    public void wayGml() throws Exception {
        String gml = transform("feature-gml.xsl", fixture("way-32113829-full.xml", "way", "32113829"));
        assertTrue(gml.contains("gml:id=\"way.32113829\""));
        assertTrue("real polygon, parsed from the parameter", gml.contains("<gml:Polygon") && gml.contains("<gml:posList>"));
        assertEquals("one geometry", 1, count(gml, "<osm:geometry>"));
        assertFalse(gml.contains("<osm:node"));
    }

    @Test
    public void relationGml() throws Exception {
        String gml = transform("feature-gml.xsl", element(OsmDocumentTest.RELATION_FULL, "relation", "42"));
        assertTrue(gml.contains("gml:id=\"relation.42\""));
        assertFalse("member relation is not a feature in the GML collection", gml.contains("relation.99"));
        assertTrue("relation gets its real shape, not a centroid point", gml.contains("<gml:Polygon"));
        assertFalse(gml.contains("<gml:Point"));
        assertTrue(gml.contains("<osm:member type=\"way\" ref=\"10\" role=\"outer\"/>"));
        assertTrue(gml.contains("numberReturned=\"1\""));
    }

    @Test
    public void gmlWithoutGeometryOmitsTheElement() throws Exception {
        String xml = "<osm><relation id=\"1\"><tag k=\"type\" v=\"route\"/></relation></osm>";
        String gml = transform("feature-gml.xsl", element(xml, "relation", "1"));
        assertFalse(gml.contains("<osm:geometry"));
        assertTrue(gml.contains("gml:id=\"relation.1\""));
    }
}
