package com.ontologycentral.osmwrap.webapp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import com.ontologycentral.osmwrap.AcceptHeader;
import com.ontologycentral.osmwrap.ApiConstants;
import com.ontologycentral.osmwrap.GeoJsonConverter;
import com.ontologycentral.osmwrap.HttpClientUtil;

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
public class FeatureServlet extends HttpServlet {
	private static final Logger _log = Logger.getLogger(FeatureServlet.class.getName());

	/**
	 * XML cache keyed by upstream URL. Weight is the char count of the XML string
	 * (≈ UTF-8 byte count for ASCII-heavy OSM XML). Capped at 64 MB to bound heap use.
	 */
	private final Cache<String, Object[]> xmlCache = Caffeine.newBuilder()
			.maximumWeight(2L * 1024 * 1024 * 1024)
			.weigher((String k, Object[] v) -> ((String) v[0]).length())
			.expireAfterWrite(Duration.ofHours(24))
			.build();

	/** Tracks upstream fetches that are currently in progress, keyed by upstream URL. */
	private final ConcurrentHashMap<String, CompletableFuture<Object[]>> inFlight = new ConcurrentHashMap<>();

	/** Signals that an upstream fetch returned a non-200 status. */
	private static final class FetchException extends Exception {
		final int status;
		FetchException(int status, String msg) {
			super(msg);
			this.status = status;
		}
	}

	public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		OutputStream os = resp.getOutputStream();

		String pathInfo = req.getPathInfo();
		if (pathInfo == null) {
			resp.sendError(404, "No path specified");
			return;
		}

		String ctrl = null;
		String id = null;
		String format = "rdf"; // default format

		if (pathInfo.startsWith("/")) {
			// Remove leading slash and extract ID
			String path = pathInfo.substring(1);

			// Check for file extension
			if (path.endsWith(".json")) {
				format = "json";
				id = path.substring(0, path.length() - 5); // remove .json
			} else if (path.endsWith(".rdf")) {
				format = "rdf";
				id = path.substring(0, path.length() - 4); // remove .rdf
			} else if (path.endsWith(".ttl")) {
				format = "rdf";                              // produce RDF/XML; RdfFilter converts to Turtle
				id = path.substring(0, path.length() - 4); // strip .ttl
			} else if (path.endsWith(".gml")) {
				format = "gml";
				id = path.substring(0, path.length() - 4); // remove .gml
			} else {
				// No extension - content-negotiate on Accept header
				id = path;
				List<AcceptHeader.AcceptType> accepted = AcceptHeader.parse(req.getHeader("Accept"));
				double qJson = Math.max(AcceptHeader.maxQ(accepted, "application", "geo+json"),
						AcceptHeader.maxQ(accepted, "application", "json"));
				double qRdf = Math.max(AcceptHeader.maxQ(accepted, "application", "rdf+xml"),
						AcceptHeader.maxQ(accepted, "text", "turtle"));
				double qGml = AcceptHeader.maxQ(accepted, "application", "gml+xml");
				if (qGml > qRdf && qGml > qJson) {
					format = "gml";
				} else if (qJson > qRdf) {
					format = "json";
				}
			}

			// Strip any remaining extensions (e.g., from malformed URLs like 123.json.json)
			if (id.contains(".")) {
				id = id.substring(0, id.indexOf("."));
			}

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

		// Relations always use /full (all formats) so geometry can be assembled inline.
		// Ways use /full for rdf and gml (need node coordinates); json uses simple endpoint.
		String archive;
		if (ctrl.equals("/relation/")) {
			archive = ApiConstants.OSM_API_BASE + ctrl + id + "/full";
		} else if ((format.equals("rdf") || format.equals("gml")) && ctrl.equals("/way/")) {
			archive = ApiConstants.OSM_API_BASE + ctrl + id + "/full";
		} else {
			archive = ApiConstants.OSM_API_BASE + ctrl + id;
		}

		_log.info("retrieving " + archive);

		try {
			// --- Fetch XML with cache and in-flight deduplication ---
			//
			// Three cases in priority order:
			//   1. Cache hit   — use stored XML immediately, no upstream call.
			//   2. In-flight   — another thread is already fetching this URL; join its
			//                    CompletableFuture and use the result when it arrives.
			//   3. Cold miss   — register a new CompletableFuture so other threads can
			//                    join it, then perform the upstream fetch.
			//
			// This ensures at most one upstream request is in flight per URL at any time.

			String xml = null;
			long byteCount = -1L;

			Object[] cached = xmlCache.getIfPresent(archive);
			if (cached != null) {
				xml = (String) cached[0];
				byteCount = (long) cached[1];
				_log.info("cache hit: " + archive);
			} else {
				CompletableFuture<Object[]> fresh = new CompletableFuture<>();
				CompletableFuture<Object[]> existing = inFlight.putIfAbsent(archive, fresh);

				if (existing != null) {
					// Case 2: join the in-flight fetch
					_log.info("joining in-flight fetch: " + archive);
					try {
						Object[] result = existing.join();
						xml = (String) result[0];
						byteCount = (long) result[1];
					} catch (CompletionException ce) {
						Throwable cause = ce.getCause();
						int errStatus = (cause instanceof FetchException) ? ((FetchException) cause).status : 502;
						resp.sendError(errStatus, cause.getMessage());
						return;
					}
				} else {
					// Case 3: cold miss — this thread is the designated fetcher
					try {
						HttpResponse<InputStream> response = HttpClientUtil.get(archive);
						int responseCode = response.statusCode();
						if (responseCode != 200) {
							// Signal waiting threads then proxy the error body to this response.
							fresh.completeExceptionally(new FetchException(responseCode, "upstream " + responseCode));
							resp.setStatus(responseCode);
							response.headers().firstValue("Content-Type").ifPresent(resp::setContentType);
							HttpClientUtil.copyStream(response.body(), resp.getOutputStream());
							return;
						}
						byteCount = response.headers().firstValueAsLong("content-length").orElse(-1L);
						xml = HttpClientUtil.readToString(response.body());
						response.body().close();
						Object[] result = new Object[]{xml, byteCount};
						xmlCache.put(archive, result);
						fresh.complete(result);
					} catch (IOException | RuntimeException e) {
						fresh.completeExceptionally(e);
						throw e;
					} finally {
						inFlight.remove(archive, fresh);
					}
				}
			}

			// --- Format dispatch ---

			if (format.equals("json")) {
				resp.setContentType("application/geo+json");
				String elementType = ctrl.substring(1, ctrl.length() - 1);
				GeoJsonConverter.GeometryResult geomResult = GeoJsonConverter.extractGeometryJson(xml, elementType, id);
				String geoJson = GeoJsonConverter.osmFeatureToGeoJson(xml, elementType, id, geomResult.geometryJson);
				os.write(geoJson.getBytes(StandardCharsets.UTF_8));
			} else if (format.equals("gml")) {
				Templates tmpl = (Templates) ctx.getAttribute(ctrl + ".gml");
				Transformer t = tmpl.newTransformer();
				resp.setContentType("application/gml+xml");
				t.transform(new StreamSource(new StringReader(xml)), new StreamResult(os));
			} else {
				// RDF format - use existing XSLT transformation
				Templates tmpl = (Templates) ctx.getAttribute(ctrl);
				Transformer t = tmpl.newTransformer();
				if (byteCount >= 0) {
					t.setParameter("upstream-bytes", byteCount);
				}
				if (ctrl.equals("/relation/")) {
					t.setParameter("element-id", id);
				}
				resp.setContentType("application/rdf+xml");
				_log.info("applying xslt");
				t.transform(new StreamSource(new StringReader(xml)), new StreamResult(os));
			}

    		resp.setHeader("Cache-Control", "public");
    		resp.setHeader("Expires", ZonedDateTime.now().plusDays(1).format(Listener.RFC822));

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
