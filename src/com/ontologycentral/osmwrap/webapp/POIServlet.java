package com.ontologycentral.osmwrap.webapp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
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
public class POIServlet extends HttpServlet {
	Logger _log = Logger.getLogger(this.getClass().getName());
	
	public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		OutputStream os = resp.getOutputStream();

		String bbox = req.getParameter("bbox");
		
		ServletContext ctx = getServletContext();
		
		// Convert bbox from "west,south,east,north" to "south,west,north,east" for Overpass API
		String[] coords = bbox.split(",");
		if (coords.length != 4) {
			resp.sendError(400, "Invalid bbox format. Use: west,south,east,north");
			return;
		}
		String overpassBbox = coords[1] + "," + coords[0] + "," + coords[3] + "," + coords[2]; // south,west,north,east

		// Overpass API query for nodes with amenity tags in bounding box
		String overpassQuery = "[out:xml][timeout:25];\n" +
		                      "(\n" +
		                      "  node[amenity](" + overpassBbox + ");\n" +
		                      ");\n" +
		                      "out meta;";

		String archive = "https://overpass-api.de/api/interpreter";

		URL u = new URL(archive);

		_log.info("retrieving " + u);
		System.out.println("retrieving " + u);

		try {
			HttpURLConnection conn = (HttpURLConnection)u.openConnection();
			conn.setConnectTimeout(8*1000);
			conn.setReadTimeout(30*1000); // Overpass can be slower
			conn.setRequestMethod("POST");
			conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
			conn.setDoOutput(true);

			// Send Overpass query as POST data
			String postData = "data=" + URLEncoder.encode(overpassQuery, "UTF-8");
			conn.getOutputStream().write(postData.getBytes("UTF-8"));
			conn.getOutputStream().flush();
			conn.getOutputStream().close();

			if (conn.getResponseCode() != 200) {
				throw new RuntimeException("lookup on " + u + " resulted HTTP in status code " + conn.getResponseCode());
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
