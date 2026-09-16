package com.ontologycentral.osmwrap.webapp;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import com.ontologycentral.osmwrap.AcceptHeader;
import com.ontologycentral.osmwrap.ApiConstants;
import com.ontologycentral.osmwrap.GeoJsonConverter;
import com.ontologycentral.osmwrap.HttpClientUtil;
import com.ontologycentral.osmwrap.OsmFullDocument;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

@SuppressWarnings("serial")
public class FeatureServlet extends HttpServlet {
    private static final Logger _log = Logger.getLogger(FeatureServlet.class.getName());

    /**
     * A cached upstream response: the raw UTF-8 body and the {@code Content-Length} the
     * upstream declared (-1 when it sent none, e.g. chunked {@code /full} responses).
     */
    private record Fetched(byte[] body, long byteCount) {}

    /**
     * Response cache keyed by upstream URL, weighted by raw body bytes. Bodies are kept as
     * bytes, not strings: Saxon and the StAX relation parser both read bytes directly, and a
     * {@code /full} relation response can be tens of MB. Capped at 2 GB total, 24 h TTL.
     */
    private final Cache<String, Fetched> xmlCache = Caffeine.newBuilder()
            .maximumWeight(2L * 1024 * 1024 * 1024)
            .weigher((String k, Fetched v) -> v.body().length)
            .expireAfterWrite(Duration.ofHours(24))
            .build();

    /** Tracks upstream fetches that are currently in progress, keyed by upstream URL. */
    private final ConcurrentHashMap<String, CompletableFuture<Fetched>> inFlight = new ConcurrentHashMap<>();

    /** Signals that an upstream fetch returned a non-200 status. */
    private static final class FetchException extends Exception {
        final int status;
        FetchException(int status, String msg) {
            super(msg);
            this.status = status;
        }
    }

    /**
     * Fetch XML from upstream with cache and in-flight deduplication.
     * Returns the raw body or throws FetchException / IOException.
     * Writes the error response and returns null if upstream returned non-200.
     */
    private Fetched fetchXml(String url, HttpServletResponse resp) throws IOException {
        Fetched cached = xmlCache.getIfPresent(url);
        if (cached != null) {
            _log.info("cache hit: " + url);
            return cached;
        }
        CompletableFuture<Fetched> fresh = new CompletableFuture<>();
        CompletableFuture<Fetched> existing = inFlight.putIfAbsent(url, fresh);
        if (existing != null) {
            _log.info("joining in-flight fetch: " + url);
            try {
                return existing.join();
            } catch (CompletionException ce) {
                Throwable cause = ce.getCause();
                int errStatus = (cause instanceof FetchException) ? ((FetchException) cause).status : 502;
                resp.sendError(errStatus, cause.getMessage());
                return null;
            }
        }
        try {
            HttpResponse<InputStream> response = HttpClientUtil.get(url);
            int responseCode = response.statusCode();
            if (responseCode != 200) {
                fresh.completeExceptionally(new FetchException(responseCode, "upstream " + responseCode));
                resp.setStatus(responseCode);
                response.headers().firstValue("Content-Type").ifPresent(resp::setContentType);
                HttpClientUtil.copyStream(response.body(), resp.getOutputStream());
                return null;
            }
            long byteCount = response.headers().firstValueAsLong("content-length").orElse(-1L);
            byte[] body;
            try (InputStream is = response.body()) {
                body = is.readAllBytes();
            }
            Fetched result = new Fetched(body, byteCount);
            xmlCache.put(url, result);
            fresh.complete(result);
            return result;
        } catch (IOException | RuntimeException e) {
            fresh.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(url, fresh);
        }
    }

    /**
     * Relation output in all three formats from a stream-parsed {@code /full} response.
     * The XSLT stylesheets get a stripped document (relations only, no member node/way
     * elements) plus the centroid and, for Turtle, the GML geometry as parameters, so Saxon
     * never builds a tree over the member geometry.
     */
    private void serveRelation(ServletContext ctx, HttpServletResponse resp, OutputStream os,
            String id, String format, String upstreamUrl, Fetched fetched)
            throws IOException, TransformerException {
        OsmFullDocument doc = OsmFullDocument.parse(new ByteArrayInputStream(fetched.body()));
        OsmFullDocument.Relation rel = doc.relation(id);
        _log.info("relation " + id + ": " + doc.nodes().size() + " nodes, " + doc.ways().size()
                + " ways, " + doc.relations().size() + " relations, "
                + (rel == null ? "primary missing" : rel.members().size() + " members"));

        if (format.equals("json")) {
            resp.setContentType("application/geo+json");
            String geometryJson = GeoJsonConverter.relationGeometryJson(doc, id);
            List<String[]> tags = rel == null ? List.of() : rel.tags();
            String geoJson = GeoJsonConverter.osmFeatureToGeoJson(tags, "relation", id, geometryJson, "/osm");
            os.write(geoJson.getBytes(StandardCharsets.UTF_8));
            return;
        }

        Templates tmpl = (Templates) ctx.getAttribute(format.equals("gml") ? Listener.RELATION_GML : Listener.RELATION);
        Transformer t = tmpl.newTransformer();
        t.setParameter("element-id", id);
        double[] centroid = doc.centroid();
        if (centroid != null) {
            t.setParameter("centroid-lon", Double.toString(centroid[0]));
            t.setParameter("centroid-lat", Double.toString(centroid[1]));
        }
        if (format.equals("gml")) {
            resp.setContentType("application/gml+xml");
        } else {
            t.setParameter("source-prefix", "/osm");
            t.setParameter("upstream-url", upstreamUrl);
            if (fetched.byteCount() >= 0) {
                t.setParameter("upstream-bytes", fetched.byteCount());
            }
            try {
                String gml = GeoJsonConverter.relationGeometryGml(doc, id);
                if (gml != null) {
                    t.setParameter("geometry-gml", gml);
                }
            } catch (RuntimeException e) {
                _log.log(Level.WARNING, "relation " + id + ": geometry-gml build failed: " + e.getMessage(), e);
            }
            resp.setContentType("text/turtle");
            _log.info("applying xslt");
        }
        t.transform(new StreamSource(new StringReader(doc.strippedXml())), new StreamResult(os));
    }

    public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        OutputStream os = resp.getOutputStream();

        String pathInfo = req.getPathInfo();
        if (pathInfo == null) {
            resp.sendError(404, "No path specified");
            return;
        }

        String ctrl = null;
        String id = null;
        String format = "rdf"; // default format

        if (pathInfo.startsWith("/")) {
            // Remove leading slash and extract ID
            String path = pathInfo.substring(1);

            // Check for file extension
            if (path.endsWith(".json")) {
                format = "json";
                id = path.substring(0, path.length() - 5); // remove .json
            } else if (path.endsWith(".rdf")) {
                format = "rdf";
                id = path.substring(0, path.length() - 4); // remove .rdf
            } else if (path.endsWith(".ttl")) {
                format = "rdf";                              // produce RDF/XML; RdfFilter converts to Turtle
                id = path.substring(0, path.length() - 4); // strip .ttl
            } else if (path.endsWith(".gml")) {
                format = "gml";
                id = path.substring(0, path.length() - 4); // remove .gml
            } else {
                // No extension - content-negotiate on Accept header
                id = path;
                List<AcceptHeader.AcceptType> accepted = AcceptHeader.parse(req.getHeader("Accept"));
                double qJson = Math.max(AcceptHeader.maxQ(accepted, "application", "geo+json"),
                        AcceptHeader.maxQ(accepted, "application", "json"));
                double qRdf = Math.max(AcceptHeader.maxQ(accepted, "application", "rdf+xml"),
                        AcceptHeader.maxQ(accepted, "text", "turtle"));
                double qGml = AcceptHeader.maxQ(accepted, "application", "gml+xml");
                if (qGml > qRdf && qGml > qJson) {
                    format = "gml";
                } else if (qJson > qRdf) {
                    format = "json";
                }
            }

            // Strip any remaining extensions (e.g., from malformed URLs like 123.json.json)
            if (id.contains(".")) {
                id = id.substring(0, id.indexOf("."));
            }

            // Determine the type based on servlet mapping
            String servletPath = req.getServletPath();
            if (servletPath.equals("/osm/node")) {
                ctrl = "/node/";
            } else if (servletPath.equals("/osm/way")) {
                ctrl = "/way/";
            } else if (servletPath.equals("/osm/relation")) {
                ctrl = "/relation/";
            }
        }

        if (ctrl == null || id == null) {
            resp.sendError(404, "Invalid path");
            return;
        }

        ServletContext ctx = getServletContext();

        // Ways use /full for rdf and gml (need node coordinates); json uses simple endpoint.
        // Relations always use /full: member ways and nodes come inline, and the response is
        // stream-parsed (OsmFullDocument) rather than held as a string, so its size is bounded
        // by our heap only through the node map, not the raw markup. There is deliberately no
        // member-count refusal any more: the cache above makes an expensive /full fetch happen
        // at most once per 24 h per relation, whatever its size.
        String archive;
        if (ctrl.equals("/relation/")
                || ((format.equals("rdf") || format.equals("gml")) && ctrl.equals("/way/"))) {
            archive = ApiConstants.OSM_API_BASE + ctrl + id + "/full";
        } else {
            archive = ApiConstants.OSM_API_BASE + ctrl + id;
        }

        _log.info("retrieving " + archive);

        try {
            Fetched fetched = fetchXml(archive, resp);
            if (fetched == null) return;
            long byteCount = fetched.byteCount();

            if (ctrl.equals("/relation/")) {
                serveRelation(ctx, resp, os, id, format, archive, fetched);
            } else if (format.equals("json")) {
                resp.setContentType("application/geo+json");
                String xml = new String(fetched.body(), StandardCharsets.UTF_8);
                String elementType = ctrl.substring(1, ctrl.length() - 1);
                GeoJsonConverter.GeometryResult geomResult = GeoJsonConverter.extractGeometryJson(xml, elementType, id);
                String geoJson = GeoJsonConverter.osmFeatureToGeoJson(xml, elementType, id, geomResult.geometryJson, "/osm");
                os.write(geoJson.getBytes(StandardCharsets.UTF_8));
            } else if (format.equals("gml")) {
                Templates tmpl = (Templates) ctx.getAttribute(ctrl + ".gml");
                Transformer t = tmpl.newTransformer();
                resp.setContentType("application/gml+xml");
                t.transform(new StreamSource(new ByteArrayInputStream(fetched.body())), new StreamResult(os));
            } else {
                // RDF format - use existing XSLT transformation
                Templates tmpl = (Templates) ctx.getAttribute(ctrl);
                Transformer t = tmpl.newTransformer();
                t.setParameter("source-prefix", "/osm");
                t.setParameter("upstream-url", archive);
                if (byteCount >= 0) {
                    t.setParameter("upstream-bytes", byteCount);
                }
                resp.setContentType("text/turtle");
                _log.info("applying xslt");
                t.transform(new StreamSource(new ByteArrayInputStream(fetched.body())), new StreamResult(os));
            }

    		resp.setHeader("Cache-Control", "public");
    		resp.setHeader("Expires", ZonedDateTime.now().plusDays(1).format(Listener.RFC822));

        } catch (TransformerException e) {
            _log.log(Level.SEVERE, e.getMessage(), e);
            resp.sendError(500, e.getMessage());
            return;
        } catch (IOException e) {
            resp.sendError(HttpClientUtil.errorStatus(e), archive + ": " + e.getMessage());
            _log.log(Level.SEVERE, e.getMessage(), e);
            return;
        } catch (RuntimeException e) {
            resp.sendError(500, archive + ": " + e.getMessage());
            _log.log(Level.SEVERE, e.getMessage(), e);
            return;
        }

        os.close();
    }

}
