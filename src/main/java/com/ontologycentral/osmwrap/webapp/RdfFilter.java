package com.ontologycentral.osmwrap.webapp;

import com.ontologycentral.osmwrap.AcceptHeader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.logging.Logger;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.RDFWriter;
import org.apache.jena.riot.RIOT;

public class RdfFilter implements Filter {
	Logger _log = Logger.getLogger(this.getClass().getName());

	@Override
	public void doFilter(
			jakarta.servlet.ServletRequest request,
			jakarta.servlet.ServletResponse response,
			FilterChain chain)
			throws IOException, ServletException {

		HttpServletRequest httpRequest = (HttpServletRequest) request;
		HttpServletResponse httpResponse = (HttpServletResponse) response;

		ByteArrayOutputStream capture = new ByteArrayOutputStream();
		CaptureResponseWrapper wrapper = new CaptureResponseWrapper(httpResponse, capture);

		chain.doFilter(request, wrapper);

		wrapper.flushBuffer();

		String contentType = wrapper.getContentType();

		String acceptHeader = httpRequest.getHeader("Accept");
		List<AcceptHeader.AcceptType> accepted = AcceptHeader.parse(acceptHeader);
		boolean serveTurtle = !AcceptHeader.prefers(accepted,
				"application", "rdf+xml", "text", "turtle");

		httpResponse.setHeader("Vary", "Accept");

		String requestPath = httpRequest.getRequestURI()
				.substring(httpRequest.getContextPath().length());
		// Strip query string segment — only the path portion matters for extension detection
		int qpos = requestPath.indexOf('?');
		if (qpos >= 0) requestPath = requestPath.substring(0, qpos);
		// Last path segment (after final '/') used for extension detection
		String lastSegment = requestPath.substring(requestPath.lastIndexOf('/') + 1);
		boolean hasExtension = lastSegment.contains(".");

		if (serveTurtle && contentType != null && contentType.contains("application/rdf+xml")) {
			byte[] original = capture.toByteArray();

			try {
				Model model = ModelFactory.createDefaultModel();

				String proto = httpRequest.getHeader("X-Forwarded-Proto");
				if (proto == null) proto = httpRequest.getScheme();
				String host = httpRequest.getHeader("X-Forwarded-Host");
				if (host == null) host = httpRequest.getHeader("Host");
				if (host == null) host = httpRequest.getServerName();
				String queryString = httpRequest.getQueryString();
				String base = proto + "://" + host + requestPath
						+ (queryString != null ? "?" + queryString : "");

				RDFParser.create()
						.source(new java.io.ByteArrayInputStream(original))
						.lang(Lang.RDFXML)
						.base(base)
						.parse(model);

				ByteArrayOutputStream out = new ByteArrayOutputStream();

				RDFWriter.create()
						.source(model)
						.lang(Lang.TURTLE)
						.base(base)
						.output(out);

				String turtle = out.toString("UTF-8");
				byte[] result = turtle.getBytes("UTF-8");

				if (!hasExtension) {
					httpResponse.setHeader("Content-Location", requestPath + ".ttl");
				}
				httpResponse.setContentType("text/turtle");
				httpResponse.setContentLength(result.length);
				httpResponse.getOutputStream().write(result);
			} catch (Exception e) {
				_log.warning("RDF parse error: " + e.getMessage());
				httpResponse.sendError(500, "RDF parse error: " + e.getMessage());
				return;
			}
		} else {
			byte[] data = capture.toByteArray();
			if (!hasExtension && contentType != null) {
				String ext = null;
				if (contentType.contains("application/rdf+xml"))             ext = ".rdf";
				else if (contentType.contains("application/geo+json")
						|| contentType.contains("application/json"))         ext = ".json";
				else if (contentType.contains("application/gml+xml"))        ext = ".gml";
				if (ext != null) {
					httpResponse.setHeader("Content-Location", requestPath + ext);
				}
			}
			if (contentType != null) {
				httpResponse.setContentType(contentType);
			}
			httpResponse.setContentLength(data.length);
			httpResponse.getOutputStream().write(data);
		}
	}

	private static class CaptureResponseWrapper extends HttpServletResponseWrapper {
		private final ByteArrayOutputStream capture;
		private ServletOutputStream outputStream;
		private PrintWriter writer;
		private String contentType;
		private int status = 200;

		CaptureResponseWrapper(HttpServletResponse response, ByteArrayOutputStream capture) {
			super(response);
			this.capture = capture;
		}

		@Override
		public void setContentType(String type) {
			this.contentType = type;
		}

		@Override
		public String getContentType() {
			return contentType;
		}

		@Override
		public void setStatus(int sc) {
			this.status = sc;
			super.setStatus(sc);
		}

		@Override
		public void sendError(int sc, String msg) throws IOException {
			this.status = sc;
			super.sendError(sc, msg);
		}

		@Override
		public void sendError(int sc) throws IOException {
			this.status = sc;
			super.sendError(sc);
		}

		@Override
		public int getStatus() {
			return status;
		}

		@Override
		public ServletOutputStream getOutputStream() throws IOException {
			if (outputStream == null) {
				outputStream = new ServletOutputStream() {
					@Override
					public void write(int b) throws IOException {
						capture.write(b);
					}

					@Override
					public void write(byte[] b, int off, int len) throws IOException {
						capture.write(b, off, len);
					}

					@Override
					public boolean isReady() {
						return true;
					}

					@Override
					public void setWriteListener(WriteListener listener) {
					}
				};
			}
			return outputStream;
		}

		@Override
		public PrintWriter getWriter() throws IOException {
			if (writer == null) {
				writer = new PrintWriter(new java.io.OutputStreamWriter(capture, getCharacterEncoding()));
			}
			return writer;
		}

		@Override
		public void flushBuffer() throws IOException {
			if (writer != null) {
				writer.flush();
			}
			if (outputStream != null) {
				outputStream.flush();
			}
		}

		@Override
		public void setContentLength(int len) {
		}

		@Override
		public void setContentLengthLong(long len) {
		}
	}
}
