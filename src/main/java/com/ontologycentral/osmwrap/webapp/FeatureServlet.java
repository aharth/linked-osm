package com.ontologycentral.osmwrap.webapp;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.ontologycentral.osmwrap.AcceptHeader;
import com.ontologycentral.osmwrap.OsmElement;
import com.ontologycentral.osmwrap.UpstreamCache;
import com.ontologycentral.osmwrap.UpstreamCache.UpstreamException;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

/**
 * {@code /osm/node/*}, {@code /osm/way/*}, {@code /osm/relation/*}: one OSM API element as
 * Turtle (default; {@code RdfFilter} converts to RDF/XML on request), GeoJSON, a GML
 * feature collection, or the HTML view ({@code element.html}) for browsers. All three
 * element types and all data formats go through the same
 * steps: {@link OsmElement#load} (cache → StAX parse → geometry), then either the GeoJSON
 * feature builder or one of two stylesheets that receive the geometry as a parameter.
 */
@SuppressWarnings("serial")
public class FeatureServlet extends HttpServlet {
    private static final Logger _log = Logger.getLogger(FeatureServlet.class.getName());

    public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String pathInfo = req.getPathInfo();
        if (pathInfo == null || !pathInfo.startsWith("/")) {
            resp.sendError(404, "No path specified");
            return;
        }

        // Element type from the servlet mapping (/osm/node, /osm/way, /osm/relation).
        String servletPath = req.getServletPath();
        String type = servletPath.substring(servletPath.lastIndexOf('/') + 1);
        if (!"node".equals(type) && !"way".equals(type) && !"relation".equals(type)) {
            resp.sendError(404, "Invalid path");
            return;
        }

        Negotiated n = negotiate(pathInfo.substring(1), req.getHeader("Accept"));
        if (n == null) {
            resp.sendError(404, "Invalid path");
            return;
        }
        String id = n.id();
        String format = n.format();

        // Browsers get the HTML view (Leaflet map + property table; the page itself
        // fetches /osm/{type}/{id}.json). Same rule as linked-adv's /oid/{oid}: .html
        // suffix, or an Accept that STRICTLY prefers text/html over the data formats.
        if (format.equals("html")) {
            // Written here rather than forwarded to the container's default servlet: on
            // Tomcat that servlet hands static files to the connector (sendfile), which
            // bypasses the capturing wrapper RdfFilter puts around every response, and the
            // body arrived empty in production (200, Content-Length 0). Serving the bytes
            // ourselves behaves the same in every container.
            resp.setHeader("Vary", "Accept");
            resp.setContentType("text/html;charset=UTF-8");
            byte[] page = elementPage(getServletContext());
            resp.setContentLength(page.length);
            resp.getOutputStream().write(page);
            return;
        }

        ServletContext ctx = getServletContext();
        UpstreamCache cache = (UpstreamCache) ctx.getAttribute(UpstreamCache.ATTR);
        String upstreamUrl = OsmElement.upstreamUrl(type, id);
        OutputStream os = resp.getOutputStream();

        try {
            OsmElement element = OsmElement.load(cache, type, id);

            if (format.equals("json")) {
                resp.setContentType("application/geo+json");
                os.write(element.toGeoJsonFeature("/osm").getBytes(StandardCharsets.UTF_8));
            } else {
                Templates tmpl = (Templates) ctx.getAttribute(
                        format.equals("gml") ? Listener.FEATURE_GML : Listener.FEATURE);
                Transformer t = tmpl.newTransformer();
                setFeatureParameters(t, element);
                resp.setContentType(format.equals("gml") ? "application/gml+xml" : "text/turtle");
                t.transform(new StreamSource(new StringReader(element.doc().strippedXml())), new StreamResult(os));
            }

            resp.setHeader("Cache-Control", "public");
            resp.setHeader("Expires", ZonedDateTime.now().plusDays(1).format(Listener.RFC822));
        } catch (UpstreamException e) {
            UpstreamErrors.relay(e, resp);
            return;
        } catch (TransformerException e) {
            _log.log(Level.SEVERE, e.getMessage(), e);
            resp.sendError(500, e.getMessage());
            return;
        } catch (IOException e) {
            _log.log(Level.SEVERE, e.getMessage(), e);
            UpstreamErrors.fail(e, upstreamUrl, resp);
            return;
        } catch (RuntimeException e) {
            _log.log(Level.SEVERE, e.getMessage(), e);
            resp.sendError(500, upstreamUrl + ": " + e.getMessage());
            return;
        }

        os.close();
    }

    private static volatile byte[] elementPage;

    /** {@code /element.html} from the webapp, read once. */
    static byte[] elementPage(ServletContext ctx) throws IOException {
        byte[] page = elementPage;
        if (page == null) {
            try (java.io.InputStream in = ctx.getResourceAsStream("/element.html")) {
                if (in == null) throw new IOException("element.html not in webapp");
                page = in.readAllBytes();
            }
            elementPage = page;
        }
        return page;
    }

    /** Outcome of format negotiation: the element id and one of rdf, json, gml, html. */
    record Negotiated(String id, String format) {}

    /**
     * Format from the path's extension when it has one, otherwise from the Accept header;
     * Turtle when nothing is preferred. {@code .ttl} and {@code .rdf} both mean "rdf" here
     * (RdfFilter serves .rdf as RDF/XML). Returns null for an empty id.
     */
    static Negotiated negotiate(String path, String accept) {
        String id;
        String format;
        if (path.endsWith(".json")) {
            format = "json";
            id = path.substring(0, path.length() - 5);
        } else if (path.endsWith(".html")) {
            format = "html";
            id = path.substring(0, path.length() - 5);
        } else if (path.endsWith(".rdf") || path.endsWith(".ttl")) {
            format = "rdf";
            id = path.substring(0, path.length() - 4);
        } else if (path.endsWith(".gml")) {
            format = "gml";
            id = path.substring(0, path.length() - 4);
        } else {
            id = path;
            List<AcceptHeader.AcceptType> accepted = AcceptHeader.parse(accept);
            double qJson = Math.max(AcceptHeader.maxQ(accepted, "application", "geo+json"),
                    AcceptHeader.maxQ(accepted, "application", "json"));
            double qRdf = Math.max(AcceptHeader.maxQ(accepted, "application", "rdf+xml"),
                    AcceptHeader.maxQ(accepted, "text", "turtle"));
            double qGml = AcceptHeader.maxQ(accepted, "application", "gml+xml");
            if (AcceptHeader.prefersHtml(accept)) {
                format = "html";
            } else if (qGml > qRdf && qGml > qJson) {
                format = "gml";
            } else if (qJson > qRdf) {
                format = "json";
            } else {
                format = "rdf";
            }
        }
        // Strip any remaining extensions (e.g. from malformed URLs like 123.json.json)
        if (id.contains(".")) {
            id = id.substring(0, id.indexOf('.'));
        }
        return id.isEmpty() ? null : new Negotiated(id, format);
    }

    /**
     * The parameters both feature stylesheets take. Everything derived from member
     * geometry is computed in Java and passed in as strings (Saxon rejects a Java
     * {@code Double} where the stylesheet compares strings); the stylesheets never see
     * member nodes or ways.
     */
    static void setFeatureParameters(Transformer t, OsmElement element) {
        t.setParameter("source-prefix", "/osm");
        t.setParameter("upstream-url", element.upstreamUrl());
        if (element.fetched().byteCount() >= 0) {
            t.setParameter("upstream-bytes", element.fetched().byteCount());
        }
        t.setParameter("element-type", element.type());
        t.setParameter("element-id", element.id());
        double[] centroid = element.doc().centroid();
        if (centroid != null) {
            t.setParameter("centroid-lon", Double.toString(centroid[0]));
            t.setParameter("centroid-lat", Double.toString(centroid[1]));
        }
        String gml = element.geometry().toGML();
        if (gml != null) {
            t.setParameter("geometry-gml", gml);
        }
    }
}
