package com.ontologycentral.osmwrap;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * Utility class for building URLs for OpenStreetMap and related APIs.
 */
public final class UrlBuilder {

    /**
     * Builds URL for OSM feature (node, way, relation).
     *
     * @param type the OSM feature type (node, way, relation)
     * @param id the feature ID
     * @return complete API URL
     */
    public static String buildFeatureUrl(String type, String id) {
        return ApiConstants.OSM_API_BASE + "/" + type + "/" + id;
    }

    /**
     * Builds URL for OSM changeset.
     *
     * @param changesetId the changeset ID
     * @return complete API URL
     */
    public static String buildChangesetUrl(String changesetId) {
        return ApiConstants.OSM_API_BASE + "/changeset/" + changesetId;
    }

    /**
     * Builds URL for OSM map data with bounding box.
     *
     * @param bbox bounding box in format "west,south,east,north"
     * @return complete API URL
     */
    public static String buildMapUrl(String bbox) {
        return ApiConstants.OSM_API_BASE + "/map?bbox=" + bbox;
    }

    /**
     * Builds URL for Nominatim search.
     *
     * @param query the search query
     * @return complete search URL
     * @throws UnsupportedEncodingException if URL encoding fails
     */
    public static String buildSearchUrl(String query) throws UnsupportedEncodingException {
        return ApiConstants.NOMINATIM_API_BASE + "/search?q=" + URLEncoder.encode(query, "UTF-8") + "&format=xml";
    }

    /**
     * Builds Overpass API query for Points of Interest.
     *
     * @param bbox bounding box in format "west,south,east,north"
     * @return Overpass query string
     */
    public static String buildOverpassPOIQuery(String bbox) {
        // Convert bbox from "west,south,east,north" to "south,west,north,east" for Overpass API
        String[] coords = bbox.split(",");
        if (coords.length != 4) {
            throw new IllegalArgumentException("Invalid bbox format. Use: west,south,east,north");
        }
        String overpassBbox = coords[1] + "," + coords[0] + "," + coords[3] + "," + coords[2]; // south,west,north,east

        return "[out:xml][timeout:25];\n" +
               "(\n" +
               "  node[amenity](" + overpassBbox + ");\n" +
               ");\n" +
               "out meta;";
    }

    /**
     * Builds Overpass API query for geometry data.
     *
     * @param elementType the OSM element type (node, way, relation)
     * @param id the element ID
     * @return Overpass query string
     */
    public static String buildOverpassGeometryQuery(String elementType, String id) {
        return "[out:json];" + elementType + "(" + id + ");out geom;";
    }

    /**
     * Builds Overpass API query for nodes around a point.
     *
     * @param lat WGS84 latitude
     * @param lon WGS84 longitude
     * @param radius search radius in meters
     * @return Overpass query string
     */
    public static String buildOverpassAroundQuery(String lat, String lon, String radius) {
        if (lat == null || lon == null || radius == null) {
            throw new IllegalArgumentException("lat, lon, and radius must not be null");
        }

        return "[out:xml][timeout:25];\n" +
               "(\n" +
               "  node(around:" + radius + "," + lat + "," + lon + ");\n" +
               ");\n" +
               "out meta;";
    }

    private UrlBuilder() {
        // Utility class
    }
}