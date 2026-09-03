package com.ontologycentral.osmwrap.webapp;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ontologycentral.osmwrap.ApiConstants;
import com.ontologycentral.osmwrap.HttpClientUtil;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Passthrough proxy for Tracestrack's raster, vector and terrain-RGB tile
 * APIs, trusted callers only (see {@link TracestrackRouting}).
 *
 * <p>One servlet, three URL shapes under {@code /tile/tracestrack/*}, dispatched
 * on the leading path segment (mirroring the upstream API's own layout - see
 * {@code https://tracestrack.com/docs/}):
 * <ul>
 *   <li>{@code /vt/{name}/{z}/{x}/{y}.pbf} - vector tiles
 *       ({@code carto}/{@code routes}/{@code contours}/{@code topo})</li>
 *   <li>{@code /terrain-rgb/{z}/{x}/{y}.webp} - terrain-RGB elevation tiles</li>
 *   <li>{@code /{mapname}/{z}/{x}/{y}.{ext}[?style=...]} - combined raster
 *       tiles (everything else - language code or {@code topo_}-prefixed
 *       variant, {@code webp}/{@code png})</li>
 * </ul>
 * No rendering, no format negotiation: upstream bytes are streamed back as-is.
 */
@SuppressWarnings("serial")
public class TracestrackTileServlet extends HttpServlet {
    private static final Logger _log = Logger.getLogger(TracestrackTileServlet.class.getName());

    private static final Pattern VECTOR_LAST = Pattern.compile("^(\\d+)\\.pbf$");
    private static final Pattern TERRAIN_LAST = Pattern.compile("^(\\d+)\\.webp$");
    private static final Pattern RASTER_LAST = Pattern.compile("^(\\d+)(@[12]x)?\\.(webp|png)$");
    private static final Pattern DIGITS = Pattern.compile("^\\d+$");

    public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String pathInfo = req.getPathInfo();
        if (pathInfo == null) {
            resp.sendError(404, "No tile coordinates specified");
            return;
        }
        String path = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
        String[] parts = path.split("/");

        String key = TracestrackRouting.key(req);
        if (key == null) {
            resp.sendError(402, "Tracestrack tiles require a trusted request and a configured upstream key");
            return;
        }

        String url = upstreamUrl(parts, key, req.getParameter("style"));
        if (url == null) {
            resp.sendError(404, "Unrecognized Tracestrack tile path: /" + path);
            return;
        }

        try {
            HttpResponse<InputStream> response = HttpClientUtil.get(url);
            resp.setStatus(response.statusCode());
            resp.setContentType(response.headers().firstValue("Content-Type")
                    .orElse("application/octet-stream"));
            HttpClientUtil.copyStream(response.body(), resp.getOutputStream());
            response.body().close();
        } catch (IOException e) {
            resp.sendError(HttpClientUtil.errorStatus(e), "Tracestrack upstream: " + e.getMessage());
            _log.log(Level.SEVERE, e.getMessage(), e);
        }
    }

    /** Pure URL construction, kept separate from the servlet API for testability. */
    static String upstreamUrl(String[] parts, String key, String style) {
        if (parts.length == 5 && "vt".equals(parts[0])) {
            if (!DIGITS.matcher(parts[2]).matches() || !DIGITS.matcher(parts[3]).matches()) {
                return null;
            }
            Matcher m = VECTOR_LAST.matcher(parts[4]);
            if (!m.matches()) {
                return null;
            }
            return ApiConstants.TRACESTRACK_TILE_BASE + "/vt/" + parts[1] + "/" + parts[2] + "/"
                    + parts[3] + "/" + parts[4] + "?key=" + key;
        }
        if (parts.length == 4 && "terrain-rgb".equals(parts[0])) {
            if (!DIGITS.matcher(parts[1]).matches() || !DIGITS.matcher(parts[2]).matches()) {
                return null;
            }
            Matcher m = TERRAIN_LAST.matcher(parts[3]);
            if (!m.matches()) {
                return null;
            }
            return ApiConstants.TRACESTRACK_TILE_BASE + "/terrain-rgb/" + parts[1] + "/" + parts[2] + "/"
                    + parts[3] + "?key=" + key;
        }
        if (parts.length == 4) {
            if (!DIGITS.matcher(parts[1]).matches() || !DIGITS.matcher(parts[2]).matches()) {
                return null;
            }
            Matcher m = RASTER_LAST.matcher(parts[3]);
            if (!m.matches()) {
                return null;
            }
            String url = ApiConstants.TRACESTRACK_TILE_BASE + "/" + parts[0] + "/" + parts[1] + "/"
                    + parts[2] + "/" + parts[3] + "?key=" + key;
            if (style != null && !style.isEmpty()) {
                url += "&style=" + URLEncoder.encode(style, StandardCharsets.UTF_8);
            }
            return url;
        }
        return null;
    }
}
