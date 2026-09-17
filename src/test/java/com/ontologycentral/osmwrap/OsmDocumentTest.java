package com.ontologycentral.osmwrap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.Test;

import com.ontologycentral.osmwrap.OsmDocument.Element;
import com.ontologycentral.osmwrap.OsmDocument.Member;

/**
 * Offline tests for {@link OsmDocument}, the single StAX parse behind every OSM API element
 * response (node, way {@code /full}, relation {@code /full}).
 *
 * <p>Fixtures are real OSM API responses fetched 2026-09-17:
 * {@code node-1675605507.xml} (Café Wanderer, Nürnberg), {@code way-32113829-full.xml}
 * (Palas of the Kaiserburg, a closed building outline, 11 nodes), {@code way-100-full.xml}
 * (a roundabout, 24 nodes), {@code relation-147-full.xml} (Tigris River multipolygon: 379
 * nodes, 3 ways, 1 relation).
 */
public class OsmDocumentTest {

    public static OsmDocument fixture(String resource, String type, String id) throws IOException {
        try (InputStream in = OsmDocumentTest.class.getResourceAsStream("/" + resource)) {
            assertNotNull("fixture on test classpath: " + resource, in);
            return OsmDocument.parse(in, type, id);
        }
    }

    public static OsmDocument parse(String xml, String type, String id) throws IOException {
        return OsmDocument.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), type, id);
    }

    /**
     * Synthetic relation /full in the shape the OSM API really emits: a member relation
     * before the primary one, and a node defined AFTER the way that references it.
     */
    public static final String RELATION_FULL = """
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

    /** Synthetic open way /full: nodes first, then the way. */
    public static final String WAY_FULL = """
            <osm version="0.6" generator="t">
             <node id="1" lat="1.0" lon="1.0"/>
             <node id="2" lat="1.0" lon="2.0"/>
             <node id="3" lat="2.0" lon="2.0"><tag k="barrier" v="gate"/></node>
             <way id="10" version="3" changeset="5" timestamp="2021-01-01T00:00:00Z" user="w" uid="2">
              <nd ref="1"/>
              <nd ref="2"/>
              <nd ref="3"/>
              <tag k="highway" v="path"/>
             </way>
            </osm>
            """;

    // --- node ---

    @Test
    public void nodeResponse() throws IOException {
        OsmDocument doc = fixture("node-1675605507.xml", "node", "1675605507");
        Element n = doc.primary();
        assertNotNull(n);
        assertEquals("node", n.type());
        assertEquals("1675605507", n.id());
        assertEquals("21", n.attributes().get("version"));
        assertEquals("Wanderer", n.tag("name"));
        assertTrue(n.members().isEmpty());
        assertEquals(1, doc.nodes().size());
        assertEquals(11.0741441, doc.nodes().get("1675605507")[0], 1e-12);
        assertEquals(49.4574744, doc.centroid()[1], 1e-12);
        assertTrue(doc.ways().isEmpty());
        assertTrue(doc.relations().isEmpty());
        String xml = doc.strippedXml();
        assertTrue("primary node kept with its tags", xml.contains("<node id=\"1675605507\"") && xml.contains("k=\"amenity\""));
        assertTrue("entity-escaped tag survives", xml.contains("&quot;Meister der Dürerzeit&quot;"));
    }

    // --- way ---

    @Test
    public void wayFullResponseKeepsOnlyTheWay() throws IOException {
        OsmDocument doc = fixture("way-32113829-full.xml", "way", "32113829");
        assertEquals(11, doc.nodes().size());
        assertEquals(List.of("32113829"), List.copyOf(doc.ways().keySet()));
        List<String> refs = doc.ways().get("32113829");
        assertEquals(12, refs.size());
        assertEquals("closed outline", refs.get(0), refs.get(refs.size() - 1));
        Element w = doc.primary();
        assertEquals("way", w.type());
        assertEquals("13", w.attributes().get("version"));
        String xml = doc.strippedXml();
        assertFalse("member nodes stripped", xml.contains("<node"));
        assertTrue("the way itself kept", xml.contains("<way id=\"32113829\""));
        assertTrue("its nd refs kept (harmless, suppressed by the stylesheet)", xml.contains("<nd ref="));
    }

    @Test
    public void wayRefsOrderAndTagsOnMemberNodesDoNotLeak() throws IOException {
        OsmDocument doc = parse(WAY_FULL, "way", "10");
        assertEquals(List.of("1", "2", "3"), doc.ways().get("10"));
        assertEquals(List.of("highway"), doc.primary().tags().stream().map(kv -> kv[0]).toList());
        assertFalse(doc.strippedXml().contains("barrier"));
        assertEquals(5.0 / 3, doc.centroid()[0], 1e-12);
    }

    // --- relation ---

    @Test
    public void parsesRealRelationFullResponse() throws IOException {
        OsmDocument doc = fixture("relation-147-full.xml", "relation", "147");
        assertEquals(379, doc.nodes().size());
        assertEquals(3, doc.ways().size());
        assertEquals(1, doc.relations().size());

        Element rel = doc.primary();
        assertNotNull(rel);
        assertEquals(rel, doc.relation("147"));
        assertEquals("13", rel.attributes().get("version"));
        assertEquals(3, rel.members().size());
        assertEquals("multipolygon", rel.tag("type"));
        assertEquals("Tigris River", rel.tag("name:en"));

        double[] c = doc.centroid();
        assertNotNull(c);
        assertTrue("centroid lon in Iraq: " + c[0], c[0] > 43 && c[0] < 46);
        assertTrue("centroid lat in Iraq: " + c[1], c[1] > 32 && c[1] < 35);
    }

    @Test
    public void strippedRelationDocumentKeepsRelationsOnly() throws IOException {
        OsmDocument doc = fixture("relation-147-full.xml", "relation", "147");
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
    public void forwardReferencesAndNestedRelations() throws IOException {
        OsmDocument doc = parse(RELATION_FULL, "relation", "42");
        assertEquals("node without coordinates is skipped", 4, doc.nodes().size());
        assertEquals(List.of("1", "2", "3", "1"), doc.ways().get("10"));
        assertEquals(List.of("99", "42"), List.copyOf(doc.relations().keySet()));
        Element rel = doc.primary();
        assertEquals(new Member("relation", "99", "subarea"), rel.members().get(2));
        assertEquals("A <b> \"c\"", rel.tag("name"));
        assertEquals("boundary", doc.relation("99").tag("type"));
        // centroid over all four nodes with coordinates: lon (1+2+2+9)/4, lat (1+1+2+9)/4
        assertEquals(3.5, doc.centroid()[0], 1e-12);
        assertEquals(3.25, doc.centroid()[1], 1e-12);
        assertFalse("member way tag stripped", doc.strippedXml().contains("highway"));
        assertTrue("member relation kept", doc.strippedXml().contains("<relation id=\"99\""));
    }

    // --- edge cases ---

    @Test
    public void missingPrimaryElement() throws IOException {
        OsmDocument doc = parse(RELATION_FULL, "relation", "7");
        assertNull(doc.primary());
        assertEquals("other relations still indexed", 2, doc.relations().size());
        OsmDocument doc2 = parse(WAY_FULL, "way", "11");
        assertNull(doc2.primary());
        assertTrue("non-primary ways are indexed but stripped", doc2.ways().containsKey("10"));
        assertFalse(doc2.strippedXml().contains("<way"));
    }

    @Test
    public void primaryTypeMustMatchNotJustTheId() throws IOException {
        // way 10 exists, but as a node id 10 does not
        OsmDocument doc = parse(WAY_FULL, "node", "10");
        assertNull(doc.primary());
    }

    @Test
    public void noNodesMeansNoCentroid() throws IOException {
        OsmDocument doc = parse("<osm><relation id=\"1\"><tag k=\"type\" v=\"route\"/></relation></osm>", "relation", "1");
        assertNull(doc.centroid());
        assertNotNull(doc.primary());
    }

    @Test(expected = IOException.class)
    public void malformedXmlIsAnIOException() throws IOException {
        parse("<osm><relation id=\"1\"><tag k=\"a\"", "relation", "1");
    }

    @Test
    public void externalEntitiesAreNotResolved() throws IOException {
        String xxe = "<!DOCTYPE osm [<!ENTITY x SYSTEM \"file:///etc/hostname\">]>"
                + "<osm><node id=\"1\" lat=\"1\" lon=\"1\"><tag k=\"n\" v=\"&x;\"/></node></osm>";
        try {
            OsmDocument doc = parse(xxe, "node", "1");
            String v = doc.primary().tag("n");
            assertTrue("entity must not expand to file content: " + v, v.isEmpty() || v.equals("&x;"));
        } catch (IOException expected) {
            // DTD support is off; rejecting the document outright is fine too
        }
    }
}
