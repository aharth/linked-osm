package com.ontologycentral.osmwrap.webapp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
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

		if (contentType != null && contentType.contains("application/rdf+xml")) {
			byte[] original = capture.toByteArray();

			try {
				Model model = ModelFactory.createDefaultModel();

				RDFParser.create()
						.source(new java.io.ByteArrayInputStream(original))
						.lang(Lang.RDFXML)
						.base("")
						.parse(model);

				ByteArrayOutputStream out = new ByteArrayOutputStream();

				RDFWriter.create()
						.source(model)
						.lang(Lang.TURTLE)
						.base("")
						.output(out);

				byte[] result = out.toByteArray();

				httpResponse.setContentType("text/turtle");
				httpResponse.setContentLength(result.length);
				httpResponse.getOutputStream().write(result);
			} catch (Exception e) {
				_log.warning("RDF parse error, passing through original: " + e.getMessage());
				httpResponse.setContentLength(original.length);
				httpResponse.getOutputStream().write(original);
			}
		} else {
			byte[] data = capture.toByteArray();
			httpResponse.setContentLength(data.length);
			httpResponse.getOutputStream().write(data);
		}
	}

	private static class CaptureResponseWrapper extends HttpServletResponseWrapper {
		private final ByteArrayOutputStream capture;
		private ServletOutputStream outputStream;
		private PrintWriter writer;

		CaptureResponseWrapper(HttpServletResponse response, ByteArrayOutputStream capture) {
			super(response);
			this.capture = capture;
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
