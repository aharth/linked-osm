package com.ontologycentral.osmwrap.webapp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpResponse;
import java.util.logging.Level;
import java.util.logging.Logger;

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
public class ChangesetServlet extends HttpServlet {
	private static final Logger _log = Logger.getLogger(ChangesetServlet.class.getName());

	public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		OutputStream os = resp.getOutputStream();

		String pathInfo = req.getPathInfo();
		if (pathInfo == null) {
			resp.sendError(404, "No changeset ID specified");
			return;
		}

		String changesetId = null;

		if (pathInfo.startsWith("/")) {
			String path = pathInfo.substring(1);
			if (path.endsWith(".json")) {
				resp.sendError(406, "JSON format not supported for changesets");
				return;
			} else if (path.endsWith(".rdf")) {
				changesetId = path.substring(0, path.length() - 4);
			} else {
				changesetId = path;
			}
		}

		if (changesetId == null) {
			resp.sendError(404, "Invalid changeset ID");
			return;
		}

		ServletContext ctx = getServletContext();

		String archive = UrlBuilder.buildChangesetUrl(changesetId);

		_log.info("retrieving " + archive);

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

			Templates tmpl = (Templates) ctx.getAttribute(Listener.CHANGESET);
			Transformer t = tmpl.newTransformer();
			response.headers().firstValueAsLong("content-length")
					.ifPresent(n -> t.setParameter("upstream-bytes", n));
			resp.setContentType("application/rdf+xml");
			t.transform(new StreamSource(is), new StreamResult(os));

		} catch (TransformerException te) {
			_log.log(Level.SEVERE, te.getMessage(), te);
			resp.sendError(500, "Transformation error: " + te.getMessage());
		} catch (IOException ex) {
			_log.log(Level.SEVERE, ex.getMessage(), ex);
			resp.sendError(HttpClientUtil.errorStatus(ex), "Server error: " + ex.getMessage());
		} catch (Exception ex) {
			_log.log(Level.SEVERE, ex.getMessage(), ex);
			resp.sendError(500, "Server error: " + ex.getMessage());
		}
	}
}
