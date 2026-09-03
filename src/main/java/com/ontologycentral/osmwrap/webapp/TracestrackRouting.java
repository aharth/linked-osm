package com.ontologycentral.osmwrap.webapp;

import com.ontologycentral.osmwrap.BuildInfo;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Shared trust gate for the Tracestrack tile/vector/terrain/elevation
 * passthrough servlets - the same rule as {@link OverpassRouting}'s
 * Tracestrack branch, factored out since four servlets need it instead of
 * one.
 */
public final class TracestrackRouting {

    private TracestrackRouting() {
        // Utility class
    }

    /**
     * The Tracestrack API key to use for this request, or {@code null} if
     * the request is not trusted (see {@link RateLimitFilter}) or no key is
     * configured.
     */
    public static String key(HttpServletRequest req) {
        if (!OverpassRouting.isTrusted(req)) {
            return null;
        }
        String key = BuildInfo.getTracestrackApiKey();
        return key.isEmpty() ? null : key;
    }
}
