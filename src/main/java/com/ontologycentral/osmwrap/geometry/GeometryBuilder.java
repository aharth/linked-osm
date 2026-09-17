package com.ontologycentral.osmwrap.geometry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import com.ontologycentral.osmwrap.OsmDocument;
import com.ontologycentral.osmwrap.OsmDocument.Element;
import com.ontologycentral.osmwrap.OsmDocument.Member;

/**
 * The one place that turns a parsed OSM element into a {@link Geometry}. Every output
 * format (GeoJSON, WKT, KML, GML in Turtle and in the GML feature collection) is derived
 * from the result, so all of them agree.
 *
 * <ul>
 *   <li>node → {@link PointGeometry}</li>
 *   <li>way → {@link MultipolygonGeometry} (one outer ring) when closed with at least four
 *       nodes, otherwise {@link LineStringGeometry}</li>
 *   <li>relation with {@code type=multipolygon|boundary} → {@link MultipolygonGeometry} via
 *       {@link MultipolygonHandler} ring assembly</li>
 *   <li>any other relation → {@link LineStringGeometry} over all member coordinates in
 *       member order (node members directly, way members as their node lists)</li>
 *   <li>anything without usable coordinates → {@link Geometry#EMPTY}</li>
 * </ul>
 *
 * Everything is resolved from the document alone: no upstream calls.
 */
public final class GeometryBuilder {
    private static final Logger LOG = Logger.getLogger(GeometryBuilder.class.getName());

    private GeometryBuilder() {}

    public static Geometry build(OsmDocument doc) {
        Element primary = doc.primary();
        if (primary == null) return Geometry.EMPTY;
        switch (primary.type()) {
            case "node": {
                double[] c = doc.nodes().get(primary.id());
                return c == null ? Geometry.EMPTY : new PointGeometry(c[0], c[1]);
            }
            case "way":
                return fromCoordinates(wayCoordinates(doc, primary.id()));
            case "relation": {
                if (MultipolygonHandler.isMultipolygon(primary)) {
                    MultipolygonGeometry geom = MultipolygonHandler.buildMultipolygon(doc, primary.id());
                    if (geom.isValid()) return geom;
                    LOG.info("relation " + primary.id() + ": multipolygon has no closed outer ring, "
                            + "falling back to member LineString");
                }
                return fromCoordinates(memberCoordinates(doc, primary));
            }
            default:
                return Geometry.EMPTY;
        }
    }

    /**
     * Point for one coordinate, a single-ring polygon for a closed sequence of at least
     * four, a LineString otherwise; empty for nothing.
     */
    static Geometry fromCoordinates(List<double[]> coords) {
        if (coords.isEmpty()) return Geometry.EMPTY;
        if (coords.size() == 1) return new PointGeometry(coords.get(0)[0], coords.get(0)[1]);
        if (coords.size() >= 4 && MultipolygonHandler.coordsEqual(coords.get(0), coords.get(coords.size() - 1))) {
            MultipolygonGeometry geom = new MultipolygonGeometry();
            geom.addOuterRing(new Ring(coords, "outer"));
            if (geom.isValid()) return geom;
        }
        return new LineStringGeometry(coords);
    }

    /** A way's node coordinates in order; refs without a known node are skipped. */
    static List<double[]> wayCoordinates(OsmDocument doc, String wayId) {
        List<double[]> coords = new ArrayList<>();
        List<String> refs = doc.ways().get(wayId);
        if (refs == null) return coords;
        Map<String, double[]> nodes = doc.nodes();
        for (String ref : refs) {
            double[] c = nodes.get(ref);
            if (c != null) coords.add(c);
        }
        return coords;
    }

    /** A relation's member coordinates in member order (nodes directly, ways as node lists). */
    static List<double[]> memberCoordinates(OsmDocument doc, Element rel) {
        List<double[]> coords = new ArrayList<>();
        Map<String, double[]> nodes = doc.nodes();
        for (Member m : rel.members()) {
            if ("node".equals(m.type())) {
                double[] c = nodes.get(m.ref());
                if (c != null) coords.add(c);
            } else if ("way".equals(m.type())) {
                coords.addAll(wayCoordinates(doc, m.ref()));
            }
        }
        return coords;
    }
}
