package com.ontologycentral.osmwrap.webapp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.Scanner;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.Set;

import com.ontologycentral.osmwrap.ApiConstants;
import com.ontologycentral.osmwrap.HttpClientUtil;
import com.ontologycentral.osmwrap.geometry.MultipolygonHandler;
import com.ontologycentral.osmwrap.geometry.MultipolygonGeometry;

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
			String geometry = extractGeometryFromOSM(osmXml, elementType, id);

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

	private String extractGeometryFromOSM(String osmXml, String elementType, String id) {
		try {
			if ("node".equals(elementType)) {
				// For a node, extract lat/lon directly
				Pattern latPattern = Pattern.compile("lat=['\"]([^'\"]+)['\"]");
				Pattern lonPattern = Pattern.compile("lon=['\"]([^'\"]+)['\"]");
				Matcher latMatcher = latPattern.matcher(osmXml);
				Matcher lonMatcher = lonPattern.matcher(osmXml);

				if (latMatcher.find() && lonMatcher.find()) {
					String lon = lonMatcher.group(1);
					String lat = latMatcher.group(1);
					return "{\"type\":\"Point\",\"coordinates\":[" + lon + "," + lat + "]}";
				}
			} else if ("way".equals(elementType)) {
				// For a way, extract all nd references and fetch their coordinates
				List<String> nodeRefs = extractNodeReferences(osmXml);
				if (nodeRefs.isEmpty()) {
					return "{\"type\":\"LineString\",\"coordinates\":[]}";
				}

				List<double[]> coordinates = fetchNodeCoordinates(nodeRefs);
				return buildLineStringGeometry(coordinates);
			} else if ("relation".equals(elementType)) {
				// Check if this is a multipolygon relation
				if (MultipolygonHandler.isMultipolygon(osmXml)) {
					try {
						_log.info("Processing multipolygon relation " + id);
						MultipolygonGeometry geom = MultipolygonHandler.buildMultipolygon(osmXml, id);
						return MultipolygonHandler.toGeoJSON(geom);
					} catch (Exception e) {
						_log.warning("Error processing multipolygon: " + e.getMessage());
						// Fall through to generic relation handling
					}
				}

				// For other relations, extract member references (generic handling)
				List<Map<String, String>> members = extractMemberReferences(osmXml);
				List<double[]> coordinates = new ArrayList<>();

				for (Map<String, String> member : members) {
					String memberType = member.get("type");
					String memberRef = member.get("ref");

					if ("node".equals(memberType)) {
						// Fetch single node coordinates
						List<double[]> nodeCoords = fetchNodeCoordinates(java.util.Arrays.asList(memberRef));
						coordinates.addAll(nodeCoords);
					} else if ("way".equals(memberType)) {
						// Fetch way coordinates
						List<String> wayNodeRefs = fetchWayNodeReferences(memberRef);
						List<double[]> wayCoords = fetchNodeCoordinates(wayNodeRefs);
						coordinates.addAll(wayCoords);
					}
				}

				if (coordinates.isEmpty()) {
					return "{\"type\":\"GeometryCollection\",\"geometries\":[]}";
				}

				// Return as MultiLineString or GeometryCollection depending on structure
				return buildLineStringGeometry(coordinates);
			}

			return "{\"type\":\"GeometryCollection\",\"geometries\":[]}";
		} catch (Exception e) {
			_log.warning("Error extracting geometry from OSM: " + e.getMessage());
			return "{\"type\":\"GeometryCollection\",\"geometries\":[]}";
		}
	}

	private List<String> extractNodeReferences(String osmXml) {
		List<String> nodeRefs = new ArrayList<>();
		Pattern pattern = Pattern.compile("<nd ref=['\"]([^'\"]+)['\"]");
		Matcher matcher = pattern.matcher(osmXml);

		while (matcher.find()) {
			nodeRefs.add(matcher.group(1));
		}

		return nodeRefs;
	}

	private List<Map<String, String>> extractMemberReferences(String osmXml) {
		List<Map<String, String>> members = new ArrayList<>();
		Pattern pattern = Pattern.compile("<member type=['\"]([^'\"]+)['\"] ref=['\"]([^'\"]+)['\"]");
		Matcher matcher = pattern.matcher(osmXml);

		while (matcher.find()) {
			Map<String, String> member = new HashMap<>();
			member.put("type", matcher.group(1));
			member.put("ref", matcher.group(2));
			members.add(member);
		}

		return members;
	}

	private List<String> fetchWayNodeReferences(String wayId) throws IOException {
		String url = ApiConstants.OSM_API_BASE + "/way/" + wayId;
		HttpResponse<InputStream> response = HttpClientUtil.get(url);

		if (response.statusCode() != 200) {
			return new ArrayList<>();
		}

		InputStream is = response.body();
		String osmXml = readInputStream(is);
		is.close();

		return extractNodeReferences(osmXml);
	}

	private List<double[]> fetchNodeCoordinates(List<String> nodeIds) throws IOException {
		List<double[]> coordinates = new ArrayList<>();

		if (nodeIds == null || nodeIds.isEmpty()) {
			return coordinates;
		}

		// Remove duplicates
		Set<String> uniqueNodeIds = new HashSet<>(nodeIds);

		try {
			// Use bulk fetching to get all nodes in batches (up to 50 per request)
			Map<String, double[]> nodeCoordinates = HttpClientUtil.fetchNodesBulk(new ArrayList<>(uniqueNodeIds));

			// Maintain original order from nodeIds list
			for (String nodeId : nodeIds) {
				if (nodeCoordinates.containsKey(nodeId)) {
					coordinates.add(nodeCoordinates.get(nodeId));
				}
			}
		} catch (Exception e) {
			_log.warning("Error fetching node coordinates in bulk: " + e.getMessage());
			// Return empty list on error
		}

		return coordinates;
	}

	private String buildLineStringGeometry(List<double[]> coordinates) {
		if (coordinates.isEmpty()) {
			return "{\"type\":\"LineString\",\"coordinates\":[]}";
		}

		StringBuilder sb = new StringBuilder();
		sb.append("{\"type\":\"LineString\",\"coordinates\":[");

		for (int i = 0; i < coordinates.size(); i++) {
			if (i > 0) {
				sb.append(",");
			}
			double[] coord = coordinates.get(i);
			sb.append("[").append(coord[0]).append(",").append(coord[1]).append("]");
		}

		sb.append("]}");
		return sb.toString();
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
