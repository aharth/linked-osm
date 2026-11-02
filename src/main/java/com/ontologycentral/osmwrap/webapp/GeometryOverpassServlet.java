package com.ontologycentral.osmwrap.webapp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.ontologycentral.osmwrap.AcceptHeader;
import com.ontologycentral.osmwrap.HttpClientUtil;
import com.ontologycentral.osmwrap.OsmDocument;
import com.ontologycentral.osmwrap.UrlBuilder;
import com.ontologycentral.osmwrap.geometry.Geometry;
import com.ontologycentral.osmwrap.geometry.GeometryBuilder;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@SuppressWarnings("serial")
public class GeometryOverpassServlet extends HttpServlet {
    private static final Logger _log = Logger.getLogger(GeometryOverpassServlet.class.getName());

    public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        OutputStream os = resp.getOutputStream();

        String pathInfo = req.getPathInfo();
        if (pathInfo == null) {
            resp.sendError(404, "No path specified");
            return;
        }

        // Parse /way/123 or /type/id
        String[] parts = pathInfo.substring(1).split("/", 2);
        String elementType;
        String id;

        if (parts.length == 2) {
            elementType = parts[0];
            id = parts[1];
        } else {
            resp.sendError(404, "Invalid path format");
            return;
        }

        // Check for file extension suffix in ID
        String format = "json"; // default to JSON
        boolean explicitFormat = id.contains(".");
        if (explicitFormat) {
            String extension = id.substring(id.lastIndexOf(".") + 1).toLowerCase();
            if ("json".equals(extension)) {
                format = "json";
            } else if ("wkt".equals(extension)) {
                format = "wkt";
            } else if ("kml".equals(extension)) {
                format = "kml";
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

        // Build Overpass API query for geometry data
        String query = UrlBuilder.buildOverpassGeometryQuery(elementType, id);
        String archive = OverpassRouting.base(req) + "?data=" + URLEncoder.encode(query, StandardCharsets.UTF_8);

        _log.info("retrieving " + OverpassRouting.redact(archive));

        try {
            HttpResponse<InputStream> response = HttpClientUtil.get(archive);

            int responseCode = response.statusCode();
            if (responseCode != 200) {
                resp.setStatus(responseCode);
                response.headers().firstValue("Content-Type").ifPresent(resp::setContentType);
                HttpClientUtil.copyStream(response.body(), resp.getOutputStream());
                return;
            }

            InputStream is = response.body();
            // OSM XML as the API's /full answers it: the same parser and geometry builder as
            // /geo/osm, so both routes give the same POLYGON, MULTIPOLYGON, LINESTRING or POINT
            OsmDocument doc = OsmDocument.parse(is, elementType, id);
            Geometry geometry = GeometryBuilder.build(doc);
            if (doc.primary() == null) {
                resp.sendError(404, elementType + " " + id + " not found in Overpass");
                is.close();
                return;
            }

            if ("json".equals(format)) {
                resp.setContentType("application/geo+json");
                os.write(geometry.toGeoJSON().getBytes(StandardCharsets.UTF_8));
            } else if ("wkt".equals(format)) {
                resp.setContentType("application/wkt");
                os.write(geometry.toWKT().getBytes(StandardCharsets.UTF_8));
            } else if ("kml".equals(format)) {
                resp.setContentType("application/vnd.google-earth.kml+xml");
                String kml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n  <Document>\n    <Placemark>\n"
                        + "      <name>" + elementType + " " + id + "</name>\n      " + geometry.toKML() + "\n"
                        + "    </Placemark>\n  </Document>\n</kml>";
                os.write(kml.getBytes(StandardCharsets.UTF_8));
            } else {
                resp.sendError(406, "Unsupported format: " + format);
                is.close();
                return;
            }

            resp.setHeader("Cache-Control", "public");
            resp.setHeader("Expires", ZonedDateTime.now().plusDays(1).format(Listener.RFC822));

            is.close();
        } catch (IOException e) {
            resp.sendError(HttpClientUtil.errorStatus(e), OverpassRouting.redact(archive) + ": " + OverpassRouting.redact(e.getMessage()));
            _log.log(Level.SEVERE, OverpassRouting.redact(e.getMessage()), e);
            return;
        } catch (RuntimeException e) {
            resp.sendError(500, OverpassRouting.redact(archive) + ": " + OverpassRouting.redact(e.getMessage()));
            _log.log(Level.SEVERE, OverpassRouting.redact(e.getMessage()), e);
            return;
        }

        os.close();
    }
}
