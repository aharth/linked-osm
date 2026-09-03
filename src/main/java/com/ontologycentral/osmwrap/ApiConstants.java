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

    /**
     * Tracestrack's paid Overpass API, keyed per account. {@code %s} is
     * replaced with the key from {@link BuildInfo#getTracestrackApiKey()}.
     */
    public static final String TRACESTRACK_OVERPASS_TEMPLATE =
        "https://api.tracestrack.com/overpass/%s/interpreter";

    /**
     * Protomaps' hosted basemap tile API (vector, {@code .mvt}, maxzoom 15).
     * {@code %1$d}, {@code %2$d}, {@code %3$d} are z/x/y; {@code %4$s} is the
     * key from {@link BuildInfo#getProtomapsApiKey()}.
     */
    public static final String PROTOMAPS_TILE_TEMPLATE =
        "https://api.protomaps.com/tiles/v4/%1$d/%2$d/%3$d.mvt?key=%4$s";

    private ApiConstants() {
        // Utility class
    }
}