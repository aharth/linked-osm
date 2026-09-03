package com.ontologycentral.osmwrap.webapp;

import com.ontologycentral.osmwrap.ApiConstants;
import com.ontologycentral.osmwrap.BuildInfo;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Picks the Protomaps tile URL for a request, trusted callers only.
 *
 * <p>Unlike {@link OverpassRouting}, there is no free fallback here:
 * Protomaps' hosted API is entirely key-gated, so an untrusted request (see
 * {@link RateLimitFilter}) or a missing {@link BuildInfo#getProtomapsApiKey()}
 * has nothing to route to at all.
 */
public final class ProtomapsRouting {

    private ProtomapsRouting() {
        // Utility class
    }

    /**
     * The upstream Protomaps tile URL for this request's z/x/y, or {@code null}
     * if the request is not trusted or no Protomaps key is configured.
     */
    public static String tileUrl(HttpServletRequest req, int z, int x, int y) {
        if (!OverpassRouting.isTrusted(req)) {
            return null;
        }
        String key = BuildInfo.getProtomapsApiKey();
        if (key.isEmpty()) {
            return null;
        }
        return String.format(ApiConstants.PROTOMAPS_TILE_TEMPLATE, z, x, y, key);
    }
}
