package com.ontologycentral.osmwrap.webapp;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.logging.Logger;
import java.util.Scanner;

import com.ontologycentral.osmwrap.ApiConstants;
import com.ontologycentral.osmwrap.HttpClientUtil;
import com.ontologycentral.osmwrap.UrlBuilder;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

@SuppressWarnings("serial")
public class FeatureServlet extends HttpServlet {
	Logger _log = Logger.getLogger(this.getClass().getName());
	
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
			} else {
				// No extension - default to RDF
				id = path;
			}

			// Strip any remaining extensions (e.g., from malformed URLs like 123.json.json)
			if (id.contains(".")) {
				id = id.substring(0, id.indexOf("."));
			}

			// Determine the type based on servlet mapping
			String servletPath = req.getServletPath();
			if (servletPath.equals("/node")) {
				ctrl = "/node/";
			} else if (servletPath.equals("/way")) {
				ctrl = "/way/";
			} else if (servletPath.equals("/relation")) {
				ctrl = "/relation/";
			}
		}

		if (ctrl == null || id == null) {
			resp.sendError(404, "Invalid path");
			return;
		}

		ServletContext ctx = getServletContext();

		String archive;
		if (format.equals("json")) {
			// Use Overpass API for geometry data
			String elementType = ctrl.substring(1, ctrl.length() - 1); // remove leading slash and trailing slash
			String query = UrlBuilder.buildOverpassGeometryQuery(elementType, id);
			archive = ApiConstants.OVERPASS_API_BASE + "?data=" + URLEncoder.encode(query, "UTF-8");
		} else {
			// Use standard OSM API for RDF data
			archive = ApiConstants.OSM_API_BASE + ctrl + id;
		}

		URL u = new URL(archive);

		_log.info("retrieving " + u);
		System.out.println("retrieving " + u);

		try {
			HttpURLConnection conn = HttpClientUtil.createConnection(archive);

			int responseCode = conn.getResponseCode();
			if (responseCode != 200) {
				// Pass through the original status code instead of always returning 500
				resp.sendError(responseCode, "Upstream API returned: " + conn.getResponseMessage());
				return;
			}

			InputStream is = conn.getInputStream();

			String encoding = conn.getContentEncoding();
			if (encoding == null) {
				encoding = "ISO-8859-1";
			}

			if (format.equals("json")) {
				// For JSON format, convert Overpass JSON to GeoJSON
				resp.setContentType("application/geo+json");
				String osmJson = readInputStream(is);
				String geoJson = convertOsmToGeoJson(osmJson);
				os.write(geoJson.getBytes(StandardCharsets.UTF_8));
			} else {
				// RDF format - use existing XSLT transformation
				Transformer t = (Transformer)ctx.getAttribute(ctrl);
				resp.setContentType("application/rdf+xml");

				StreamSource ssource = new StreamSource(is);
				StreamResult sresult = new StreamResult(os);

				_log.info("applying xslt");

				t.transform(ssource, sresult);
			}

    		resp.setHeader("Cache-Control", "public");
    		Calendar c = Calendar.getInstance();
    		c.add(Calendar.DATE, 1);
    		resp.setHeader("Expires", Listener.RFC822.format(c.getTime()));

			is.close();
		} catch (TransformerException e) {
			e.printStackTrace(); 
			resp.sendError(500, e.getMessage());
			return;
		} catch (IOException e) {
			resp.sendError(500, u + ": " + e.getMessage());
			e.printStackTrace();
			return;
		} catch (RuntimeException e) {
			resp.sendError(500, u + ": " + e.getMessage());
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

	private String convertOsmToGeoJson(String osmJson) {
		// Simple OSM JSON to GeoJSON conversion
		// This is a basic implementation - could be improved with proper JSON parsing
		try {
			if (osmJson.contains("\"elements\":[]")) {
				// No elements found
				return "{\"type\":\"FeatureCollection\",\"features\":[]}";
			}

			// Extract first element for simple case
			String elementPattern = "\"elements\":\\s*\\[\\s*\\{([^}]+)\\}";
			java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(elementPattern);
			java.util.regex.Matcher matcher = pattern.matcher(osmJson);

			if (matcher.find()) {
				String elementData = matcher.group(1);

				// Extract basic properties
				String type = extractJsonValue(elementData, "type");
				String id = extractJsonValue(elementData, "id");
				String lat = extractJsonValue(elementData, "lat");
				String lon = extractJsonValue(elementData, "lon");

				if (type != null && id != null) {
					StringBuilder geoJson = new StringBuilder();
					geoJson.append("{\"type\":\"Feature\",");
					geoJson.append("\"id\":").append(id).append(",");

				// Geometry is null - geometry is served separately via /geo/{type}/{id}
				geoJson.append("\"geometry\":null,");

					// Properties
					geoJson.append("\"properties\":{\"osm_type\":\"").append(type).append("\",\"osm_id\":").append(id).append("}}");

					return geoJson.toString();
				}
			}

			// Fallback
			return "{\"type\":\"Feature\",\"geometry\":null,\"properties\":{}}";

		} catch (Exception e) {
			_log.warning("Error converting OSM JSON to GeoJSON: " + e.getMessage());
			return "{\"type\":\"Feature\",\"geometry\":null,\"properties\":{\"error\":\"conversion_failed\"}}";
		}
	}

	private String extractJsonValue(String json, String key) {
		String pattern = "\"" + key + "\"\\s*:\\s*([^,}]+)";
		java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
		java.util.regex.Matcher m = p.matcher(json);
		if (m.find()) {
			return m.group(1).replaceAll("\"", "");
		}
		return null;
	}
}
