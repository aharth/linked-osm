package com.ontologycentral.osmwrap.geometry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import com.ontologycentral.osmwrap.OsmDocument;
import com.ontologycentral.osmwrap.OsmDocument.Element;
import com.ontologycentral.osmwrap.OsmDocument.Member;

/**
 * Assembles OSM multipolygon relations into {@link MultipolygonGeometry}: member ways are
 * resolved to coordinate sequences, grouped by role, and stitched end-to-end into closed
 * rings. All data comes from the parsed document; nothing is fetched.
 */
public final class MultipolygonHandler {
    private static final Logger logger = Logger.getLogger(MultipolygonHandler.class.getName());

    private MultipolygonHandler() {}

    /** True if the relation's {@code type} tag is {@code multipolygon} or {@code boundary}. */
    public static boolean isMultipolygon(Element rel) {
        if (rel == null) return false;
        String type = rel.tag("type");
        return "multipolygon".equals(type) || "boundary".equals(type);
    }

    /**
     * Build the multipolygon of a relation from a parsed {@code /full} document. Member ways
     * missing from the document are skipped. The result may be invalid (no closed outer
     * ring); callers check {@link MultipolygonGeometry#isValid()}.
     */
    public static MultipolygonGeometry buildMultipolygon(OsmDocument doc, String relationId) {
        Element rel = doc.relation(relationId);
        List<List<double[]>> outerSegments = new ArrayList<>();
        List<List<double[]>> innerSegments = new ArrayList<>();
        if (rel != null) {
            for (Member m : rel.members()) {
                if (!"way".equals(m.type())) continue;
                List<double[]> coords = GeometryBuilder.wayCoordinates(doc, m.ref());
                if (coords.size() < 2) {
                    if (!doc.ways().containsKey(m.ref())) {
                        logger.fine("relation " + relationId + ": member way " + m.ref() + " not in document");
                    }
                    continue;
                }
                if ("inner".equals(m.role())) {
                    innerSegments.add(coords);
                } else {
                    outerSegments.add(coords);
                }
            }
        }
        return buildFromSegments(outerSegments, innerSegments);
    }

    /**
     * Build multipolygon geometry from member way segments already resolved to coordinates
     * (also used for Overpass {@code out geom} responses).
     *
     * @param outerSegments way coordinate sequences with role "outer"
     * @param innerSegments way coordinate sequences with role "inner" (holes)
     */
    public static MultipolygonGeometry buildFromSegments(
            List<List<double[]>> outerSegments,
            List<List<double[]>> innerSegments) {
        MultipolygonGeometry geom = new MultipolygonGeometry();
        for (List<double[]> stitched : stitchWaySegments(outerSegments)) {
            Ring ring = new Ring(stitched, "outer");
            if (ring.isClosed()) geom.addOuterRing(ring);
        }
        for (List<double[]> stitched : stitchWaySegments(innerSegments)) {
            Ring ring = new Ring(stitched, "inner");
            if (ring.isClosed()) geom.addInnerRing(ring);
        }
        return geom;
    }

    /**
     * Stitch a list of open or closed way coordinate lists into closed rings.
     * Adjacent segments are joined end-to-end (reversing if needed) until the
     * accumulated path closes back on itself.
     */
    private static List<List<double[]>> stitchWaySegments(List<List<double[]>> segments) {
        List<List<double[]>> result = new ArrayList<>();
        List<List<double[]>> remaining = new ArrayList<>(segments);

        while (!remaining.isEmpty()) {
            List<double[]> ring = new ArrayList<>(remaining.remove(0));
            boolean progress = true;
            while (progress && !segmentIsClosed(ring)) {
                progress = false;
                for (int i = 0; i < remaining.size(); i++) {
                    List<double[]> seg = remaining.get(i);
                    double[] ringEnd = ring.get(ring.size() - 1);
                    if (coordsEqual(ringEnd, seg.get(0))) {
                        ring.addAll(seg.subList(1, seg.size()));
                        remaining.remove(i);
                        progress = true;
                        break;
                    } else if (coordsEqual(ringEnd, seg.get(seg.size() - 1))) {
                        List<double[]> rev = new ArrayList<>(seg);
                        Collections.reverse(rev);
                        ring.addAll(rev.subList(1, rev.size()));
                        remaining.remove(i);
                        progress = true;
                        break;
                    }
                }
            }
            if (ring.size() >= 3) {
                result.add(ring);
            }
        }
        return result;
    }

    private static boolean segmentIsClosed(List<double[]> ring) {
        return ring.size() >= 4 && coordsEqual(ring.get(0), ring.get(ring.size() - 1));
    }

    static boolean coordsEqual(double[] a, double[] b) {
        return Math.abs(a[0] - b[0]) < 1e-9 && Math.abs(a[1] - b[1]) < 1e-9;
    }

    /** GeoJSON of a multipolygon, or an empty GeometryCollection if it is not valid. */
    public static String toGeoJSON(MultipolygonGeometry geometry) {
        if (geometry == null || !geometry.isValid()) {
            return Geometry.EMPTY.toGeoJSON();
        }
        return geometry.toGeoJSON();
    }
}
