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
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import com.ontologycentral.osmwrap.AcceptHeader;
import com.ontologycentral.osmwrap.ApiConstants;
import com.ontologycentral.osmwrap.HttpClientUtil;
import com.ontologycentral.osmwrap.UrlBuilder;

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
        if (id.contains(".")) {
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

        // Content negotiation: check Accept header for format preference (if no extension specified)
        if ("json".equals(format)) {
            List<AcceptHeader.AcceptType> accepted = AcceptHeader.parse(req.getHeader("Accept"));
            if (AcceptHeader.maxQ(accepted, "application", "vnd.google-earth.kml+xml") > 0) {
                format = "kml";
            } else if (AcceptHeader.maxQ(accepted, "application", "wkt") > 0) {
                format = "wkt";
            }
        }

        // Build Overpass API query for geometry data
        String query = UrlBuilder.buildOverpassGeometryQuery(elementType, id);
        String archive = ApiConstants.OVERPASS_API_BASE + "?data=" + URLEncoder.encode(query, StandardCharsets.UTF_8);

        _log.info("retrieving " + archive);

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
            String osmJson = HttpClientUtil.readToString(is);

            if ("json".equals(format)) {
                // Extract only the geometry from the OSM JSON
                String geometry = extractGeometry(osmJson);

                resp.setContentType("application/geo+json");
                os.write(geometry.getBytes(StandardCharsets.UTF_8));
            } else if ("wkt".equals(format)) {
                // Convert geometry to WKT format
                String wkt = convertToWkt(osmJson);

                resp.setContentType("application/wkt");
                os.write(wkt.getBytes(StandardCharsets.UTF_8));
            } else if ("kml".equals(format)) {
                // Convert geometry to KML format
                String kml = convertToKml(osmJson, elementType, id);

                resp.setContentType("application/vnd.google-earth.kml+xml");
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

    private String extractGeometry(String osmJson) {
        // Extract just the geometry object from the OSM JSON
        try {
            if (osmJson.contains("\"elements\":[]")) {
                return "{\"type\":\"GeometryCollection\",\"geometries\":[]}";
            }

            // Find geometry array in JSON
            int geoIdx = osmJson.indexOf("\"geometry\"");
            if (geoIdx < 0) {
                return "{\"type\":\"GeometryCollection\",\"geometries\":[]}";
            }

            int bracketIdx = osmJson.indexOf("[", geoIdx);
            if (bracketIdx < 0) {
                return "{\"type\":\"GeometryCollection\",\"geometries\":[]}";
            }

            String geometryType = osmJson.contains("\"type\":\"node\"") ? "Point" : "LineString";

            if ("Point".equals(geometryType)) {
                Pattern latPattern = Pattern.compile("\"lat\"\\s*:\\s*([-\\d.]+)");
                Pattern lonPattern = Pattern.compile("\"lon\"\\s*:\\s*([-\\d.]+)");
                Matcher latMatcher = latPattern.matcher(osmJson);
                Matcher lonMatcher = lonPattern.matcher(osmJson);
                if (latMatcher.find() && lonMatcher.find()) {
                    String lon = lonMatcher.group(1);
                    String lat = latMatcher.group(1);
                    return "{\"type\":\"Point\",\"coordinates\":[" + lon + "," + lat + "]}";
                }
            } else {
                // For LineString, extract coordinates
                int endIdx = findMatchingBracket(osmJson, bracketIdx);
                if (endIdx > bracketIdx) {
                    String coordinates = osmJson.substring(bracketIdx, endIdx + 1);
                    return "{\"type\":\"LineString\",\"coordinates\":" + coordinates + "}";
                }
            }

            return "{\"type\":\"GeometryCollection\",\"geometries\":[]}";
        } catch (Exception e) {
            _log.warning("Error extracting geometry: " + e.getMessage());
            return "{\"type\":\"GeometryCollection\",\"geometries\":[]}";
        }
    }

    private String convertToWkt(String osmJson) {
        try {
            if (osmJson.contains("\"elements\":[]")) {
                return "GEOMETRYCOLLECTION()";
            }

            String geometryType = osmJson.contains("\"type\":\"node\"") ? "Point" : "LineString";

            if ("Point".equals(geometryType)) {
                Pattern latPattern = Pattern.compile("\"lat\"\\s*:\\s*([-\\d.]+)");
                Pattern lonPattern = Pattern.compile("\"lon\"\\s*:\\s*([-\\d.]+)");
                Matcher latMatcher = latPattern.matcher(osmJson);
                Matcher lonMatcher = lonPattern.matcher(osmJson);
                if (latMatcher.find() && lonMatcher.find()) {
                    String lat = latMatcher.group(1);
                    String lon = lonMatcher.group(1);
                    return "POINT(" + lon + " " + lat + ")";
                }
            } else {
                int geoIdx = osmJson.indexOf("\"geometry\"");
                int bracketIdx = osmJson.indexOf("[", geoIdx);
                if (bracketIdx > 0) {
                    int endIdx = findMatchingBracket(osmJson, bracketIdx);
                    String coordinates = osmJson.substring(bracketIdx, endIdx + 1);
                    String wktCoords = coordinates.replaceAll("\\[\\[", "(").replaceAll("\\]\\]", ")").replaceAll("\\],\\[", ", ").replaceAll(",\\s*", " ");
                    return "LINESTRING(" + wktCoords.replaceAll("[\\[\\]]", "") + ")";
                }
            }

            return "GEOMETRYCOLLECTION()";
        } catch (Exception e) {
            _log.warning("Error converting to WKT: " + e.getMessage());
            return "GEOMETRYCOLLECTION()";
        }
    }

    private String convertToKml(String osmJson, String elementType, String id) {
        try {
            StringBuilder kml = new StringBuilder();
            kml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            kml.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n");
            kml.append("  <Document>\n");
            kml.append("    <Placemark>\n");
            kml.append("      <name>").append(elementType).append(" ").append(id).append("</name>\n");

            if (osmJson.contains("\"elements\":[]")) {
                kml.append("      <MultiGeometry/>\n");
            } else if (osmJson.contains("\"type\":\"node\"")) {
                Pattern latPattern = Pattern.compile("\"lat\"\\s*:\\s*([-\\d.]+)");
                Pattern lonPattern = Pattern.compile("\"lon\"\\s*:\\s*([-\\d.]+)");
                Matcher latMatcher = latPattern.matcher(osmJson);
                Matcher lonMatcher = lonPattern.matcher(osmJson);
                if (latMatcher.find() && lonMatcher.find()) {
                    String lat = latMatcher.group(1);
                    String lon = lonMatcher.group(1);
                    kml.append("      <Point>\n");
                    kml.append("        <coordinates>").append(lon).append(",").append(lat).append(",0</coordinates>\n");
                    kml.append("      </Point>\n");
                }
            } else {
                int geoIdx = osmJson.indexOf("\"geometry\"");
                int bracketIdx = osmJson.indexOf("[", geoIdx);
                if (bracketIdx > 0) {
                    kml.append("      <LineString>\n");
                    kml.append("        <coordinates>\n");

                    Pattern coordPattern = Pattern.compile("\\[([-\\d.]+),\\s*([-\\d.]+)\\]");
                    Matcher coordMatcher = coordPattern.matcher(osmJson);

                    while (coordMatcher.find()) {
                        String lon = coordMatcher.group(1);
                        String lat = coordMatcher.group(2);
                        kml.append("          ").append(lon).append(",").append(lat).append(",0\n");
                    }

                    kml.append("        </coordinates>\n");
                    kml.append("      </LineString>\n");
                }
            }

            kml.append("    </Placemark>\n");
            kml.append("  </Document>\n");
            kml.append("</kml>");

            return kml.toString();
        } catch (Exception e) {
            _log.warning("Error converting to KML: " + e.getMessage());
            return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><kml xmlns=\"http://www.opengis.net/kml/2.2\"/>";
        }
    }

    private int findMatchingBracket(String str, int openPos) {
        int count = 1;
        for (int i = openPos + 1; i < str.length(); i++) {
            if (str.charAt(i) == '[') count++;
            else if (str.charAt(i) == ']') {
                count--;
                if (count == 0) return i;
            }
        }
        return -1;
    }
}
