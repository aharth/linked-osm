package com.ontologycentral.osmwrap.webapp;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.RDFWriter;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Emulates Apache2 mod_negotiation MultiViews behaviour for extensionless URLs.
 *
 * For a request to /foo (no extension, no direct resource):
 *   1. Scan the directory for files named foo.*
 *   2. Pick the best match via Accept header content negotiation.
 *      TTL variants also satisfy application/rdf+xml requests.
 *   3. Forward internally to the matched file, or convert TTL→RDF/XML with
 *      Jena for RDF/XML clients, setting Content-Location and Vary: Accept.
 *
 * For explicit .rdf requests where no physical .rdf file exists, derives the
 * response from the corresponding .ttl file via Jena on demand.
 *
 * Falls through transparently when the path has an extension or a direct
 * resource exists.
 */
public class MultiViewsFilter implements Filter {

    @Override
    public void init(FilterConfig config) {}

    @Override
    public void destroy() {}

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest  req  = (HttpServletRequest)  request;
        HttpServletResponse resp = (HttpServletResponse) response;
        ServletContext      ctx  = req.getServletContext();

        // Use raw request URI (minus context path) so root detection works regardless
        // of how Tomcat resolves welcome files before or during filter invocation.
        String contextPath = req.getContextPath();
        String rawUri = req.getRequestURI().substring(contextPath.length());

        // Root request: treat as /index so MultiViews can negotiate index.*
        if (rawUri.isEmpty() || "/".equals(rawUri)) rawUri = "/index";

        String path = rawUri;

        int lastSlash = path.lastIndexOf('/');
        String filename = path.substring(lastSlash + 1);
        int dotPos = filename.lastIndexOf('.');
        String ext = dotPos >= 0 ? filename.substring(dotPos + 1) : "";

        // Explicit .rdf request: serve from .ttl via Jena if no physical .rdf exists
        if ("rdf".equals(ext)) {
            try {
                if (ctx.getResource(path) == null) {
                    String ttlPath = path.substring(0, path.length() - 4) + ".ttl";
                    try {
                        if (ctx.getResource(ttlPath) != null) {
                            resp.setHeader("Vary", "Accept");
                            resp.setContentType("application/rdf+xml");
                            convertTtlToRdfXml(ctx, ttlPath, resp.getOutputStream());
                            return;
                        }
                    } catch (java.net.MalformedURLException e2) { /* fall through */ }
                }
            } catch (java.net.MalformedURLException e) { /* fall through */ }
            chain.doFilter(request, response);
            return;
        }

        // Pass through if the path has any other real file extension (letters only)
        // but NOT version-style segments like "4.0".
        if (!ext.isEmpty() && ext.matches("[a-zA-Z]+")) {
            chain.doFilter(request, response);
            return;
        }

        // Pass through if a direct FILE resource exists at this path. A same-named
        // DIRECTORY (getResource() returns a URL ending in "/") must NOT pass
        // through here: that would defer to the servlet container's own
        // add-a-trailing-slash redirect, which is proxy-prefix-unaware and leaks
        // the internal context path. Falling through instead lets the variant
        // search below find e.g. vocab.ttl for /vocab, exactly as it already does
        // index.ttl/index.jsp for /index.
        try {
            java.net.URL resourceUrl = ctx.getResource(path);
            if (resourceUrl != null && !resourceUrl.toString().endsWith("/")) {
                chain.doFilter(request, response);
                return;
            }
        } catch (java.net.MalformedURLException e) {
            chain.doFilter(request, response);
            return;
        }

        // Collect variants: files in the same directory named filename.*
        String dir = lastSlash >= 0 ? path.substring(0, lastSlash + 1) : "/";
        Set<String> dirPaths = ctx.getResourcePaths(dir);
        if (dirPaths == null) {
            chain.doFilter(request, response);
            return;
        }

        List<String> variants = new ArrayList<>();
        String prefix = dir + filename + ".";
        for (String p : dirPaths) {
            if (p.startsWith(prefix) && !p.equals(prefix)) {
                variants.add(p);
            }
        }

        if (variants.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }

        // Content negotiation
        String acceptHeader = req.getHeader("Accept");
        String best = negotiate(variants, acceptHeader, ctx);
        if (best == null) {
            chain.doFilter(request, response);
            return;
        }

        resp.setHeader("Vary", "Accept");

        // If best is .ttl and client prefers RDF/XML, convert inline with Jena
        if (best.endsWith(".ttl")) {
            Map<String, Double> accept = parseAccept(acceptHeader);
            if (score("application/rdf+xml", accept) > score("text/turtle", accept)) {
                String contentLoc = best.substring(1, best.length() - 4) + ".rdf";
                resp.setHeader("Content-Location", contentLoc);
                resp.setContentType("application/rdf+xml");
                convertTtlToRdfXml(ctx, best, resp.getOutputStream());
                return;
            }
        }

        resp.setHeader("Content-Location", best.substring(1)); // strip leading /
        req.getRequestDispatcher(best).forward(request, response);
    }

    // --- Jena conversion ----------------------------------------------------

    private static void convertTtlToRdfXml(ServletContext ctx, String ttlPath, OutputStream out)
            throws IOException, ServletException {
        try {
            java.net.URL url = ctx.getResource(ttlPath);
            Model model = ModelFactory.createDefaultModel();
            RDFParser.create().source(url.openStream()).lang(Lang.TURTLE).parse(model);
            RDFWriter.create().lang(Lang.RDFXML).source(model).output(out);
        } catch (Exception e) {
            throw new ServletException("TTL to RDF/XML conversion failed: " + e.getMessage(), e);
        }
    }

    // --- Content negotiation ------------------------------------------------

    private String negotiate(List<String> variants, String acceptHeader, ServletContext ctx) {
        Map<String, Double> accept = parseAccept(acceptHeader);
        String bestVariant = null;
        double bestScore   = -1;

        for (String variant : variants) {
            String mime  = ctx.getMimeType(variant);
            if (mime == null) mime = "application/octet-stream";
            // Strip parameters from MIME type for matching
            int semi = mime.indexOf(';');
            String baseType = semi >= 0 ? mime.substring(0, semi).trim() : mime;
            double s = score(baseType, accept);
            // TTL can also satisfy RDF/XML requests
            if ("text/turtle".equals(baseType)) {
                double rdfScore = score("application/rdf+xml", accept);
                if (rdfScore > s) s = rdfScore;
            }
            if (s > bestScore) {
                bestScore   = s;
                bestVariant = variant;
            }
        }

        // If nothing matched the Accept header (score 0), still serve the first
        // variant rather than 406 — matches Apache default behaviour
        return bestVariant != null ? bestVariant : variants.get(0);
    }

    private Map<String, Double> parseAccept(String header) {
        Map<String, Double> map = new LinkedHashMap<>();
        if (header == null || header.isBlank()) {
            map.put("*/*", 1.0);
            return map;
        }
        for (String part : header.split(",")) {
            part = part.trim();
            String[] pieces    = part.split(";");
            String   mediaType = pieces[0].trim();
            double   q         = 1.0;
            for (int i = 1; i < pieces.length; i++) {
                String p = pieces[i].trim();
                if (p.startsWith("q=")) {
                    try { q = Double.parseDouble(p.substring(2)); }
                    catch (NumberFormatException ignored) {}
                }
            }
            map.put(mediaType, q);
        }
        return map;
    }

    private double score(String mimeType, Map<String, Double> accept) {
        // Exact match
        Double q = accept.get(mimeType);
        if (q != null) return q;
        // Type wildcard (e.g. application/*)
        String type = mimeType.split("/")[0];
        q = accept.get(type + "/*");
        if (q != null) return q;
        // Full wildcard
        q = accept.get("*/*");
        return q != null ? q : 0.0;
    }
}
