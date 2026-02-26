package com.ontologycentral.osmwrap.webapp;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

import com.ontologycentral.osmwrap.TaginfoConverter;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Servlet that provides SKOS representations of OSM tag keys.
 *
 * Endpoints:
 * - /tag/{key}.rdf - SKOS/RDF representation
 * - /tag/{key}.json - SKOS JSON-LD representation
 */
@SuppressWarnings("serial")
public class TagServlet extends HttpServlet {
	private static final Logger _log = Logger.getLogger(TagServlet.class.getName());
	TaginfoConverter converter = new TaginfoConverter();

	public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		OutputStream os = resp.getOutputStream();

		String pathInfo = req.getPathInfo();
		if (pathInfo == null) {
			resp.sendError(404, "No tag specified");
			return;
		}

		String key = null;
		String format = "rdf"; // default format

		if (pathInfo.startsWith("/")) {
			String path = pathInfo.substring(1);

			// Check for file extension
			if (path.endsWith(".json")) {
				format = "json";
				key = path.substring(0, path.length() - 5); // remove .json
			} else if (path.endsWith(".rdf")) {
				format = "rdf";
				key = path.substring(0, path.length() - 4); // remove .rdf
			} else {
				// No extension - default to RDF
				key = path;
			}

			// Strip any remaining extensions
			if (key.contains(".")) {
				key = key.substring(0, key.indexOf("."));
			}
		}

		if (key == null || key.isEmpty()) {
			resp.sendError(404, "No tag key specified");
			return;
		}

		// Handle index request
		if (key.equals("index")) {
			handleIndexRequest(resp, format);
			return;
		}

		try {
			_log.info("fetching tag info for: " + key);

			// Fetch data from Taginfo
			String keyInfo = converter.fetchKeyInfo(key);
			String values = converter.fetchKeyValues(key);

			// For base keys (no colon), also fetch namespace variants
			String namespacesJson = "";
			if (!key.contains(":")) {
				namespacesJson = converter.fetchAllKeys(); // Fetch all to find variants
			}

			String output;
			if (format.equals("json")) {
				resp.setContentType("application/ld+json");
				output = converter.convertToSKOSJson(key, keyInfo, values, namespacesJson, "/tag/");
			} else {
				resp.setContentType("application/rdf+xml");
				output = converter.convertToSKOSRDF(key, keyInfo, values, namespacesJson, "/tag/");
			}

			resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
			resp.setHeader("Cache-Control", "public, max-age=86400"); // Cache for 1 day
			os.write(output.getBytes(StandardCharsets.UTF_8));

		} catch (IOException e) {
			_log.warning("Error fetching tag info: " + e.getMessage());
			resp.sendError(503, "Unable to fetch tag information: " + e.getMessage());
			return;
		} catch (RuntimeException e) {
			_log.warning("Error processing tag: " + e.getMessage());
			resp.sendError(500, "Error processing tag: " + e.getMessage());
			return;
		}

		os.close();
	}

	/**
	 * Handle requests for /tag/index.{rdf|json}
	 */
	private void handleIndexRequest(HttpServletResponse resp, String format) throws IOException {
		OutputStream os = resp.getOutputStream();

		try {
			_log.info("fetching all tag keys index");

			// Fetch all keys from Taginfo
			String allKeysJson = converter.fetchAllKeys();

			String output;
			if (format.equals("json")) {
				resp.setContentType("application/ld+json");
				output = converter.convertKeysToSKOSJson(allKeysJson);
			} else {
				resp.setContentType("application/rdf+xml");
				output = converter.convertKeysToSKOSRDF(allKeysJson);
			}

			resp.setCharacterEncoding("UTF-8");
			resp.setHeader("Cache-Control", "public, max-age=86400"); // Cache for 1 day
			os.write(output.getBytes(java.nio.charset.StandardCharsets.UTF_8));

		} catch (IOException e) {
			_log.warning("Error fetching tag index: " + e.getMessage());
			resp.sendError(503, "Unable to fetch tag index: " + e.getMessage());
			return;
		} catch (RuntimeException e) {
			_log.warning("Error processing tag index: " + e.getMessage());
			resp.sendError(500, "Error processing tag index: " + e.getMessage());
			return;
		}

		os.close();
	}
}
