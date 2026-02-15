package com.ontologycentral.osmwrap;

/**
 * Shared API constants for OpenStreetMap and related services.
 */
public final class ApiConstants {

    public static final String OSM_API_BASE = "https://api.openstreetmap.org/api/0.6";
    public static final String NOMINATIM_API_BASE = "https://nominatim.openstreetmap.org";
    public static final String OVERPASS_API_BASE = "https://overpass-api.de/api/interpreter";

    public static final int DEFAULT_CONNECT_TIMEOUT = 8000;
    public static final int DEFAULT_READ_TIMEOUT = 8000;
    public static final int SEARCH_READ_TIMEOUT = 30000;
    public static final int POI_READ_TIMEOUT = 30000;
    public static final int GEOMETRY_READ_TIMEOUT = 30000;
    public static final int AROUND_READ_TIMEOUT = 30000;

    private ApiConstants() {
        // Utility class
    }
}