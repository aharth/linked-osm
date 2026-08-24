package com.ontologycentral.osmwrap.webapp;

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
     * XML cache keyed by upstream URL. Weight is the char count of the XML string
     * (≈ UTF-8 byte count for ASCII-heavy OSM XML). Capped at 64 MB to bound heap use.
     */
    private final Cache<String, Object[]> xmlCache = Caffeine.newBuilder()
            .maximumWeight(2L * 1024 * 1024 * 1024)
            .weigher((String k, Object[] v) -> ((String) v[0]).length())
            .expireAfterWrite(Duration.ofHours(24))
            .build();

    /** Tracks upstream fetches that are currently in progress, keyed by upstream URL. */
    private final ConcurrentHashMap<String, CompletableFuture<Object[]>> inFlight = new ConcurrentHashMap<>();

    /** Maximum number of way members before skipping /full geometry fetch. */
    private static final int MAX_RELATION_WAYS = 200;

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
     * Returns {xml, byteCount} or throws FetchException / IOException.
     * Writes the error response and returns null if upstream returned non-200.
     */
    private Object[] fetchXml(String url, HttpServletResponse resp) throws IOException {
        Object[] cached = xmlCache.getIfPresent(url);
        if (cached != null) {
            _log.info("cache hit: " + url);
            return cached;
        }
        CompletableFuture<Object[]> fresh = new CompletableFuture<>();
        CompletableFuture<Object[]> existing = inFlight.putIfAbsent(url, fresh);
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
            String xml = HttpClientUtil.readToString(response.body());
            response.body().close();
            Object[] result = new Object[]{xml, byteCount};
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

    private static int countWayMembers(String xml) {
        int count = 0;
        int pos = 0;
        String needle = "<member type=\"way\"";
        while ((pos = xml.indexOf(needle, pos)) >= 0) {
            count++;
            pos += needle.length();
        }
        return count;
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
        // Relations: fetch simple URL first to count way members; only fetch /full if small enough.
        String archive;
        if ((format.equals("rdf") || format.equals("gml")) && ctrl.equals("/way/")) {
            archive = ApiConstants.OSM_API_BASE + ctrl + id + "/full";
        } else {
            archive = ApiConstants.OSM_API_BASE + ctrl + id;
        }

        _log.info("retrieving " + archive);

        try {
            String xml;
            long byteCount;
            String upstreamUrl = archive;

            if (ctrl.equals("/relation/")) {
                // Two-step fetch: probe the simple relation first to count way members.
                // If the relation has many ways, /full would be hundreds of MB — skip it
                // and serve the relation metadata without geometry instead.
                Object[] simpleResult = fetchXml(archive, resp);
                if (simpleResult == null) return;
                String simpleXml = (String) simpleResult[0];
                int wayCount = countWayMembers(simpleXml);
                _log.info("relation " + id + " has " + wayCount + " way members");
                if (wayCount > MAX_RELATION_WAYS) {
                    _log.info("relation " + id + ": " + wayCount + " ways > " + MAX_RELATION_WAYS + ", refusing /full");
                    resp.sendError(413, "Relation " + id + " has " + wayCount + " way members (limit "
                            + MAX_RELATION_WAYS + "). Use /geo/overpass/relation/" + id + ".json for geometry.");
                    return;
                }
                if (wayCount > 0) {
                    upstreamUrl = ApiConstants.OSM_API_BASE + ctrl + id + "/full";
                    _log.info("retrieving full: " + upstreamUrl);
                    Object[] fullResult = fetchXml(upstreamUrl, resp);
                    if (fullResult == null) return;
                    xml = (String) fullResult[0];
                    byteCount = (long) fullResult[1];
                } else {
                    xml = simpleXml;
                    byteCount = (long) simpleResult[1];
                }
            } else {
                Object[] result = fetchXml(archive, resp);
                if (result == null) return;
                xml = (String) result[0];
                byteCount = (long) result[1];
            }

            // --- Format dispatch ---

            if (format.equals("json")) {
                resp.setContentType("application/geo+json");
                String elementType = ctrl.substring(1, ctrl.length() - 1);
                GeoJsonConverter.GeometryResult geomResult = GeoJsonConverter.extractGeometryJson(xml, elementType, id);
                String geoJson = GeoJsonConverter.osmFeatureToGeoJson(xml, elementType, id, geomResult.geometryJson, "/osm");
                os.write(geoJson.getBytes(StandardCharsets.UTF_8));
            } else if (format.equals("gml")) {
                Templates tmpl = (Templates) ctx.getAttribute(ctrl + ".gml");
                Transformer t = tmpl.newTransformer();
                resp.setContentType("application/gml+xml");
                t.transform(new StreamSource(new StringReader(xml)), new StreamResult(os));
            } else {
                // RDF format - use existing XSLT transformation
                Templates tmpl = (Templates) ctx.getAttribute(ctrl);
                Transformer t = tmpl.newTransformer();
                t.setParameter("source-prefix", "/osm");
                t.setParameter("upstream-url", upstreamUrl);
                if (byteCount >= 0) {
                    t.setParameter("upstream-bytes", byteCount);
                }
                if (ctrl.equals("/relation/")) {
                    t.setParameter("element-id", id);
                }
                resp.setContentType("text/turtle");
                _log.info("applying xslt");
                t.transform(new StreamSource(new StringReader(xml)), new StreamResult(os));
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
