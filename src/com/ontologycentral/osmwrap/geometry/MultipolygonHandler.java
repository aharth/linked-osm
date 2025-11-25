package com.ontologycentral.osmwrap.geometry;

import java.io.IOException;
import java.util.ArrayList;
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
            if ("type".equals(key) && "multipolygon".equals(value)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Convert OSM multipolygon relation to MultipolygonGeometry
     * Handles fetching of member ways and node coordinates from OSM API
     */
    public static MultipolygonGeometry buildMultipolygon(String relationXml, String relationId) throws IOException {
        MultipolygonGeometry geom = new MultipolygonGeometry();

        // Extract members with their roles
        List<Map<String, String>> members = extractMembers(relationXml);

        // Process each member (way or node)
        for (Map<String, String> member : members) {
            String type = member.get("type");
            String ref = member.get("ref");
            String role = member.get("role");

            if ("way".equals(type)) {
                try {
                    // Fetch the way from OSM API
                    String wayXml = fetchFromOSMAPI("way", ref);
                    List<double[]> coordinates = extractWayCoordinates(wayXml);

                    if (!coordinates.isEmpty()) {
                        Ring ring = new Ring(coordinates, role);
                        if (ring.isClosed()) {
                            if ("inner".equals(role)) {
                                geom.addInnerRing(ring);
                            } else {
                                // Default to outer if role is empty or "outer"
                                geom.addOuterRing(ring);
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.warning("Failed to process way " + ref + " in relation " + relationId + ": " + e.getMessage());
                    // Continue processing other members
                }
            } else if ("node".equals(type)) {
                try {
                    // Fetch single node
                    String nodeXml = fetchFromOSMAPI("node", ref);
                    double[] coord = extractNodeCoordinate(nodeXml);

                    if (coord != null) {
                        List<double[]> singleCoord = new ArrayList<>();
                        singleCoord.add(coord);
                        Ring ring = new Ring(singleCoord, role);
                        // Note: single node rings won't be closed/valid, but we process them anyway
                    }
                } catch (Exception e) {
                    logger.warning("Failed to process node " + ref + " in relation " + relationId + ": " + e.getMessage());
                }
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
     * Extract coordinates for a way by fetching all referenced nodes
     */
    private static List<double[]> extractWayCoordinates(String wayXml) throws IOException {
        List<double[]> coordinates = new ArrayList<>();
        Set<String> processed = new HashSet<>();

        // Extract node references from the way
        List<String> nodeRefs = extractNodeReferences(wayXml);

        // Fetch each node and get its coordinates
        for (String nodeRef : nodeRefs) {
            if (processed.contains(nodeRef)) {
                continue;  // Skip duplicates
            }
            processed.add(nodeRef);

            try {
                String nodeXml = fetchFromOSMAPI("node", nodeRef);
                double[] coord = extractNodeCoordinate(nodeXml);

                if (coord != null) {
                    coordinates.add(coord);
                }
            } catch (Exception e) {
                logger.warning("Failed to fetch node " + nodeRef + ": " + e.getMessage());
                // Continue with next node
            }
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
