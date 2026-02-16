package com.ontologycentral.osmwrap.webapp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.logging.Logger;

import com.ontologycentral.osmwrap.ApiConstants;
import com.ontologycentral.osmwrap.GeoJsonConverter;
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
public class POIServlet extends HttpServlet {
	Logger _log = Logger.getLogger(this.getClass().getName());

	public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		OutputStream os = resp.getOutputStream();

		String bbox = req.getParameter("bbox");
		String limit = req.getParameter("limit");

		boolean wantJson = isJsonRequested(req);

		ServletContext ctx = getServletContext();

		String overpassQuery;
		try {
			overpassQuery = UrlBuilder.buildOverpassPOIQuery(bbox, limit);
		} catch (IllegalArgumentException e) {
			resp.sendError(400, e.getMessage());
			return;
		}

		String archive = ApiConstants.OVERPASS_API_BASE;

		URL u = new URL(archive);

		_log.info("retrieving " + u);
		System.out.println("retrieving " + u);

		try {
			HttpURLConnection conn = HttpClientUtil.createConnection(archive, ApiConstants.DEFAULT_CONNECT_TIMEOUT, ApiConstants.POI_READ_TIMEOUT);
			conn.setRequestMethod("POST");
			conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
			conn.setDoOutput(true);

			// Send Overpass query as POST data
			String postData = "data=" + URLEncoder.encode(overpassQuery, "UTF-8");
			conn.getOutputStream().write(postData.getBytes("UTF-8"));
			conn.getOutputStream().flush();
			conn.getOutputStream().close();

			int responseCode = conn.getResponseCode();
			if (responseCode != 200) {
				// Pass through the original status code instead of always returning 500
				resp.sendError(responseCode, HttpClientUtil.readErrorBody(conn));
				return;
			}

			InputStream is = conn.getInputStream();

			if (wantJson) {
				String xml = readInputStream(is);
				is.close();
				String geoJson = GeoJsonConverter.overpassNodesToGeoJson(xml);
				resp.setContentType("application/geo+json");
				os.write(geoJson.getBytes(StandardCharsets.UTF_8));
			} else {
				String encoding = conn.getContentEncoding();
				if (encoding == null) {
					encoding = "ISO-8859-1";
				}

				Transformer t = (Transformer)ctx.getAttribute(Listener.POI);

				resp.setContentType("application/rdf+xml");

				StreamSource ssource = new StreamSource(is);
				StreamResult sresult = new StreamResult(os);

				_log.info("applying xslt");

				t.transform(ssource, sresult);

				is.close();
			}
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

	private boolean isJsonRequested(HttpServletRequest req) {
		String path = req.getServletPath();
		if (path != null && path.endsWith(".json")) return true;
		String accept = req.getHeader("Accept");
		if (accept != null && (accept.contains("application/geo+json") || accept.contains("application/json"))) return true;
		return false;
	}

	private String readInputStream(InputStream is) throws IOException {
		Scanner scanner = new Scanner(is, StandardCharsets.UTF_8);
		scanner.useDelimiter("\\A");
		return scanner.hasNext() ? scanner.next() : "";
	}
}
