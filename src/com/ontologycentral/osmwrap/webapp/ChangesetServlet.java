package com.ontologycentral.osmwrap.webapp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
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
public class ChangesetServlet extends HttpServlet {
	Logger _log = Logger.getLogger(this.getClass().getName());

	public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		OutputStream os = resp.getOutputStream();

		String pathInfo = req.getPathInfo();
		if (pathInfo == null) {
			resp.sendError(404, "No changeset ID specified");
			return;
		}

		String changesetId = null;
		String format = "rdf"; // default format

		if (pathInfo.startsWith("/")) {
			// Remove leading slash and extract ID
			String path = pathInfo.substring(1);

			// Check for file extension
			if (path.endsWith(".json")) {
				format = "json";
				changesetId = path.substring(0, path.length() - 5); // remove .json
			} else if (path.endsWith(".rdf")) {
				format = "rdf";
				changesetId = path.substring(0, path.length() - 4); // remove .rdf
			} else {
				// No extension - default to RDF
				changesetId = path;
			}
		}

		if (changesetId == null) {
			resp.sendError(404, "Invalid changeset ID");
			return;
		}

		ServletContext ctx = getServletContext();

		String archive = UrlBuilder.buildChangesetUrl(changesetId);

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
				// For JSON format, return raw OSM API response
				resp.setContentType("application/json");
				// Note: OSM API doesn't provide JSON for changesets, so this might need conversion
				resp.sendError(406, "JSON format not supported for changesets");
				return;
			} else {
				// Use XSLT for RDF transformation
				Transformer t = (Transformer)ctx.getAttribute(Listener.CHANGESET);

				resp.setContentType("application/rdf+xml");

				t.transform(new StreamSource(is), new StreamResult(os));
			}

		} catch (TransformerException te) {
			System.err.println(te);
			te.printStackTrace();
			resp.sendError(500, "Transformation error: " + te.getMessage());
		} catch (Exception ex) {
			System.err.println(ex);
			ex.printStackTrace();
			resp.sendError(500, "Server error: " + ex.getMessage());
		}
	}
}