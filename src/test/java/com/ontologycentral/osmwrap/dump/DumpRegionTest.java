package com.ontologycentral.osmwrap.dump;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.ontologycentral.osmwrap.OsmDocument;
import com.ontologycentral.osmwrap.geometry.Geometry;
import com.ontologycentral.osmwrap.geometry.GeometryBuilder;

/**
 * The dump path end to end on a real extract: {@code src/test/resources/dump/kaiserburg.osm.pbf}
 * is Geofabrik's bayern-261103 cut to the Nuremberg Kaiserburg
 * ({@code osmium extract -b 11.0735,49.4565,11.0775,49.4590 -s complete_ways}) and run through
 * {@code osmium add-locations-to-ways --keep-member-nodes -f pbf,pbf_compression=zlib}, the
 * same step as {@code refresh.sh}. Many of its relations reach outside the box, which makes
 * them incomplete.
 */
public class DumpRegionTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static Path dir;
    private static DumpIndexer.Result result;
    private static DumpRegion region;

    @BeforeClass
    public static void build() throws IOException {
        dir = Files.createTempDirectory("dump-test");
        try (InputStream in = DumpRegionTest.class.getResourceAsStream("/dump/kaiserburg.osm.pbf")) {
            assertNotNull("fixture missing", in);
            Files.copy(in, dir.resolve(DumpFiles.DATA), StandardCopyOption.REPLACE_EXISTING);
        }
        result = DumpIndexer.index(dir, "kaiserburg", "kaiserburg.osm.pbf");
        Files.move(dir.resolve(DumpFiles.MANIFEST_PENDING), dir.resolve(DumpFiles.MANIFEST));
        region = DumpRegion.open("kaiserburg", dir, 1L << 20);
    }

    @AfterClass
    public static void cleanup() throws IOException {
        if (region != null) {
            region.close();
        }
        try (var s = Files.list(dir)) {
            for (Path p : s.toList()) {
                Files.delete(p);
            }
        }
        Files.delete(dir);
    }

    @Test
    public void indexCountsMatchOsmium() {
        // osmium fileinfo -e on the fixture: 475 nodes, 437 ways, 29 relations
        assertEquals(475, result.nodes());
        assertEquals(437, result.ways());
        assertEquals(29, result.relations());
        assertEquals(0, result.incompleteWays());
        assertTrue("relations reaching outside the box are incomplete", result.incompleteRelations() > 0);
    }

    @Test
    public void manifestHasEveryKeyAndTheReplicationState() throws IOException {
        Map<String, String> m = DumpFiles.readManifest(dir.resolve(DumpFiles.MANIFEST));
        for (String k : DumpFiles.MANIFEST_KEYS) {
            assertTrue("MANIFEST lacks " + k, m.containsKey(k));
        }
        assertEquals("1", m.get("format"));
        assertEquals("kaiserburg.osm.pbf", m.get("source"));
        // osmium extract doesn't carry the replication state over, so the fixture has none
        // (the full Bavaria extract's MANIFEST does: 2026-11-03T21:24:23Z, sequence 4954)
        assertEquals("", m.get("replication_timestamp"));
        assertEquals("-1", m.get("replication_sequence"));
    }

    @Test
    public void checkPassesAndNamesWrongRegion() {
        assertNull(DumpRegion.check(dir, "kaiserburg"));
        String problem = DumpRegion.check(dir, "bayern");
        assertNotNull(problem);
        assertTrue(problem, problem.contains("region"));
        assertTrue(DumpRegion.check(dir.resolve("nope"), "kaiserburg").contains("MANIFEST"));
    }

    @Test
    public void nodeAsOsmApiXml() throws IOException {
        // Café Wanderer, Beim Tiergärtnertor
        byte[] xml = region.osmXml("node", 1675605507L);
        assertNotNull(xml);
        OsmDocument doc = OsmDocument.parse(new ByteArrayInputStream(xml), "node", "1675605507");
        assertNotNull(doc.primary());
        assertEquals("cafe", doc.primary().tag("amenity"));
        assertEquals("Wanderer", doc.primary().tag("name"));
        assertArrayEquals(new double[] {11.0741441, 49.4574744}, doc.nodes().get("1675605507"), 0);
        assertTrue(doc.primary().attributes().containsKey("version"));
        assertTrue(doc.primary().attributes().containsKey("timestamp"));
        String s = new String(xml, StandardCharsets.UTF_8);
        assertTrue("seven decimals, as the OSM API prints them", s.contains("lat=\"49.4574744\""));
        assertTrue(s, s.contains("generator=\"Linked OSM dump kaiserburg (kaiserburg.osm.pbf)\""));
    }

    @Test
    public void wayCarriesItsNodesAndBuildsAPolygon() throws IOException {
        // Palas building of the Kaiserburg: closed way, 11 distinct nodes
        byte[] xml = region.osmXml("way", 32113829L);
        assertNotNull(xml);
        OsmDocument doc = OsmDocument.parse(new ByteArrayInputStream(xml), "way", "32113829");
        assertEquals("Burg", doc.primary().tag("addr:street"));
        assertEquals(12, doc.ways().get("32113829").size());
        assertEquals(11, doc.nodes().size());
        assertArrayEquals(new double[] {11.0751556, 49.4578307}, doc.nodes().get("360425482"), 0);
        Geometry g = GeometryBuilder.build(doc);
        assertFalse(g.isEmpty());
        assertTrue(g.toWKT(), g.toWKT().startsWith("MULTIPOLYGON") || g.toWKT().startsWith("POLYGON"));
    }

    @Test
    public void completeRelationsAreServedIncompleteOnesAreNot() throws IOException {
        int served = 0;
        int refused = 0;
        DumpFiles.Blocks blocks = DumpFiles.readBlocks(dir.resolve(DumpFiles.BLOCKS));
        for (int b = 0; b < blocks.size(); b++) {
            if (blocks.types()[b] != PbfBlock.RELATION) {
                continue; // a block holding several types has one entry per type
            }
            for (PbfBlock.Relation r : region.block(b).relations()) {
                byte[] xml = region.osmXml("relation", r.id());
                if (xml == null) {
                    refused++;
                    continue;
                }
                served++;
                OsmDocument doc = OsmDocument.parse(new ByteArrayInputStream(xml), "relation",
                        Long.toString(r.id()));
                assertNotNull(doc.primary());
                assertEquals(r.memberIds().length, doc.primary().members().size());
                for (OsmDocument.Member m : doc.primary().members()) {
                    switch (m.type()) {
                        case "node" -> assertTrue(doc.nodes().containsKey(m.ref()));
                        case "way" -> assertTrue(doc.ways().containsKey(m.ref()));
                        default -> assertTrue(doc.relations().containsKey(m.ref()));
                    }
                }
            }
        }
        assertEquals(result.incompleteRelations(), refused);
        assertEquals(result.relations() - result.incompleteRelations(), served);
    }

    @Test
    public void absentElementsAreMisses() throws IOException {
        assertNull("node 1 is in Italy", region.osmXml("node", 1L));
        assertNull(region.osmXml("way", 100L));
        assertNull(region.osmXml("relation", 51477L));
        assertNull("beyond the largest id", region.osmXml("node", Long.MAX_VALUE));
    }

    @Test
    public void storeLookupParsesIdsAndMisses() throws IOException {
        DumpStore.Hit miss;
        DumpStore store = storeOf(region);
        assertNull(store.lookup("node", "abc", m -> fail(m)));
        assertNull(store.lookup("node", "-5", m -> fail(m)));
        miss = store.lookup("node", "1", m -> fail(m));
        assertNull(miss);
        DumpStore.Hit hit = store.lookup("way", "32113829", m -> fail(m));
        assertNotNull(hit);
        assertEquals("kaiserburg", hit.region().name());
    }

    @Test
    public void headerWithoutLocationsOnWaysIsRefused() {
        PbfBlock.Header h = new PbfBlock.Header(List.of("OsmSchema-V0.6", "DenseNodes"),
                List.of("Sort.Type_then_ID"), null, -1, null);
        try {
            DumpIndexer.checkHeader(h, Path.of("x.osm.pbf"));
            fail("accepted a file without locations on ways");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("LocationsOnWays"));
        }
        PbfBlock.Header unsorted = new PbfBlock.Header(List.of("OsmSchema-V0.6"),
                List.of("LocationsOnWays"), null, -1, null);
        try {
            DumpIndexer.checkHeader(unsorted, Path.of("x.osm.pbf"));
            fail("accepted an unsorted file");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("Sort.Type_then_ID"));
        }
    }

    @Test
    public void blockFindHandlesBoundaries() {
        DumpFiles.Blocks b = new DumpFiles.Blocks(
                new byte[] {PbfBlock.NODE, PbfBlock.NODE, PbfBlock.WAY, PbfBlock.RELATION},
                new long[] {10, 50, 5, 7},
                new long[] {40, 90, 500, 7},
                new long[] {0, 1, 2, 3});
        assertEquals(-1, b.find(PbfBlock.NODE, 9));
        assertEquals(0, b.find(PbfBlock.NODE, 10));
        assertEquals(0, b.find(PbfBlock.NODE, 40));
        assertEquals(-1, b.find(PbfBlock.NODE, 45));
        assertEquals(1, b.find(PbfBlock.NODE, 50));
        assertEquals(1, b.find(PbfBlock.NODE, 90));
        assertEquals(-1, b.find(PbfBlock.NODE, 91));
        assertEquals(2, b.find(PbfBlock.WAY, 5));
        assertEquals(-1, b.find(PbfBlock.WAY, 501));
        assertEquals(3, b.find(PbfBlock.RELATION, 7));
        assertEquals(-1, b.find(PbfBlock.RELATION, 8));
    }

    @Test
    public void coordinatesKeepSevenDecimals() {
        assertEquals("49.4574744", DumpRegion.coord(494574744));
        assertEquals("-0.1270000", DumpRegion.coord(-1270000));
        assertEquals("0.0000000", DumpRegion.coord(0));
    }

    @Test
    public void zigzag() {
        assertEquals(0, ProtoReader.zigzag(0));
        assertEquals(-1, ProtoReader.zigzag(1));
        assertEquals(1, ProtoReader.zigzag(2));
        assertEquals(-2, ProtoReader.zigzag(3));
        assertEquals(Long.MIN_VALUE, ProtoReader.zigzag(-1L));
    }

    private static DumpStore storeOf(DumpRegion r) {
        return DumpStore.of(List.of(r));
    }
}
