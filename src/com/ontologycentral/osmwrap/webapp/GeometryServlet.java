package com.ontologycentral.osmwrap.webapp;

import java.io.IOException;
import java.util.logging.Logger;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@SuppressWarnings("serial")
public class GeometryServlet extends HttpServlet {
	Logger _log = Logger.getLogger(this.getClass().getName());
	
	public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		String pathInfo = req.getPathInfo();
		if (pathInfo == null) {
			resp.sendError(404, "No path specified");
			return;
		}

		// Parse /geo/node/123 or /geo/way/456 or /geo/relation/789
		String[] parts = pathInfo.substring(1).split("/", 3);
		if (parts.length < 2) {
			resp.sendError(404, "Invalid path format");
			return;
		}

		String elementType = parts[0];
		String id = parts[1];

		// Legacy endpoint - redirect to new .json format
		resp.sendRedirect("../" + elementType + "/" + id + ".json");
	}
}
