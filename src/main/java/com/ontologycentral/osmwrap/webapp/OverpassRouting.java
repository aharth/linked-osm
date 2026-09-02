package com.ontologycentral.osmwrap.webapp;

import com.ontologycentral.osmwrap.ApiConstants;
import com.ontologycentral.osmwrap.BuildInfo;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Picks which Overpass API base(s) a request should use.
 *
 * <p>A request that carried a valid {@code Authorization: Bearer} key (see
 * {@link RateLimitFilter}) is routed to the paid Tracestrack endpoint, if a
 * key is configured via {@link BuildInfo#getTracestrackApiKey()}. Everyone
 * else gets the free public mirrors in {@link ApiConstants#OVERPASS_API_BASES}.
 */
public final class OverpassRouting {

    static final String TRUSTED_ATTR = "osmwrap.trustedOverpass";

    private OverpassRouting() {
        // Utility class
    }

    static boolean isTrusted(HttpServletRequest req) {
        return Boolean.TRUE.equals(req.getAttribute(TRUSTED_ATTR));
    }

    /** First (and possibly only) base to use for this request. */
    public static String base(HttpServletRequest req) {
        return bases(req)[0];
    }

    /** Full fallback chain to use for this request. */
    public static String[] bases(HttpServletRequest req) {
        if (isTrusted(req)) {
            String key = BuildInfo.getTracestrackApiKey();
            if (!key.isEmpty()) {
                return new String[] { String.format(ApiConstants.TRACESTRACK_OVERPASS_TEMPLATE, key) };
            }
        }
        return ApiConstants.OVERPASS_API_BASES;
    }
}
