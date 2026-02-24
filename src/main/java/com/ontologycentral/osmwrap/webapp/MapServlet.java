package com.ontologycentral.osmwrap.webapp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpResponse;
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
public class MapServlet extends HttpServlet {
	Logger _log = Logger.getLogger(this.getClass().getName());

	public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		OutputStream os = resp.getOutputStream();

		String bbox = req.getParameter("bbox");

		boolean wantJson = isJsonRequested(req);

		ServletContext ctx = getServletContext();

		String archive = UrlBuilder.buildMapUrl(bbox);

		_log.info("retrieving " + archive);
		System.out.println("retrieving " + archive);

		try {
			HttpResponse<InputStream> response = HttpClientUtil.get(archive);

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
				String geoJson = GeoJsonConverter.osmMapToGeoJson(xml);
				resp.setContentType("application/geo+json");
				os.write(geoJson.getBytes(StandardCharsets.UTF_8));
			} else {
				Transformer t = (Transformer)ctx.getAttribute(Listener.MAP);

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
