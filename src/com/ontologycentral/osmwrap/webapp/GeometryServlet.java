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
public class GeometryServlet extends HttpServlet {
	Logger _log = Logger.getLogger(this.getClass().getName());
	
	public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		OutputStream os = resp.getOutputStream();

		String id = req.getRequestURI();
		
		id = id.substring(4);
		
		String ctrl = null;
		
		if (id.startsWith(Listener.NODE)) {
			ctrl = Listener.NODE;
		} else if (id.startsWith(Listener.RELATION)) {
			ctrl = Listener.RELATION;
		} else if (id.startsWith(Listener.WAY)) {
			ctrl = Listener.WAY;
		}
		
		id = id.substring(ctrl.length());
		
		ctrl = ctrl.substring(0, ctrl.length()-1);

		ServletContext ctx = getServletContext();

		String archive = "http://linkedgeodata.org/data/geometry" + ctrl + id + "?output=xml";
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

			Transformer t = (Transformer)ctx.getAttribute(Listener.GEO);

			resp.setContentType("application/wkt");
			
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
