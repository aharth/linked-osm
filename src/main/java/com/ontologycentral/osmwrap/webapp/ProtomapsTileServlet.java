package com.ontologycentral.osmwrap.webapp;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.ontologycentral.osmwrap.HttpClientUtil;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Passthrough proxy for Protomaps' hosted vector tile API, trusted callers
 * only (see {@link ProtomapsRouting}).
 *
 * <p>{@code /tile/protomaps/{z}/{x}/{y}.mvt} - no rendering, no format
 * negotiation: the upstream {@code .mvt} bytes are streamed back as-is.
 * Untrusted requests, or a deployment with no Protomaps key configured, get
 * 403 rather than a broken proxy attempt.
 */
@SuppressWarnings("serial")
public class ProtomapsTileServlet extends HttpServlet {
    private static final Logger _log = Logger.getLogger(ProtomapsTileServlet.class.getName());

    public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String pathInfo = req.getPathInfo();
        if (pathInfo == null) {
            resp.sendError(404, "No tile coordinates specified");
            return;
        }

        String path = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
        if (path.endsWith(".mvt")) {
            path = path.substring(0, path.length() - 4);
        }
        String[] parts = path.split("/");
        if (parts.length != 3) {
            resp.sendError(404, "Expected /{z}/{x}/{y}.mvt");
            return;
        }

        int z;
        int x;
        int y;
        try {
            z = Integer.parseInt(parts[0]);
            x = Integer.parseInt(parts[1]);
            y = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            resp.sendError(404, "z/x/y must be integers");
            return;
        }

        String url = ProtomapsRouting.tileUrl(req, z, x, y);
        if (url == null) {
            resp.sendError(403, "Protomaps tiles require a trusted request and a configured upstream key");
            return;
        }

        try {
            HttpResponse<InputStream> response = HttpClientUtil.get(url);
            resp.setStatus(response.statusCode());
            resp.setContentType(response.headers().firstValue("Content-Type")
                    .orElse("application/vnd.mapbox-vector-tile"));
            HttpClientUtil.copyStream(response.body(), resp.getOutputStream());
            response.body().close();
        } catch (IOException e) {
            resp.sendError(HttpClientUtil.errorStatus(e), "Protomaps upstream: " + e.getMessage());
            _log.log(Level.SEVERE, e.getMessage(), e);
        }
    }
}
