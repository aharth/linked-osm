package com.ontologycentral.osmwrap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Offline tests for {@link GeoJsonConverter#overpassFeaturesToGeoJson}.
 *
 * <p>The way fixture is a real Overpass {@code out geom} response (a DPD
 * warehouse in Nürnberg, trimmed): crucially its {@code <nd>} elements carry
 * the node {@code ref} BEFORE {@code lat}/{@code lon} — the shape Overpass
 * actually emits for way geometry, which a position-dependent regex misses
 * (the live endpoint returned zero way features until this was fixed).
 */
public class GeoJsonConverterTest {

    private static final String WAY_OUT_GEOM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <osm version="0.6" generator="Overpass API 0.7.62.11 87bfad18">
            <note>The data included in this document is from www.openstreetmap.org. The data is made available under ODbL.</note>
            <meta osm_base="2026-06-11T18:19:36Z"/>
              <way id="28927139">
                <bounds minlat="49.4042020" minlon="11.0509057" maxlat="49.4051266" maxlon="11.0530186"/>
                <nd ref="317994318" lat="49.4043098" lon="11.0509057"/>
                <nd ref="317994319" lat="49.4051266" lon="11.0510061"/>
                <nd ref="317994320" lat="49.4050984" lon="11.0515488"/>
                <nd ref="317994321" lat="49.4046279" lon="11.0514909"/>
                <nd ref="317994318" lat="49.4043098" lon="11.0509057"/>
                <tag k="building" v="warehouse"/>
                <tag k="name" v="DPD"/>
              </way>
            </osm>
            """;

    @Test
    public void wayWithRefFirstNdAttributesBecomesAPolygon() {
        String json = GeoJsonConverter.overpassFeaturesToGeoJson(WAY_OUT_GEOM);
        assertTrue("way must be emitted as a feature: " + json,
                json.contains("\"osm_id\":\"28927139\""));
        assertTrue("closed ring must become a Polygon: " + json,
                json.contains("\"type\":\"Polygon\""));
        assertTrue("first coordinate is [lon, lat]: " + json,
                json.contains("[11.0509057,49.4043098]"));
        assertTrue("tags survive (keys namespaced by osmTagKey): " + json,
                json.contains("\"/tag/name\":\"DPD\""));
        assertTrue("attribution from <note> is kept: " + json,
                json.contains("ODbL"));
    }

    @Test
    public void openWayBecomesALineString() {
        String open = WAY_OUT_GEOM.replace(
                "    <nd ref=\"317994318\" lat=\"49.4043098\" lon=\"11.0509057\"/>\n"
                        + "    <tag k=\"building\"", "    <tag k=\"building\"");
        String json = GeoJsonConverter.overpassFeaturesToGeoJson(open);
        // Ring no longer closes (first nd != last nd) → LineString.
        assertTrue(json.contains("\"type\":\"LineString\""));
    }

    @Test
    public void relationMemberNdsWithoutRefStillParse() {
        String relation = """
                <osm version="0.6">
                  <relation id="99">
                    <member type="way" ref="1" role="outer">
                      <nd lat="49.0" lon="11.0"/>
                      <nd lat="49.0" lon="11.1"/>
                      <nd lat="49.1" lon="11.1"/>
                      <nd lat="49.0" lon="11.0"/>
                    </member>
                    <tag k="type" v="multipolygon"/>
                  </relation>
                </osm>
                """;
        String json = GeoJsonConverter.overpassFeaturesToGeoJson(relation);
        assertTrue("relation must yield a polygonal feature: " + json,
                json.contains("\"osm_id\":\"99\"") && json.contains("Polygon"));
    }

    @Test
    public void emptyResponseYieldsEmptyFeatureCollection() {
        String json = GeoJsonConverter.overpassFeaturesToGeoJson(
                "<osm version=\"0.6\"></osm>");
        assertEquals(-1, json.indexOf("\"Feature\""));
        assertTrue(json.contains("\"features\":[]"));
    }
}
