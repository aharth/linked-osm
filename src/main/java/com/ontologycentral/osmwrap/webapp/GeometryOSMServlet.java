package com.ontologycentral.osmwrap.webapp;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.ontologycentral.osmwrap.AcceptHeader;
import com.ontologycentral.osmwrap.OsmElement;
import com.ontologycentral.osmwrap.UpstreamCache;
import com.ontologycentral.osmwrap.UpstreamCache.UpstreamException;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * {@code /geo/osm/{type}/{id}[.json|.wkt|.kml]}: just the geometry of one OSM API element.
 * Same load path as {@link FeatureServlet} (shared cache, StAX parse, one geometry model),
 * so the shape here is byte-for-byte the one embedded in the Turtle and GML outputs.
 */
@SuppressWarnings("serial")
public class GeometryOSMServlet extends HttpServlet {
    private static final Logger _log = Logger.getLogger(GeometryOSMServlet.class.getName());

    public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String pathInfo = req.getPathInfo();
        if (pathInfo == null) {
            resp.sendError(404, "No path specified");
            return;
        }

        // Parse /way/123 or /node/456, or /relation/789
        String[] parts = pathInfo.substring(1).split("/", 2);
        if (parts.length != 2) {
            resp.sendError(404, "Invalid path format");
            return;
        }
        String type = parts[0];
        String id = parts[1];
        if (!"node".equals(type) && !"way".equals(type) && !"relation".equals(type)) {
            resp.sendError(404, "Invalid element type: " + type);
            return;
        }

        // Check for file extension suffix in ID
        String format = "json"; // default to JSON
        boolean explicitFormat = id.contains(".");
        if (explicitFormat) {
            String extension = id.substring(id.lastIndexOf(".") + 1).toLowerCase();
            if ("json".equals(extension) || "wkt".equals(extension) || "kml".equals(extension)) {
                format = extension;
            } else {
                // Unknown extension - reject with 406
                resp.sendError(406, "Unsupported format: ." + extension);
                return;
            }
            // Strip the extension from the ID
            id = id.substring(0, id.indexOf("."));
        }

        // Content negotiation: only when the URL gave no explicit extension — a suffix
        // always wins (family convention). Compare kml/wkt against geo+json with
        // prefers(), not a bare maxQ()>0: a generic "Accept: */*" (the curl/most-clients
        // default) matches every candidate via wildcard with equal q, so a raw maxQ()>0
        // check treated "KML is acceptable" as "KML is preferred" and hijacked plain
        // requests with no real Accept opinion into KML. prefers() only fires on an
        // actual, strict preference for kml/wkt over geo+json.
        if (!explicitFormat) {
            List<AcceptHeader.AcceptType> accepted = AcceptHeader.parse(req.getHeader("Accept"));
            if (AcceptHeader.prefers(accepted, "application", "vnd.google-earth.kml+xml",
                    "application", "geo+json")) {
                format = "kml";
            } else if (AcceptHeader.prefers(accepted, "application", "wkt",
                    "application", "geo+json")) {
                format = "wkt";
            }
        }

        UpstreamCache cache = (UpstreamCache) getServletContext().getAttribute(UpstreamCache.ATTR);
        String upstreamUrl = OsmElement.upstreamUrl(type, id);
        OutputStream os = resp.getOutputStream();

        try {
            OsmElement element = OsmElement.load(cache, type, id);

            String body;
            switch (format) {
                case "wkt":
                    resp.setContentType("application/wkt");
                    body = element.geometry().toWKT();
                    break;
                case "kml":
                    resp.setContentType("application/vnd.google-earth.kml+xml");
                    body = element.toKmlDocument();
                    break;
                default:
                    resp.setContentType("application/geo+json");
                    body = element.geometry().toGeoJSON();
            }
            os.write(body.getBytes(StandardCharsets.UTF_8));

            resp.setHeader("Cache-Control", "public");
            resp.setHeader("Expires", ZonedDateTime.now().plusDays(1).format(Listener.RFC822));
        } catch (UpstreamException e) {
            UpstreamErrors.relay(e, resp);
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
}
