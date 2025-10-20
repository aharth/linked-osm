package com.ontologycentral.osmwrap.webapp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;
import java.util.logging.Logger;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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

		if (pathInfo.startsWith("/")) {
			// Remove leading slash and extract ID
			String path = pathInfo.substring(1);
			id = path;

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

		String archive = "https://api.openstreetmap.org/api/0.6" + ctrl + id;

		URL u = new URL(archive);

		_log.info("retrieving " + u);
		System.out.println("retrieving " + u);

		try {
			HttpURLConnection conn = (HttpURLConnection)u.openConnection();
			conn.setConnectTimeout(8*1000);
			conn.setReadTimeout(8*1000);

			if (conn.getResponseCode() != 200) {
				throw new RuntimeException("lookup on " + u + " resulted HTTP in status code " + conn.getResponseCode());
			}

			InputStream is = conn.getInputStream();

			String encoding = conn.getContentEncoding();
			if (encoding == null) {
				encoding = "ISO-8859-1";
			}

			Transformer t = (Transformer)ctx.getAttribute(ctrl);

			resp.setContentType("application/rdf+xml");
			
    		resp.setHeader("Cache-Control", "public");
    		Calendar c = Calendar.getInstance();
    		c.add(Calendar.DATE, 1);
    		resp.setHeader("Expires", Listener.RFC822.format(c.getTime()));

			StreamSource ssource = new StreamSource(is);
			StreamResult sresult = new StreamResult(os);

			_log.info("lapplying xslt");

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
