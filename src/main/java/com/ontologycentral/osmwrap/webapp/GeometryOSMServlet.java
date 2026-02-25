package com.ontologycentral.osmwrap.webapp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.logging.Logger;
import java.util.Scanner;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import com.ontologycentral.osmwrap.ApiConstants;
import com.ontologycentral.osmwrap.GeoJsonConverter;
import com.ontologycentral.osmwrap.HttpClientUtil;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@SuppressWarnings("serial")
public class GeometryOSMServlet extends HttpServlet {
	Logger _log = Logger.getLogger(this.getClass().getName());

	public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		OutputStream os = resp.getOutputStream();

		String pathInfo = req.getPathInfo();
		if (pathInfo == null) {
			resp.sendError(404, "No path specified");
			return;
		}

		// Parse /way/123 or /node/456, or /relation/789
		String[] parts = pathInfo.substring(1).split("/", 2);
		String elementType;
		String id;

		if (parts.length == 2) {
			// Format: /way/123
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
			String accept = req.getHeader("Accept");
			if (accept != null) {
				if (accept.contains("application/vnd.google-earth.kml+xml")) {
					format = "kml";
				} else if (accept.contains("application/wkt")) {
					format = "wkt";
				}
			}
		}

		// Fetch element from OSM API to get coordinates and member references
		String osmApiUrl = ApiConstants.OSM_API_BASE + "/" + elementType + "/" + id;

		_log.info("retrieving OSM element " + osmApiUrl);
		System.out.println("retrieving OSM element " + osmApiUrl);

		try {
			HttpResponse<InputStream> response = HttpClientUtil.get(osmApiUrl);

			int responseCode = response.statusCode();
			if (responseCode != 200) {
				resp.sendError(responseCode,
						new String(response.body().readAllBytes(), StandardCharsets.UTF_8).trim());
				return;
			}

			InputStream is = response.body();
			String osmXml = readInputStream(is);
			is.close();

			// Extract geometry from the OSM element
			String geometry = GeoJsonConverter.extractGeometryJson(osmXml, elementType, id);

			if ("json".equals(format)) {
				resp.setContentType("application/geo+json");
				os.write(geometry.getBytes(StandardCharsets.UTF_8));
			} else if ("wkt".equals(format)) {
				String wkt = convertToWkt(geometry);
				resp.setContentType("application/wkt");
				os.write(wkt.getBytes(StandardCharsets.UTF_8));
			} else if ("kml".equals(format)) {
				String kml = convertToKml(geometry, elementType, id);
				resp.setContentType("application/vnd.google-earth.kml+xml");
				os.write(kml.getBytes(StandardCharsets.UTF_8));
			} else {
				resp.sendError(406, "Unsupported format: " + format);
				return;
			}

			resp.setHeader("Cache-Control", "public");
			Calendar c = Calendar.getInstance();
			c.add(Calendar.DATE, 1);
			resp.setHeader("Expires", Listener.RFC822.format(c.getTime()));

		} catch (IOException e) {
			resp.sendError(500, osmApiUrl + ": " + e.getMessage());
			e.printStackTrace();
			return;
		} catch (RuntimeException e) {
			resp.sendError(500, osmApiUrl + ": " + e.getMessage());
			e.printStackTrace();
			return;
		}

		os.close();
	}

	private String readInputStream(InputStream is) throws IOException {
		Scanner scanner = new Scanner(is, StandardCharsets.UTF_8);
		scanner.useDelimiter("\\A");
		return scanner.hasNext() ? scanner.next() : "";
	}

	private String convertToWkt(String geoJson) {
		try {
			if (geoJson.contains("\"type\":\"Point\"")) {
				Pattern coordPattern = Pattern.compile("\"coordinates\"\\s*:\\s*\\[([^,]+),([^\\]]+)\\]");
				Matcher matcher = coordPattern.matcher(geoJson);
				if (matcher.find()) {
					String lon = matcher.group(1);
					String lat = matcher.group(2);
					return "POINT(" + lon + " " + lat + ")";
				}
			} else if (geoJson.contains("\"type\":\"Polygon\"")) {
				Pattern coordPattern = Pattern.compile("\"coordinates\"\\s*:\\s*(\\[\\[.*?\\]\\])");
				Matcher matcher = coordPattern.matcher(geoJson);
				if (matcher.find()) {
					String coordStr = matcher.group(1);
					String wktCoords = coordStr.replaceAll("\\[", "(").replaceAll("\\]", ")").replaceAll("\\],\\s*\\[", ", ").replaceAll(",\\s*", " ");
					return "POLYGON" + wktCoords;
				}
			} else if (geoJson.contains("\"type\":\"MultiPolygon\"")) {
				Pattern coordPattern = Pattern.compile("\"coordinates\"\\s*:\\s*(\\[\\[\\[.*?\\]\\]\\])");
				Matcher matcher = coordPattern.matcher(geoJson);
				if (matcher.find()) {
					String coordStr = matcher.group(1);
					String wktCoords = coordStr.replaceAll("\\[", "(").replaceAll("\\]", ")").replaceAll("\\],\\s*\\[", ", ").replaceAll(",\\s*", " ");
					return "MULTIPOLYGON" + wktCoords;
				}
			} else if (geoJson.contains("\"type\":\"LineString\"")) {
				Pattern coordPattern = Pattern.compile("\"coordinates\"\\s*:\\s*(\\[[^\\]]*\\])");
				Matcher matcher = coordPattern.matcher(geoJson);
				if (matcher.find()) {
					String coordStr = matcher.group(1);
					String wktCoords = coordStr.replaceAll("\\[", "(").replaceAll("\\]", ")").replaceAll("\\],\\s*\\[", ", ").replaceAll(",\\s*", " ");
					return "LINESTRING" + wktCoords;
				}
			}

			return "GEOMETRYCOLLECTION()";
		} catch (Exception e) {
			_log.warning("Error converting to WKT: " + e.getMessage());
			return "GEOMETRYCOLLECTION()";
		}
	}

	private String convertToKml(String geoJson, String elementType, String id) {
		try {
			StringBuilder kml = new StringBuilder();
			kml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
			kml.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n");
			kml.append("  <Document>\n");
			kml.append("    <Placemark>\n");
			kml.append("      <name>").append(elementType).append(" ").append(id).append("</name>\n");

			if (geoJson.contains("\"type\":\"Point\"")) {
				Pattern coordPattern = Pattern.compile("\"coordinates\"\\s*:\\s*\\[([^,]+),([^\\]]+)\\]");
				Matcher matcher = coordPattern.matcher(geoJson);
				if (matcher.find()) {
					String lon = matcher.group(1);
					String lat = matcher.group(2);
					kml.append("      <Point>\n");
					kml.append("        <coordinates>").append(lon).append(",").append(lat).append(",0</coordinates>\n");
					kml.append("      </Point>\n");
				}
			} else if (geoJson.contains("\"type\":\"Polygon\"")) {
				// Polygon output: extract coordinates and build KML Polygon
				Pattern coordPattern = Pattern.compile("\\[([^,]+),([^\\]]+)\\]");
				Matcher matcher = coordPattern.matcher(geoJson);

				kml.append("      <Polygon>\n");
				kml.append("        <outerBoundaryIs>\n");
				kml.append("          <LinearRing>\n");
				kml.append("            <coordinates>\n");

				while (matcher.find()) {
					String lon = matcher.group(1);
					String lat = matcher.group(2);
					kml.append("              ").append(lon).append(",").append(lat).append(",0\n");
				}

				kml.append("            </coordinates>\n");
				kml.append("          </LinearRing>\n");
				kml.append("        </outerBoundaryIs>\n");
				kml.append("      </Polygon>\n");
			} else if (geoJson.contains("\"type\":\"LineString\"")) {
				Pattern coordPattern = Pattern.compile("\\[([^,]+),([^\\]]+)\\]");
				Matcher matcher = coordPattern.matcher(geoJson);

				kml.append("      <LineString>\n");
				kml.append("        <coordinates>\n");

				while (matcher.find()) {
					String lon = matcher.group(1);
					String lat = matcher.group(2);
					kml.append("          ").append(lon).append(",").append(lat).append(",0\n");
				}

				kml.append("        </coordinates>\n");
				kml.append("      </LineString>\n");
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
}
