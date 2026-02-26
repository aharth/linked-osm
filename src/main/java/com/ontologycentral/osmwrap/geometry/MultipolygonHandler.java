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

import com.ontologycentral.osmwrap.ApiConstants;
import com.ontologycentral.osmwrap.HttpClientUtil;

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
    private static final Pattern LAT_PATTERN = Pattern.compile("lat=['\"]([^'\"]+)['\"]");
    private static final Pattern LON_PATTERN = Pattern.compile("lon=['\"]([^'\"]+)['\"]");

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

    /**
     * Convert OSM multipolygon relation to MultipolygonGeometry
     * Handles bulk fetching of member ways and node coordinates from OSM API
     */
    public static MultipolygonGeometry buildMultipolygon(String relationXml, String relationId) throws IOException {
        MultipolygonGeometry geom = new MultipolygonGeometry();

        // Extract members with their roles
        List<Map<String, String>> members = extractMembers(relationXml);

        // Separate way and node members for bulk fetching
        List<String> wayIds = new ArrayList<>();
        Map<String, String> wayRoles = new HashMap<>();
        List<String> nodeIds = new ArrayList<>();
        Map<String, String> nodeRoles = new HashMap<>();

        for (Map<String, String> member : members) {
            String type = member.get("type");
            String ref = member.get("ref");
            String role = member.get("role");

            if ("way".equals(type)) {
                wayIds.add(ref);
                wayRoles.put(ref, role);
            } else if ("node".equals(type)) {
                nodeIds.add(ref);
                nodeRoles.put(ref, role);
            }
        }

        // Bulk fetch all ways and get their node references
        if (!wayIds.isEmpty()) {
            try {
                Map<String, List<String>> wayNodes = HttpClientUtil.fetchWaysBulk(wayIds);

                // Collect all unique node IDs from ways
                Set<String> allWayNodeIds = new HashSet<>();
                for (List<String> nodes : wayNodes.values()) {
                    allWayNodeIds.addAll(nodes);
                }

                // Bulk fetch all node coordinates
                if (!allWayNodeIds.isEmpty()) {
                    Map<String, double[]> nodeCoordinates = HttpClientUtil.fetchNodesBulk(new ArrayList<>(allWayNodeIds));

                    // Collect per-role way segments then stitch into rings
                    List<List<double[]>> outerSegments = new ArrayList<>();
                    List<List<double[]>> innerSegments = new ArrayList<>();

                    for (String wayId : wayIds) {
                        try {
                            List<String> nodeRefs = wayNodes.get(wayId);
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
                }
            } catch (Exception e) {
                logger.warning("Error bulk fetching ways for relation " + relationId + ": " + e.getMessage());
            }
        }

        // Process node members
        if (!nodeIds.isEmpty()) {
            try {
                Map<String, double[]> nodeCoordinates = HttpClientUtil.fetchNodesBulk(nodeIds);

                for (String nodeId : nodeIds) {
                    try {
                        if (nodeCoordinates.containsKey(nodeId)) {
                            double[] coord = nodeCoordinates.get(nodeId);
                            List<double[]> singleCoord = new ArrayList<>();
                            singleCoord.add(coord);
                            String role = nodeRoles.get(nodeId);
                            Ring ring = new Ring(singleCoord, role);
                            // Note: single node rings won't be closed/valid, but we process them anyway
                        }
                    } catch (Exception e) {
                        logger.warning("Failed to process node " + nodeId + " in relation " + relationId + ": " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                logger.warning("Error bulk fetching nodes for relation " + relationId + ": " + e.getMessage());
            }
        }

        return geom;
    }

    /**
     * Extract member elements from relation XML
     * @return List of maps with "type", "ref", and "role" keys
     */
    private static List<Map<String, String>> extractMembers(String osmXml) {
        List<Map<String, String>> members = new ArrayList<>();
        Matcher m = MEMBER_PATTERN.matcher(osmXml);

        while (m.find()) {
            Map<String, String> member = new HashMap<>();
            member.put("type", m.group(1));
            member.put("ref", m.group(2));
            member.put("role", m.group(3) != null ? m.group(3) : "outer");
            members.add(member);
        }

        return members;
    }

    /**
     * Extract node references from way XML
     */
    private static List<String> extractNodeReferences(String wayXml) {
        List<String> refs = new ArrayList<>();
        Matcher m = ND_PATTERN.matcher(wayXml);

        while (m.find()) {
            refs.add(m.group(1));
        }

        return refs;
    }

    /**
     * Extract coordinates for a way by fetching all referenced nodes in bulk
     */
    private static List<double[]> extractWayCoordinates(String wayXml) throws IOException {
        List<double[]> coordinates = new ArrayList<>();

        // Extract node references from the way
        List<String> nodeRefs = extractNodeReferences(wayXml);

        if (nodeRefs.isEmpty()) {
            return coordinates;
        }

        // Remove duplicates
        Set<String> uniqueNodeRefs = new HashSet<>(nodeRefs);

        try {
            // Use bulk fetching to get all nodes in batches (up to 50 per request)
            Map<String, double[]> nodeCoordinates = HttpClientUtil.fetchNodesBulk(new ArrayList<>(uniqueNodeRefs));

            // Maintain original order from nodeRefs list
            for (String nodeRef : nodeRefs) {
                if (nodeCoordinates.containsKey(nodeRef)) {
                    coordinates.add(nodeCoordinates.get(nodeRef));
                }
            }
        } catch (Exception e) {
            logger.warning("Error fetching node coordinates in bulk: " + e.getMessage());
            // Return empty list on error
        }

        return coordinates;
    }

    /**
     * Extract latitude and longitude from a node XML
     */
    private static double[] extractNodeCoordinate(String nodeXml) {
        Matcher latMatcher = LAT_PATTERN.matcher(nodeXml);
        Matcher lonMatcher = LON_PATTERN.matcher(nodeXml);

        if (latMatcher.find() && lonMatcher.find()) {
            try {
                double lon = Double.parseDouble(lonMatcher.group(1));
                double lat = Double.parseDouble(latMatcher.group(1));
                return new double[]{lon, lat};
            } catch (NumberFormatException e) {
                logger.warning("Failed to parse coordinates: " + e.getMessage());
                return null;
            }
        }

        return null;
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
     * Fetch OSM element (node/way/relation) from OSM API
     */
    private static String fetchFromOSMAPI(String type, String id) throws IOException {
        String url = ApiConstants.OSM_API_BASE + "/" + type + "/" + id;
        return HttpClientUtil.fetchUrl(url, ApiConstants.DEFAULT_CONNECT_TIMEOUT, ApiConstants.GEOMETRY_READ_TIMEOUT);
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

    /**
     * Convert multipolygon to WKT format
     */
    public static String toWKT(MultipolygonGeometry geometry) {
        if (geometry == null || !geometry.isValid()) {
            return "GEOMETRYCOLLECTION()";
        }
        return geometry.toWKT();
    }

    /**
     * Convert multipolygon to KML format (wrapped in Placemark)
     */
    public static String toKML(MultipolygonGeometry geometry, String relationId) {
        if (geometry == null || !geometry.isValid()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<Placemark>");
        sb.append("<name>relation ").append(relationId).append("</name>");
        sb.append(geometry.toKML());
        sb.append("</Placemark>");

        return sb.toString();
    }
}
