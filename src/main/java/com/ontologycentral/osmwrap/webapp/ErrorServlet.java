package com.ontologycentral.osmwrap.webapp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.ontologycentral.osmwrap.AcceptHeader;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Handles container error pages for all status codes listed in web.xml.
 * Returns JSON for clients that accept it, minimal HTML otherwise.
 */
@SuppressWarnings("serial")
public class ErrorServlet extends HttpServlet {

	@Override
	protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		Integer status = (Integer) req.getAttribute("jakarta.servlet.error.status_code");
		String message = (String) req.getAttribute("jakarta.servlet.error.message");
		if (status == null) status = 500;
		if (message == null || message.isBlank()) message = defaultMessage(status);

		resp.setStatus(status);

		List<AcceptHeader.AcceptType> accepted = AcceptHeader.parse(req.getHeader("Accept"));
		boolean serveHtml = AcceptHeader.prefers(accepted, "text", "html", "application", "json");

		if (serveHtml) {
			resp.setContentType("text/html;charset=UTF-8");
			String body = "<!DOCTYPE html><html><head><title>" + status + " " + escHtml(message)
					+ "</title></head><body><h1>" + status + " " + escHtml(message)
					+ "</h1></body></html>";
			resp.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
		} else {
			resp.setContentType("application/json;charset=UTF-8");
			String body = "{\"error\":" + jsonString(message) + "}";
			resp.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
		}
	}

	private static String defaultMessage(int status) {
		switch (status) {
			case 400: return "Bad Request";
			case 404: return "Not Found";
			case 406: return "Not Acceptable";
			case 413: return "Content Too Large";
			case 500: return "Internal Server Error";
			case 502: return "Bad Gateway";
			case 503: return "Service Unavailable";
			case 504: return "Gateway Timeout";
			default:  return "Error";
		}
	}

	private static String jsonString(String s) {
		return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	private static String escHtml(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
