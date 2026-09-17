package com.ontologycentral.osmwrap;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.logging.Logger;

import com.ontologycentral.osmwrap.UpstreamCache.Fetched;
import com.ontologycentral.osmwrap.geometry.Geometry;
import com.ontologycentral.osmwrap.geometry.GeometryBuilder;

/**
 * One OSM API element, loaded the same way for every servlet and output format:
 * upstream URL → {@link UpstreamCache} → {@link OsmDocument} → {@link Geometry}.
 *
 * @param type        {@code node}, {@code way} or {@code relation}
 * @param id          the element id
 * @param upstreamUrl the OSM API URL the body came from
 * @param fetched     the raw upstream body and declared byte count
 * @param doc         the parsed document
 * @param geometry    the element's geometry, {@link Geometry#EMPTY} if none could be built
 */
public record OsmElement(String type, String id, String upstreamUrl, Fetched fetched,
        OsmDocument doc, Geometry geometry) {

    private static final Logger _log = Logger.getLogger(OsmElement.class.getName());

    /**
     * The OSM API URL for an element. Nodes carry their coordinates themselves; ways and
     * relations need {@code /full} so member nodes (and ways) come inline in one response.
     */
    public static String upstreamUrl(String type, String id) {
        return ApiConstants.OSM_API_BASE + "/" + type + "/" + id + ("node".equals(type) ? "" : "/full");
    }

    /**
     * @throws UpstreamCache.UpstreamException if the OSM API answered with a non-200 status
     * @throws IOException                     on transport failure or malformed XML
     */
    public static OsmElement load(UpstreamCache cache, String type, String id) throws IOException {
        String url = upstreamUrl(type, id);
        Fetched fetched = cache.fetch(url);
        OsmDocument doc = OsmDocument.parse(new ByteArrayInputStream(fetched.body()), type, id);
        Geometry geometry = GeometryBuilder.build(doc);
        _log.info(type + " " + id + ": " + doc.nodes().size() + " nodes, " + doc.ways().size()
                + " ways, " + doc.relations().size() + " relations, geometry "
                + (geometry.isEmpty() ? "none" : geometry.getClass().getSimpleName())
                + (doc.primary() == null ? ", primary element missing" : ""));
        return new OsmElement(type, id, url, fetched, doc, geometry);
    }

    /** The element's tags as {@code {k, v}} pairs; empty if the element is missing. */
    public List<String[]> tags() {
        return doc.primary() == null ? List.of() : doc.primary().tags();
    }

    /** GeoJSON Feature for this element under the given URI prefix (e.g. {@code /osm}). */
    public String toGeoJsonFeature(String sourcePrefix) {
        return GeoJsonConverter.osmFeatureToGeoJson(tags(), type, id, geometry.toGeoJSON(), sourcePrefix);
    }

    /** KML document with one Placemark for this element. */
    public String toKmlDocument() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n"
                + "  <Document>\n"
                + "    <Placemark>\n"
                + "      <name>" + type + " " + id + "</name>\n"
                + "      " + geometry.toKML() + "\n"
                + "    </Placemark>\n"
                + "  </Document>\n"
                + "</kml>";
    }
}
