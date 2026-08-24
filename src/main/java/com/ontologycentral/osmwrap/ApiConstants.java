package com.ontologycentral.osmwrap;

/**
 * Shared API constants for OpenStreetMap and related services.
 */
public final class ApiConstants {

    public static final String OSM_API_BASE = "https://api.openstreetmap.org/api/0.6";
    public static final String NOMINATIM_API_BASE = "https://nominatim.openstreetmap.org";
    public static final String OVERPASS_API_BASE = "https://overpass-api.de/api/interpreter";

    /**
     * Overpass endpoints in fallback order. overpass-api.de round-robins
     * several backends and individual ones can be broken (observed
     * 2026-07-22: one backend answering Apache 406 to every request while
     * its sibling serves fine) - a pooled connection then pins the broken
     * host and every retry fails. Falling back to a different HOSTNAME
     * forces a fresh connection and a working instance.
     */
    public static final String[] OVERPASS_API_BASES = {
        OVERPASS_API_BASE,
        "https://overpass.kumi.systems/api/interpreter",
    };

    private ApiConstants() {
        // Utility class
    }
}