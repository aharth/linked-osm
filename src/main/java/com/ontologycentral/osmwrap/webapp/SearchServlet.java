package com.ontologycentral.osmwrap.webapp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.ontologycentral.osmwrap.AcceptHeader;
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
public class SearchServlet extends HttpServlet {
	private static final Logger _log = Logger.getLogger(SearchServlet.class.getName());

	public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		OutputStream os = resp.getOutputStream();

		String query = req.getParameter("q");
		String limit = req.getParameter("limit");

		boolean wantJson = AcceptHeader.prefersJson(req.getServletPath(), req.getHeader("Accept"));

		ServletContext ctx = getServletContext();

		String archive = UrlBuilder.buildSearchUrl(query, limit);

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

			resp.setHeader("X-Upstream-Source", archive);

			InputStream is = response.body();

			if (wantJson) {
				String xml = HttpClientUtil.readToString(is);
				is.close();
				String geoJson = GeoJsonConverter.nominatimToGeoJson(xml);
				resp.setContentType("application/geo+json");
				os.write(geoJson.getBytes(StandardCharsets.UTF_8));
			} else {
				Templates tmpl = (Templates) ctx.getAttribute(Listener.SEARCH);
				Transformer t = tmpl.newTransformer();
				t.setParameter("upstream-url", archive);
				response.headers().firstValueAsLong("content-length")
						.ifPresent(n -> t.setParameter("upstream-bytes", n));
				resp.setContentType("application/rdf+xml");
				_log.info("applying xslt");
				t.transform(new StreamSource(is), new StreamResult(os));
				is.close();
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
