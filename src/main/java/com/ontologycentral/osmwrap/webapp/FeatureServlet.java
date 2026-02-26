package com.ontologycentral.osmwrap.webapp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.List;
import java.util.logging.Logger;
import java.util.Scanner;

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

		// RDF and GML paths for way/relation use /full to get node coordinates inline
		String archive;
		if ((format.equals("rdf") || format.equals("gml")) && (ctrl.equals("/way/") || ctrl.equals("/relation/"))) {
			archive = ApiConstants.OSM_API_BASE + ctrl + id + "/full";
		} else {
			archive = ApiConstants.OSM_API_BASE + ctrl + id;
		}

		_log.info("retrieving " + archive);
		System.out.println("retrieving " + archive);

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

			if (format.equals("json")) {
				resp.setContentType("application/geo+json");
				String elementType = ctrl.substring(1, ctrl.length() - 1);
				String osmXml = readInputStream(is);
				GeoJsonConverter.GeometryResult geomResult = GeoJsonConverter.extractGeometryJson(osmXml, elementType, id);
				String geoJson = GeoJsonConverter.osmFeatureToGeoJson(osmXml, elementType, id, geomResult.geometryJson, geomResult.centroid);
				os.write(geoJson.getBytes(StandardCharsets.UTF_8));
			} else if (format.equals("gml")) {
				Templates tmpl = (Templates) ctx.getAttribute(ctrl + ".gml");
				Transformer t = tmpl.newTransformer();
				resp.setContentType("application/gml+xml");
				t.transform(new StreamSource(is), new StreamResult(os));
			} else {
				// RDF format - use existing XSLT transformation
				Templates tmpl = (Templates) ctx.getAttribute(ctrl);
				Transformer t = tmpl.newTransformer();
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
			resp.sendError(500, archive + ": " + e.getMessage());
			e.printStackTrace();
			return;
		} catch (RuntimeException e) {
			resp.sendError(500, archive + ": " + e.getMessage());
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
}
