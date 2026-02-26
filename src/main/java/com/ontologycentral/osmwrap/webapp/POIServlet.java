package com.ontologycentral.osmwrap.webapp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Scanner;
import java.util.logging.Logger;

import com.ontologycentral.osmwrap.AcceptHeader;
import com.ontologycentral.osmwrap.ApiConstants;
import com.ontologycentral.osmwrap.GeoJsonConverter;
import com.ontologycentral.osmwrap.HttpClientUtil;
import com.ontologycentral.osmwrap.UrlBuilder;

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

		_log.info("retrieving " + archive);
		System.out.println("retrieving " + archive);

		try {
			String postData = "data=" + URLEncoder.encode(overpassQuery, "UTF-8");
			HttpResponse<InputStream> response = HttpClientUtil.post(archive, postData);

			int responseCode = response.statusCode();
			if (responseCode != 200) {
				resp.sendError(responseCode,
						new String(response.body().readAllBytes(), StandardCharsets.UTF_8).trim());
				return;
			}

			InputStream is = response.body();

			if (wantJson) {
				String xml = readInputStream(is);
				is.close();
				String geoJson = GeoJsonConverter.overpassNodesToGeoJson(xml);
				resp.setContentType("application/geo+json");
				os.write(geoJson.getBytes(StandardCharsets.UTF_8));
			} else {
				Templates tmpl = (Templates) ctx.getAttribute(Listener.POI);
			Transformer t = tmpl.newTransformer();

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

	private boolean isJsonRequested(HttpServletRequest req) {
		String path = req.getServletPath();
		if (path != null && path.endsWith(".json")) return true;
		List<AcceptHeader.AcceptType> accepted = AcceptHeader.parse(req.getHeader("Accept"));
		double qJson = Math.max(AcceptHeader.maxQ(accepted, "application", "geo+json"),
				AcceptHeader.maxQ(accepted, "application", "json"));
		double qRdf = Math.max(AcceptHeader.maxQ(accepted, "application", "rdf+xml"),
				AcceptHeader.maxQ(accepted, "text", "turtle"));
		return qJson > qRdf;
	}

	private String readInputStream(InputStream is) throws IOException {
		Scanner scanner = new Scanner(is, StandardCharsets.UTF_8);
		scanner.useDelimiter("\\A");
		return scanner.hasNext() ? scanner.next() : "";
	}
}
