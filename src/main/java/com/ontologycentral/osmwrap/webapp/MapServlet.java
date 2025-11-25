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
public class MapServlet extends HttpServlet {
	Logger _log = Logger.getLogger(this.getClass().getName());
	
	public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		OutputStream os = resp.getOutputStream();

		String bbox = req.getParameter("bbox");
		
		ServletContext ctx = getServletContext();

		String archive = UrlBuilder.buildMapUrl(bbox);

		// rather, use overpass api and filter by "relevant" nodes, e.g. those that reference wikipedia
		// or those that have a street address
		// wget "http://overpass.osm.rambler.ru/cgi/interpreter?data=node%2832.8,-118.9448,34.8,-117.6462%29%3Bout%3B"
		
		/*
		<bbox-query s="51.30" n="51.35" w="7.0" e="7.2"/>
		<query type="node">
		    <has-kv k="amenity" regv="." />
		  </query>
		<print />
		*/
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

			Transformer t = (Transformer)ctx.getAttribute(Listener.MAP);

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
