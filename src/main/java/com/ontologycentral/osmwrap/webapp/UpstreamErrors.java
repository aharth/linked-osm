package com.ontologycentral.osmwrap.webapp;

import java.io.IOException;

import com.ontologycentral.osmwrap.HttpClientUtil;
import com.ontologycentral.osmwrap.UpstreamCache.UpstreamException;

import jakarta.servlet.http.HttpServletResponse;

/** The one way OSM API element servlets report upstream failures. */
final class UpstreamErrors {
    private UpstreamErrors() {}

    /** Relay a non-200 upstream answer verbatim: same status, content type and body. */
    static void relay(UpstreamException e, HttpServletResponse resp) throws IOException {
        resp.setStatus(e.status());
        if (e.contentType() != null) resp.setContentType(e.contentType());
        resp.getOutputStream().write(e.body());
    }

    /** Transport failure or malformed upstream XML: 504 for timeouts, 500 otherwise. */
    static void fail(IOException e, String upstreamUrl, HttpServletResponse resp) throws IOException {
        resp.sendError(HttpClientUtil.errorStatus(e), upstreamUrl + ": " + e.getMessage());
    }
}
