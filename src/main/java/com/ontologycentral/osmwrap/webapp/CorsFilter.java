package com.ontologycentral.osmwrap.webapp;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Adds permissive CORS headers so a browser SPA can fetch the wrapper's
 * data-serving paths cross-origin.
 *
 * <p>Sets {@code Access-Control-Allow-Origin} ({@code *}),
 * {@code Access-Control-Allow-Methods} and {@code Access-Control-Allow-Headers} on
 * every response, and answers an {@code OPTIONS} preflight with
 * {@code 204 No Content}. {@code Authorization} and {@code DPoP} are listed
 * explicitly so a Solid SPA whose authenticated {@code fetch} attaches a DPoP-bound
 * token to every request — including a cross-origin GET of a public resource —
 * clears the preflight (a wildcard never covers {@code Authorization}; {@code DPoP}
 * is a custom header).
 */
public class CorsFilter implements Filter {

    /** {@code Access-Control-Allow-Origin} response header name. */
    static final String ALLOW_ORIGIN = "Access-Control-Allow-Origin";

    /** {@code Access-Control-Allow-Methods} response header name. */
    static final String ALLOW_METHODS = "Access-Control-Allow-Methods";

    /** {@code Access-Control-Allow-Headers} response header name. */
    static final String ALLOW_HEADERS = "Access-Control-Allow-Headers";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (response instanceof HttpServletResponse) {
            HttpServletResponse resp = (HttpServletResponse) response;
            resp.setHeader(ALLOW_ORIGIN, "*");
            resp.setHeader(ALLOW_METHODS, "GET, OPTIONS");
            resp.setHeader(ALLOW_HEADERS, "Accept, Content-Type, Authorization, DPoP");

            if (request instanceof HttpServletRequest
                    && "OPTIONS".equalsIgnoreCase(((HttpServletRequest) request).getMethod())) {
                resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
