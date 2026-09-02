package com.ontologycentral.osmwrap.webapp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.ontologycentral.osmwrap.AcceptHeader;
import com.ontologycentral.osmwrap.GeoJsonConverter;
import com.ontologycentral.osmwrap.HttpClientUtil;
import com.ontologycentral.osmwrap.UrlBuilder;

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
public class AroundServlet extends HttpServlet {
    private static final Logger _log = Logger.getLogger(AroundServlet.class.getName());

    public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        OutputStream os = resp.getOutputStream();

        String lon = req.getParameter("lon");
        String lat = req.getParameter("lat");
        String radius = req.getParameter("radius");
        String limit = req.getParameter("limit");

        if (lon == null || lat == null) {
            resp.sendError(400, "lon and lat parameters are required");
            return;
        }

        if (radius == null) {
            radius = "100";
        }

        try {
            Double.parseDouble(lon);
            Double.parseDouble(lat);
            Double.parseDouble(radius);
        } catch (NumberFormatException e) {
            resp.sendError(400, "lon, lat, and radius must be numbers");
            return;
        }

        boolean wantJson = AcceptHeader.prefersJson(req.getServletPath(), req.getHeader("Accept"));

        ServletContext ctx = getServletContext();

        String overpassQuery;
        try {
            overpassQuery = UrlBuilder.buildOverpassAroundQuery(lat, lon, radius, limit);
        } catch (IllegalArgumentException e) {
            resp.sendError(400, e.getMessage());
            return;
        }

        String archive = OverpassRouting.base(req);

        _log.info("retrieving " + archive);

        try {
            String postData = "data=" + URLEncoder.encode(overpassQuery, StandardCharsets.UTF_8);
            HttpResponse<InputStream> response = HttpClientUtil.post(archive, postData);

            int responseCode = response.statusCode();
            if (responseCode != 200) {
                resp.setStatus(responseCode);
                response.headers().firstValue("Content-Type").ifPresent(resp::setContentType);
                HttpClientUtil.copyStream(response.body(), resp.getOutputStream());
                return;
            }

            resp.setHeader("X-Upstream-Source", archive + "?" + postData);

            InputStream is = response.body();

            if (wantJson) {
                String xml = HttpClientUtil.readToString(is);
                is.close();
                String geoJson = GeoJsonConverter.overpassNodesToGeoJson(xml);
                resp.setContentType("application/geo+json");
                os.write(geoJson.getBytes(StandardCharsets.UTF_8));
            } else {
                Templates tmpl = (Templates) ctx.getAttribute(Listener.POI);
                Transformer t = tmpl.newTransformer();
                t.setParameter("upstream-url", archive + "?" + postData);
                response.headers().firstValueAsLong("content-length")
                        .ifPresent(n -> t.setParameter("upstream-bytes", n));
                resp.setContentType("application/rdf+xml");
                _log.info("applying xslt");
                t.transform(new StreamSource(is), new StreamResult(os));
                is.close();
            }
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
