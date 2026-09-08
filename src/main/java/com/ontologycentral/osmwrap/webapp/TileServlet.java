package com.ontologycentral.osmwrap.webapp;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ontologycentral.osmwrap.AcceptHeader;
import com.ontologycentral.osmwrap.ApiConstants;
import com.ontologycentral.osmwrap.HttpClientUtil;

import jakarta.json.Json;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.geosparql.implementation.datatype.WKTDatatype;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFWriter;
import org.apache.jena.vocabulary.RDF;

/**
 * The family-standard tile endpoint: {@code /tile?s={source}&layer={layer}&z=&x=&y=[&f=...]},
 * the same shape linked-adv/linked-pdok/linked-cdse/linked-inspire serve for their own
 * WMS-backed {@code /tile}. Here there is no WMS and no reprojection - Tracestrack's raster
 * API is already a Web-Mercator {@code z/x/y} pyramid at 256&times;256, so {@code s=tracestrack}
 * is a cached pass-through of the same upstream {@link TracestrackTileServlet} proxies, wrapped
 * in the shared contract so a generic tile client (this wrapper's own map UI, or a sibling
 * wrapper's consumer) does not need to special-case osmwrap.
 *
 * <p>{@code s} is a fixed enum of one value today ({@code tracestrack}) rather than a free
 * upstream URL - unlike the WMS-backed siblings, there is no service registry here, only one
 * commercial raster source wired up. Left as a parameter, not folded away, so a second source
 * (a self-hosted renderer, say) can be added without breaking existing callers.
 *
 * <p>This is the general-purpose, interactive-use endpoint - it is NOT the bulk/systematic
 * corpus-export use case discussed in {@code plans/tile-endpoint-osm-carto.md}, which
 * Tracestrack's terms of service gate on a separate written agreement for mass downloads.
 * A single map client panning around is the ordinary use this proxy (and the existing
 * {@link TracestrackTileServlet}) already serves.
 */
@SuppressWarnings("serial")
public class TileServlet extends HttpServlet {

    private static final Logger _log = Logger.getLogger(TileServlet.class.getName());

    private static final int TILE_PX = 256;
    /** Half the Web-Mercator world extent in metres (EPSG:3857 origin shift). */
    private static final double ORIGIN_SHIFT = Math.PI * 6378137;
    /** Sanity bound on requests this proxy accepts; Tracestrack's own coverage may be shallower
     *  at any given point, in which case the upstream itself answers with its own error. */
    private static final int MAX_ZOOM = 20;

    private static final String NS_GEO  = "http://www.opengis.net/ont/geosparql#";
    private static final String NS_DCAT = "http://www.w3.org/ns/dcat#";
    private static final String NS_DCT  = "http://purl.org/dc/terms/";
    private static final String NS_FOAF = "http://xmlns.com/foaf/0.1/";

    /** Tiles keyed by the (key-redacted) upstream URL; 128 MB, 6 h TTL - same shape as the
     *  WMS-backed siblings' {@code TILE_CACHE}. */
    private static final Cache<String, byte[]> TILE_CACHE = Caffeine.newBuilder()
            .maximumWeight(128L * 1024 * 1024)
            .weigher((String k, byte[] v) -> v.length)
            .expireAfterWrite(Duration.ofHours(6))
            .build();

    /** A parsed tile address plus the representation asked for. */
    record Tile(String source, String layer, int z, int x, int y, String style, String fmt) {}

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        for (String p : java.util.Collections.list(req.getParameterNames())) {
            if (!List.of("s", "layer", "z", "x", "y", "style", "f").contains(p)) {
                resp.sendError(HttpServletResponse.SC_BAD_REQUEST,
                        "Unknown parameter: " + p + " (known: s, layer, z, x, y, style, f)");
                return;
            }
        }

        Tile tile = parse(req);
        if (tile == null) {
            usage(req, resp);
            return;
        }
        if (!"tracestrack".equals(tile.source())) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Unknown source: " + tile.source()
                    + " (known: tracestrack)");
            return;
        }
        if (tile.z() < 0 || tile.z() > MAX_ZOOM) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Zoom out of range: " + tile.z());
            return;
        }
        long n = 1L << tile.z();
        if (tile.x() < 0 || tile.x() >= n || tile.y() < 0 || tile.y() >= n) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND,
                    "Tile out of range for zoom " + tile.z() + " (0.." + (n - 1) + ")");
            return;
        }

        String key = TracestrackRouting.key(req);
        if (key == null) {
            resp.sendError(402, "Tracestrack tiles require a trusted request and a configured upstream key");
            return;
        }

        if (wantsRdf(req, tile)) {
            writeDescription(req, resp, tile, key);
        } else {
            writeImage(resp, tile, key);
        }
    }

    // ---- addressing ---------------------------------------------------------

    /** {@code ?s=&layer=&z=&x=&y=[&style=][&f=webp|png|ttl|rdf|nt]} → a tile, or null when
     *  incomplete. */
    private static Tile parse(HttpServletRequest req) {
        String source = req.getParameter("s");
        String layer = req.getParameter("layer");
        String z = req.getParameter("z"), x = req.getParameter("x"), y = req.getParameter("y");
        if (source == null || source.isEmpty() || layer == null || layer.isEmpty()
                || z == null || x == null || y == null) return null;
        String style = req.getParameter("style");
        String fmt = req.getParameter("f");
        if (fmt != null) {
            fmt = fmt.toLowerCase();
            if (!List.of("webp", "png", "ttl", "rdf", "nt").contains(fmt)) return null;
        }
        try {
            return new Tile(source, layer, Integer.parseInt(z), Integer.parseInt(x),
                    Integer.parseInt(y), style, fmt);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The tile's own URI (image representation), without the format selector. */
    private static String tilePath(Tile t) {
        String p = "/tile?s=" + URLEncoder.encode(t.source(), StandardCharsets.UTF_8)
                + "&layer=" + URLEncoder.encode(t.layer(), StandardCharsets.UTF_8)
                + "&z=" + t.z() + "&x=" + t.x() + "&y=" + t.y();
        if (t.style() != null && !t.style().isEmpty()) {
            p += "&style=" + URLEncoder.encode(t.style(), StandardCharsets.UTF_8);
        }
        return p;
    }

    /** West, south, east, north in EPSG:4326 degrees (for the RDF footprint). */
    static double[] bbox4326(int z, int x, int y) {
        double n = Math.pow(2, z);
        double w = x / n * 360.0 - 180.0;
        double e = (x + 1) / n * 360.0 - 180.0;
        double north = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * y / n))));
        double south = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * (y + 1) / n))));
        return new double[]{w, south, e, north};
    }

    /**
     * True ground resolution in metres per pixel at the tile's centre latitude - the
     * EPSG:3857 *projected* resolution is uniform across latitude by construction of the
     * projection; actual ground distance per pixel shrinks toward the poles by the Mercator
     * scale factor {@code cos(lat)}.
     */
    static double resolution(int z, int y) {
        double[] b4326 = bbox4326(z, 0, y);
        double midLat = (b4326[1] + b4326[3]) / 2;
        double projectedRes = 2 * ORIGIN_SHIFT / (Math.pow(2, z) * TILE_PX);
        return projectedRes * Math.cos(Math.toRadians(midLat));
    }

    private static boolean wantsRdf(HttpServletRequest req, Tile t) {
        if (t.fmt() != null) return List.of("ttl", "rdf", "nt").contains(t.fmt());
        List<AcceptHeader.AcceptType> accepted = AcceptHeader.parse(req.getHeader("Accept"));
        if (accepted.isEmpty()) return false;      // no Accept: an image, as a tile client expects
        return AcceptHeader.prefers(accepted, "text", "turtle", "image", "webp")
            || AcceptHeader.prefers(accepted, "application", "rdf+xml", "image", "webp")
            || AcceptHeader.prefers(accepted, "application", "n-triples", "image", "webp");
    }

    // ---- image --------------------------------------------------------------

    private void writeImage(HttpServletResponse resp, Tile tile, String key) throws IOException {
        boolean wantsPng = "png".equals(tile.fmt());
        String ext = wantsPng ? "png" : "webp";
        String fetchUrl = upstreamUrl(tile, ext, key);
        String cacheKey = upstreamUrl(tile, ext, null);
        byte[] body = TILE_CACHE.getIfPresent(cacheKey);
        String contentType = wantsPng ? "image/png" : "image/webp";
        if (body == null) {
            try {
                HttpResponse<InputStream> upstream = HttpClientUtil.get(fetchUrl);
                String ct = upstream.headers().firstValue("Content-Type").orElse("");
                byte[] raw = upstream.body().readAllBytes();
                if (upstream.statusCode() != 200 || !ct.contains("image")) {
                    _log.warning("tile " + tilePath(tile) + ": upstream " + upstream.statusCode());
                    resp.setStatus(upstream.statusCode() != 200 ? upstream.statusCode() : 502);
                    resp.setContentType(ct.isEmpty() ? "text/plain" : ct);
                    resp.getOutputStream().write(raw);
                    return;
                }
                body = raw;
                contentType = ct;
                TILE_CACHE.put(cacheKey, body);
            } catch (IOException ex) {
                _log.log(Level.SEVERE, ex.getMessage(), ex);
                resp.sendError(HttpClientUtil.errorStatus(ex));
                return;
            }
        }
        resp.setContentType(contentType);
        // The same tile URI also serves Turtle under conneg, so a shared cache must key on
        // Accept - without Vary it could hand this image to an RDF request.
        resp.setHeader("Vary", "Accept");
        resp.setHeader("Cache-Control", "public, max-age=86400");
        resp.getOutputStream().write(body);
    }

    /** The upstream Tracestrack raster URL for this tile. {@code key} is {@code null} for the
     *  cache-key / provenance form, which omits the credential entirely rather than redacting
     *  it after the fact - see the family checklist's "a test asserting the key never reaches a
     *  serialization." */
    static String upstreamUrl(Tile tile, String ext, String key) {
        String url = ApiConstants.TRACESTRACK_TILE_BASE + "/" + tile.layer() + "/" + tile.z()
                + "/" + tile.x() + "/" + tile.y() + "." + ext;
        List<String> qs = new java.util.ArrayList<>();
        if (key != null) qs.add("key=" + key);
        if (tile.style() != null && !tile.style().isEmpty()) {
            qs.add("style=" + URLEncoder.encode(tile.style(), StandardCharsets.UTF_8));
        }
        return qs.isEmpty() ? url : url + "?" + String.join("&", qs);
    }

    // ---- description --------------------------------------------------------

    private void writeDescription(HttpServletRequest req, HttpServletResponse resp, Tile tile,
            String key) throws IOException {
        String origin = ProvUtil.effectiveOrigin(req);
        String self = origin + tilePath(tile);
        double[] b = bbox4326(tile.z(), tile.x(), tile.y());
        double gsd = resolution(tile.z(), tile.y());

        Model m = ModelFactory.createDefaultModel();
        m.setNsPrefix("geo",  NS_GEO);
        m.setNsPrefix("dcat", NS_DCAT);
        m.setNsPrefix("dct",  NS_DCT);
        m.setNsPrefix("foaf", NS_FOAF);
        m.setNsPrefix("xsd",  "http://www.w3.org/2001/XMLSchema#");

        boolean wantsPng = "png".equals(tile.fmt());
        String ext = wantsPng ? "png" : "webp";
        String format = wantsPng ? "image/png" : "image/webp";

        Resource r = m.createResource(self);
        r.addProperty(RDF.type, m.createResource(NS_DCAT + "Distribution"));
        r.addProperty(RDF.type, m.createResource(NS_FOAF + "Image"));
        r.addProperty(m.createProperty(NS_DCT + "title"),
                tile.layer() + " tile " + tile.z() + "/" + tile.x() + "/" + tile.y());
        r.addProperty(m.createProperty(NS_DCT + "format"), format);
        r.addProperty(m.createProperty(NS_DCAT + "downloadURL"),
                m.createResource(self + "&f=" + ext));
        r.addProperty(m.createProperty(NS_DCAT + "spatialResolutionInMeters"),
                m.createTypedLiteral(gsd, XSDDatatype.XSDdecimal));
        // Tracestrack styles raster tiles from OSM + auxiliary elevation/land-cover sources;
        // reproduce their required attribution verbatim (linked-osm/index.html carries the
        // same strings for the human-readable examples).
        r.addProperty(m.createProperty(NS_DCT + "rights"),
                "Data: © OpenStreetMap contributors; Maps © Tracestrack "
                + "(CC BY 4.0, tracestrack.com/terms-of-service/)");
        r.addProperty(m.createProperty(NS_GEO + "hasGeometry"), m.createResource()
                .addProperty(RDF.type, m.createResource(NS_GEO + "Geometry"))
                .addProperty(m.createProperty(NS_GEO + "asWKT"),
                        m.createTypedLiteral(wkt(b), WKTDatatype.INSTANCE)));
        Resource location = m.createResource()
                .addProperty(RDF.type, m.createResource(NS_DCT + "Location"))
                .addProperty(m.createProperty(NS_DCAT + "bbox"),
                        m.createTypedLiteral(wkt(b), WKTDatatype.INSTANCE));
        r.addProperty(m.createProperty(NS_DCT + "spatial"), location);

        // Provenance records the upstream URL WITHOUT the key - upstreamUrl(tile, ext, null)
        // is the same key-redacted form the image cache is keyed on, never the fetch URL.
        ProvUtil.addDocumentProv(m, self, upstreamUrl(tile, ext, null));

        Lang lang = "nt".equals(tile.fmt()) ? Lang.NTRIPLES
                : "rdf".equals(tile.fmt()) ? Lang.RDFXML
                : "ttl".equals(tile.fmt()) ? Lang.TURTLE
                : negotiatedLang(req);
        String contentType = lang.equals(Lang.NTRIPLES) ? "application/n-triples;charset=UTF-8"
                : lang.equals(Lang.RDFXML) ? "application/rdf+xml;charset=UTF-8"
                : "text/turtle;charset=UTF-8";
        resp.setHeader("Vary", "Accept");
        resp.setHeader("Cache-Control", "public, max-age=86400");
        resp.setContentType(contentType);
        RDFWriter.create().lang(lang).base(self).source(m).output(resp.getOutputStream());
    }

    /** N-Triples wins only if it beats BOTH other candidates (not just turtle) - same
     *  three-way shape as the WMS-backed siblings' {@code negotiatedLang}. */
    static Lang negotiatedLang(HttpServletRequest req) {
        List<AcceptHeader.AcceptType> a = AcceptHeader.parse(req.getHeader("Accept"));
        boolean wantsTurtle = AcceptHeader.prefers(a, "text", "turtle", "application", "rdf+xml");
        boolean wantsNtriples = AcceptHeader.prefers(a, "application", "n-triples", "application", "rdf+xml")
                && AcceptHeader.prefers(a, "application", "n-triples", "text", "turtle");
        return wantsNtriples ? Lang.NTRIPLES : wantsTurtle ? Lang.TURTLE : Lang.RDFXML;
    }

    private static String wkt(double[] b) {
        return "POLYGON((" + b[0] + " " + b[1] + ", " + b[2] + " " + b[1] + ", "
                + b[2] + " " + b[3] + ", " + b[0] + " " + b[3] + ", " + b[0] + " " + b[1] + "))";
    }

    // ---- usage --------------------------------------------------------------

    private void usage(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        List<AcceptHeader.AcceptType> accepted = AcceptHeader.parse(req.getHeader("Accept"));
        if (AcceptHeader.prefers(accepted, "text", "html", "application", "json")) {
            resp.setContentType("text/html;charset=UTF-8");
            resp.getWriter().write("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">"
                + "<title>Tile endpoint</title></head><body>\n"
                + "<h1>Web-Mercator tile endpoint</h1>\n"
                + "<p>Serves a Tracestrack raster map as XYZ tiles on the standard Web "
                + "Mercator pyramid (EPSG:3857, the OSM/Leaflet <code>z/x/y</code> grid):</p>\n"
                + "<pre>/tile?s=tracestrack&amp;layer={mapname}&amp;z={z}&amp;x={x}&amp;y={y}"
                + "[&amp;style=][&amp;f=webp|png|ttl|rdf|nt]</pre>\n"
                + "<p>The same URI answers in Turtle (<code>&amp;f=ttl</code> or an RDF "
                + "<code>Accept</code>), describing the tile's footprint and ground "
                + "resolution. See <a href=\"tile/tracestrack/\">/tile/tracestrack/*</a> for "
                + "the raw passthrough this wraps, and Tracestrack's own docs for the "
                + "available <code>layer</code> map names.</p>\n"
                + "<p>In Leaflet: <code>L.tileLayer('/tile?s=tracestrack&amp;layer=topo&amp;"
                + "z={z}&amp;x={x}&amp;y={y}')</code>.</p>\n"
                + "</body></html>");
            return;
        }
        resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(Json.createObjectBuilder()
                .add("error", "expected ?s={source}&layer={layer}&z={z}&x={x}&y={y}")
                .add("usage", "/tile?s=tracestrack&layer={layer}&z={z}&x={x}&y={y}"
                        + "[&style=][&f=webp|png|ttl|rdf|nt]")
                .add("matrixSet", "EPSG:3857, the standard Web Mercator pyramid (OSM/Leaflet z/x/y)")
                .add("sources", Json.createArrayBuilder().add("tracestrack"))
                .build().toString());
    }
}
