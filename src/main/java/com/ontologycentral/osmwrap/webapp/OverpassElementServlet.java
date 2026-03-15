package com.ontologycentral.osmwrap.webapp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

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
public class OverpassElementServlet extends HttpServlet {
	private static final Logger _log = Logger.getLogger(OverpassElementServlet.class.getName());

	public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		OutputStream os = resp.getOutputStream();

		String pathInfo = req.getPathInfo();
		if (pathInfo == null) {
			resp.sendError(404, "No path specified");
			return;
		}

		String elementType = null;
		String servletPath = req.getServletPath();
		if (servletPath.equals("/overpass/node")) {
			elementType = "node";
		} else if (servletPath.equals("/overpass/way")) {
			elementType = "way";
		} else if (servletPath.equals("/overpass/relation")) {
			elementType = "relation";
		}
		if (elementType == null) {
			resp.sendError(404, "Unknown element type");
			return;
		}

		// Extract id and format from pathInfo
		String path = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
		boolean wantJson = false;
		if (path.endsWith(".json")) {
			wantJson = true;
			path = path.substring(0, path.length() - 5);
		} else if (path.endsWith(".rdf") || path.endsWith(".ttl")) {
			path = path.substring(0, path.length() - 4);
		} else {
			wantJson = AcceptHeader.prefersJson(req.getServletPath(), req.getHeader("Accept"));
		}
		if (path.contains(".")) {
			path = path.substring(0, path.indexOf("."));
		}
		String id = path;

		// Build Overpass QL query
		String query;
		if ("node".equals(elementType)) {
			query = "[out:xml][timeout:60]; node(" + id + "); out body;";
		} else if ("way".equals(elementType)) {
			query = "[out:xml][timeout:60]; way(" + id + "); out body; >; out skel qt;";
		} else {
			query = "[out:xml][timeout:60]; relation(" + id + "); out body; >>; out skel qt;";
		}

		String archive = ApiConstants.OVERPASS_API_BASE;
		String postData = "data=" + URLEncoder.encode(query, StandardCharsets.UTF_8);

		_log.info("querying overpass for " + elementType + "/" + id);

		try {
			HttpResponse<InputStream> response = HttpClientUtil.post(archive, postData);
			int responseCode = response.statusCode();
			if (responseCode != 200) {
				resp.setStatus(responseCode);
				response.headers().firstValue("Content-Type").ifPresent(resp::setContentType);
				HttpClientUtil.copyStream(response.body(), resp.getOutputStream());
				return;
			}

			String xml = HttpClientUtil.readToString(response.body());
			response.body().close();

			if (wantJson) {
				resp.setContentType("application/geo+json");
				String geoJson = GeoJsonConverter.overpassFeaturesToGeoJson(xml);
				os.write(geoJson.getBytes(StandardCharsets.UTF_8));
			} else {
				// Strip Overpass-specific elements (<note>, <meta>) not present in OSM API XML:
				// node.xsl has no template for them, so the default XSLT rule emits their text
				// content directly inside <rdf:RDF>, producing invalid RDF/XML.
				String rdfXml = xml.replaceAll("<note>[^<]*</note>\\s*", "")
						.replaceAll("<meta[^>]*/>\\s*", "");
				ServletContext ctx = getServletContext();
				String tmplKey = "/" + elementType + "/";
				Templates tmpl = (Templates) ctx.getAttribute(tmplKey);
				Transformer t = tmpl.newTransformer();
				t.setParameter("source-prefix", "/overpass");
				t.setParameter("upstream-url", ApiConstants.OVERPASS_API_BASE + "?data=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
				if ("relation".equals(elementType)) {
					t.setParameter("element-id", id);
				}
				resp.setContentType("text/turtle");
				_log.info("applying xslt");
				t.transform(new StreamSource(new StringReader(rdfXml)), new StreamResult(os));
			}
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
