package com.ontologycentral.osmwrap.webapp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.logging.Logger;

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
public class AroundServlet extends HttpServlet {
	Logger _log = Logger.getLogger(this.getClass().getName());

	public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		OutputStream os = resp.getOutputStream();

		String lon = req.getParameter("lon");
		String lat = req.getParameter("lat");
		String radius = req.getParameter("radius");

		if (lon == null || lat == null) {
			resp.sendError(400, "lon and lat parameters are required");
			return;
		}

		if (radius == null) {
			radius = "1000";
		}

		ServletContext ctx = getServletContext();

		String overpassQuery;
		try {
			overpassQuery = UrlBuilder.buildOverpassAroundQuery(lat, lon, radius);
		} catch (IllegalArgumentException e) {
			resp.sendError(400, e.getMessage());
			return;
		}

		String archive = ApiConstants.OVERPASS_API_BASE;

		URL u = new URL(archive);

		_log.info("retrieving " + u);
		System.out.println("retrieving " + u);

		try {
			HttpURLConnection conn = HttpClientUtil.createConnection(archive, ApiConstants.DEFAULT_CONNECT_TIMEOUT, ApiConstants.AROUND_READ_TIMEOUT);
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
				resp.sendError(responseCode, "Upstream API returned: " + conn.getResponseMessage());
				return;
			}

			InputStream is = conn.getInputStream();

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
}
