package com.ontologycentral.osmwrap.geometry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ontologycentral.osmwrap.HttpClientUtil;
import com.ontologycentral.osmwrap.OsmFullDocument;
import com.ontologycentral.osmwrap.OsmFullDocument.Member;

/**
 * Handles detection and processing of OSM multipolygon relations.
 * Orchestrates the conversion of multipolygon relations to MultipolygonGeometry objects
 * and their output in various formats (GeoJSON, WKT, KML).
 */
public class MultipolygonHandler {
    private static final Logger logger = Logger.getLogger(MultipolygonHandler.class.getName());

    // Regex patterns for parsing OSM XML
    private static final Pattern TAG_PATTERN = Pattern.compile("<tag k=['\"]([^'\"]+)['\"] v=['\"]([^'\"]+)['\"]");
    private static final Pattern MEMBER_PATTERN = Pattern.compile("<member type=['\"]([^'\"]+)['\"] ref=['\"]([^'\"]+)['\"] role=['\"]([^'\"]*)['\"]");
    private static final Pattern ND_PATTERN = Pattern.compile("<nd ref=['\"]([^'\"]+)['\"]");

    /**
     * Check if an OSM relation XML represents a multipolygon
     */
    public static boolean isMultipolygon(String osmXml) {
        Matcher m = TAG_PATTERN.matcher(osmXml);
        while (m.find()) {
            String key = m.group(1);
            String value = m.group(2);
            if ("type".equals(key) && ("multipolygon".equals(value) || "boundary".equals(value))) {
                return true;
            }
        }
        return false;
    }

    /** True if the relation's {@code type} tag is {@code multipolygon} or {@code boundary}. */
    public static boolean isMultipolygon(OsmFullDocument.Relation rel) {
        if (rel == null) return false;
        String type = rel.tag("type");
        return "multipolygon".equals(type) || "boundary".equals(type);
    }

    /**
     * Convert OSM multipolygon relation to MultipolygonGeometry
     * Handles bulk fetching of member ways and node coordinates from OSM API
     */
    public static MultipolygonGeometry buildMultipolygon(String relationXml, String relationId) throws IOException {
        // Parse any inline way/node data already present in the XML (e.g. from /full responses).
        // Only HTTP-fetch what is missing — avoids hundreds of round-trips for large relations.
        return buildMultipolygon(extractMembers(relationXml), parseInlineWays(relationXml),
                parseInlineNodes(relationXml), relationId);
    }

    /**
     * Build the multipolygon of a relation from an already-parsed {@code /full} document.
     * Everything the relation needs is inline, so this normally makes no upstream calls.
     */
    public static MultipolygonGeometry buildMultipolygon(OsmFullDocument doc, String relationId) throws IOException {
        OsmFullDocument.Relation rel = doc.relation(relationId);
        List<Member> members = rel == null ? new ArrayList<>() : rel.members();
        return buildMultipolygon(members, doc.ways(), doc.nodes(), relationId);
    }

    /**
     * Shared assembly: resolve member ways to node lists and nodes to coordinates (fetching
     * from upstream only what {@code inlineWayNodes}/{@code inlineNodeCoords} do not already
     * contain), then stitch the way segments into rings by role.
     */
    private static MultipolygonGeometry buildMultipolygon(List<Member> members,
            Map<String, List<String>> inlineWayNodes, Map<String, double[]> inlineNodeCoords,
            String relationId) throws IOException {
        MultipolygonGeometry geom = new MultipolygonGeometry();

        List<String> wayIds = new ArrayList<>();
        Map<String, String> wayRoles = new HashMap<>();
        for (Member member : members) {
            if ("way".equals(member.type()) && member.ref() != null) {
                wayIds.add(member.ref());
                wayRoles.put(member.ref(), member.role() != null ? member.role() : "outer");
            }
        }

        if (!wayIds.isEmpty()) {
            try {
                // Fetch only ways not already present in the inline data
                List<String> wayIdsToFetch = new ArrayList<>();
                for (String wid : wayIds) {
                    if (!inlineWayNodes.containsKey(wid)) wayIdsToFetch.add(wid);
                }
                Map<String, List<String>> fetchedWayNodes =
                        wayIdsToFetch.isEmpty() ? new HashMap<>() : HttpClientUtil.fetchWaysBulk(wayIdsToFetch);

                Map<String, List<String>> allWayNodes = inlineWayNodes;
                if (!fetchedWayNodes.isEmpty()) {
                    allWayNodes = new HashMap<>(inlineWayNodes);
                    allWayNodes.putAll(fetchedWayNodes);
                }

                // Collect all unique node IDs referenced by member ways
                Set<String> allWayNodeIds = new HashSet<>();
                for (String wid : wayIds) {
                    List<String> refs = allWayNodes.get(wid);
                    if (refs != null) allWayNodeIds.addAll(refs);
                }

                // Fetch only nodes not already present in the inline data
                List<String> nodeIdsToFetch = new ArrayList<>();
                for (String nid : allWayNodeIds) {
                    if (!inlineNodeCoords.containsKey(nid)) nodeIdsToFetch.add(nid);
                }
                Map<String, double[]> fetchedNodeCoords =
                        nodeIdsToFetch.isEmpty() ? new HashMap<>() : HttpClientUtil.fetchNodesBulk(nodeIdsToFetch);

                Map<String, double[]> nodeCoordinates = inlineNodeCoords;
                if (!fetchedNodeCoords.isEmpty()) {
                    nodeCoordinates = new HashMap<>(inlineNodeCoords);
                    nodeCoordinates.putAll(fetchedNodeCoords);
                }

                // Collect per-role way segments then stitch into rings
                List<List<double[]>> outerSegments = new ArrayList<>();
                List<List<double[]>> innerSegments = new ArrayList<>();

                for (String wayId : wayIds) {
                    try {
                        List<String> nodeRefs = allWayNodes.get(wayId);
                        if (nodeRefs == null || nodeRefs.isEmpty()) continue;
                        List<double[]> coords = new ArrayList<>();
                        for (String nodeRef : nodeRefs) {
                            if (nodeCoordinates.containsKey(nodeRef)) {
                                coords.add(nodeCoordinates.get(nodeRef));
                            }
                        }
                        if (coords.size() < 2) continue;
                        String role = wayRoles.get(wayId);
                        if ("inner".equals(role)) {
                            innerSegments.add(coords);
                        } else {
                            outerSegments.add(coords);
                        }
                    } catch (Exception e) {
                        logger.warning("Failed to collect way " + wayId + ": " + e.getMessage());
                    }
                }

                for (List<double[]> stitched : stitchWaySegments(outerSegments)) {
                    Ring ring = new Ring(stitched, "outer");
                    if (ring.isClosed()) geom.addOuterRing(ring);
                }
                for (List<double[]> stitched : stitchWaySegments(innerSegments)) {
                    Ring ring = new Ring(stitched, "inner");
                    if (ring.isClosed()) geom.addInnerRing(ring);
                }
            } catch (Exception e) {
                logger.warning("Error building multipolygon for relation " + relationId + ": " + e.getMessage());
            }
        }

        // Node members (label, admin_centre, ...) carry no ring geometry and are ignored.
        return geom;
    }

    /**
     * Extract member elements from relation XML
     */
    private static List<Member> extractMembers(String osmXml) {
        List<Member> members = new ArrayList<>();
        Matcher m = MEMBER_PATTERN.matcher(osmXml);
        while (m.find()) {
            members.add(new Member(m.group(1), m.group(2), m.group(3) != null ? m.group(3) : "outer"));
        }
        return members;
    }

    /**
     * Parse way→nodeRef lists from XML that already contains inline &lt;way&gt; elements
     * (e.g. from an OSM /full response). Returns an empty map if none are present.
     */
    private static Map<String, List<String>> parseInlineWays(String xml) {
        Map<String, List<String>> result = new HashMap<>();
        Pattern wayPat = Pattern.compile("<way id=['\"]([^'\"]+)['\"][^>]*>(.*?)</way>", Pattern.DOTALL);
        Matcher wm = wayPat.matcher(xml);
        while (wm.find()) {
            String wayId = wm.group(1);
            List<String> refs = new ArrayList<>();
            Matcher nm = ND_PATTERN.matcher(wm.group(2));
            while (nm.find()) refs.add(nm.group(1));
            result.put(wayId, refs);
        }
        return result;
    }

    /**
     * Parse node coordinates from XML that already contains inline &lt;node&gt; elements
     * (e.g. from an OSM /full response). Returns an empty map if none are present.
     */
    private static Map<String, double[]> parseInlineNodes(String xml) {
        Map<String, double[]> result = new HashMap<>();
        Pattern nodePat = Pattern.compile(
                "<node id=['\"]([^'\"]+)['\"][^>]*lat=['\"]([^'\"]+)['\"][^>]*lon=['\"]([^'\"]+)['\"]");
        Matcher m = nodePat.matcher(xml);
        while (m.find()) {
            try {
                double lat = Double.parseDouble(m.group(2));
                double lon = Double.parseDouble(m.group(3));
                result.put(m.group(1), new double[]{lon, lat});
            } catch (NumberFormatException e) {
                logger.warning("Skipping node with bad coordinates: " + m.group(1));
            }
        }
        return result;
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

    private static boolean coordsEqual(double[] a, double[] b) {
        return Math.abs(a[0] - b[0]) < 1e-9 && Math.abs(a[1] - b[1]) < 1e-9;
    }

    /**
     * Build multipolygon geometry from pre-parsed member way segments.
     * Reuses the private stitchWaySegments logic.
     *
     * @param outerSegments way coordinate sequences with role "outer"
     * @param innerSegments way coordinate sequences with role "inner" (holes)
     * @return assembled MultipolygonGeometry
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
     * Convert multipolygon to GeoJSON format
     */
    public static String toGeoJSON(MultipolygonGeometry geometry) {
        if (geometry == null || !geometry.isValid()) {
            return "{\"type\":\"GeometryCollection\",\"geometries\":[]}";
        }
        return geometry.toGeoJSON();
    }

}
