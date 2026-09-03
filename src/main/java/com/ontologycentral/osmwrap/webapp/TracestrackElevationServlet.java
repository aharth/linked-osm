package com.ontologycentral.osmwrap.webapp;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.ontologycentral.osmwrap.ApiConstants;
import com.ontologycentral.osmwrap.HttpClientUtil;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Passthrough proxy for Tracestrack's elevation lookup API, trusted callers
 * only (see {@link TracestrackRouting}).
 *
 * <p>{@code POST /tracestrack/elevation} - the client's JSON body (an array
 * of {@code {lat, lon}}) is forwarded upstream unmodified; the upstream JSON
 * response is streamed back as-is.
 */
@SuppressWarnings("serial")
public class TracestrackElevationServlet extends HttpServlet {
    private static final Logger _log = Logger.getLogger(TracestrackElevationServlet.class.getName());
    private static final long MAX_BODY_BYTES = 1_000_000;

    public void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String key = TracestrackRouting.key(req);
        if (key == null) {
            resp.sendError(402, "Tracestrack elevation requires a trusted request and a configured upstream key");
            return;
        }

        byte[] body = req.getInputStream().readNBytes((int) MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            resp.sendError(413, "Request body too large");
            return;
        }

        String url = ApiConstants.TRACESTRACK_TILE_BASE + "/elevation?key=" + key;
        try {
            HttpResponse<InputStream> response = HttpClientUtil.postRaw(url, body, "application/json");
            resp.setStatus(response.statusCode());
            resp.setContentType(response.headers().firstValue("Content-Type").orElse("application/json"));
            HttpClientUtil.copyStream(response.body(), resp.getOutputStream());
            response.body().close();
        } catch (IOException e) {
            resp.sendError(HttpClientUtil.errorStatus(e), "Tracestrack upstream: " + e.getMessage());
            _log.log(Level.SEVERE, e.getMessage(), e);
        }
    }
}
