package com.ontologycentral.osmwrap.webapp;

import com.ontologycentral.osmwrap.AcceptHeader;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFWriter;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

/**
 * {@code /status[.json|.html|.ttl|.rdf|.nt]} - one row per upstream this wrapper depends on
 * (OSM API, Nominatim, Overpass, Protomaps, Tracestrack tile/elevation), live reachability plus
 * whatever static capability is known ({@link ServiceStatusChecker}).
 *
 * <p>Content-negotiated the same way the family's other {@code /status} endpoints are: suffix
 * wins, then {@code Accept}, defaulting to JSON (confirmed live on linked-adv's deployment - this
 * is the shape an automated consumer like behaim's {@code loadLayerRegistry} expects without
 * having to send an {@code Accept} header at all).
 *
 * <p>The RDF view describes the document as a {@code dcat:Catalog} of {@code dcat:DataService}
 * resources, one per row - Tracestrack's layers as {@code dcat:Distribution}s - plus, per
 * service, its probe outcome as a W3C HTTP-vocabulary {@code http:Response} (the same
 * "describe the HTTP transaction" idiom {@link ErrorServlet} already uses for failed requests,
 * ported to linked-pdok's {@code /status.ttl} first and applied here to a reachability probe
 * instead of a failed request).
 *
 * <p><b>Shape note:</b> linked-adv/-pdok report {@code regions.{region}.services[]} because they
 * have a per-region {@code ServiceRegistry} to fan out over. osmwrap has no regions - a flat
 * {@code services[]} array is the honest shape here, not a single-region wrapper around the same
 * tree. A consumer built against the region-grouped shape needs an osmwrap-specific branch either
 * way, since there is no WMS here for a {@code kind === "wms"} check to match in the first place.
 */
@SuppressWarnings("serial")
public class StatusServlet extends HttpServlet {

    private static final String NS_DCAT = "http://www.w3.org/ns/dcat#";
    private static final String NS_DCT  = "http://purl.org/dc/terms/";

    /** The representation this request resolves to - {@link #resolveFormat} is the pure,
     *  directly-testable piece of the content-negotiation logic below. */
    enum Format { JSON, HTML, TTL, RDFXML, NTRIPLES }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Format fmt = resolveFormat(req.getServletPath(), req.getHeader("Accept"));
        resp.setHeader("Vary", "Accept");

        List<ServiceStatusChecker.ServiceStatus> services = ServiceStatusChecker.checkAll();
        resp.setHeader("Cache-Control", "public, max-age=60");
        switch (fmt) {
            case HTML -> writeHtml(resp, services);
            case TTL, RDFXML, NTRIPLES -> writeRdf(req, resp, services, fmt);
            default -> writeJson(resp, services);
        }
    }

    /** Suffix wins; otherwise {@code Accept}, RDF beating HTML beating JSON when all three are
     *  offered with the same preference; JSON is the fallback when nothing wins outright -
     *  matching linked-adv's deployed {@code /status}, which behaim's {@code loadLayerRegistry}
     *  already relies on answering JSON with no {@code Accept} header at all. Static and
     *  network-free so it's directly unit-testable without a servlet container. */
    static Format resolveFormat(String servletPath, String acceptHeader) {
        if (servletPath.endsWith(".json")) return Format.JSON;
        if (servletPath.endsWith(".html")) return Format.HTML;
        if (servletPath.endsWith(".ttl")) return Format.TTL;
        if (servletPath.endsWith(".rdf")) return Format.RDFXML;
        if (servletPath.endsWith(".nt")) return Format.NTRIPLES;

        List<AcceptHeader.AcceptType> a = AcceptHeader.parse(acceptHeader);
        double rdfQ = Math.max(AcceptHeader.maxQ(a, "text", "turtle"),
                Math.max(AcceptHeader.maxQ(a, "application", "rdf+xml"),
                        AcceptHeader.maxQ(a, "application", "n-triples")));
        double htmlQ = AcceptHeader.maxQ(a, "text", "html");
        double jsonQ = AcceptHeader.maxQ(a, "application", "json");

        // Strict '>', not '>=': a bare "Accept: */*" scores every type equally (q=1.0 via the
        // wildcard), so a tie must fall through to the JSON default below rather than spuriously
        // preferring RDF just because it's not LOSING to html/json.
        if (rdfQ > 0 && rdfQ > htmlQ && rdfQ > jsonQ) {
            // N-Triples wins only if it beats BOTH other RDF candidates, not just Turtle - same
            // three-way shape as TileServlet.negotiatedLang.
            boolean wantsTurtle = AcceptHeader.prefers(a, "text", "turtle", "application", "rdf+xml");
            boolean wantsNtriples = AcceptHeader.prefers(a, "application", "n-triples", "application", "rdf+xml")
                    && AcceptHeader.prefers(a, "application", "n-triples", "text", "turtle");
            return wantsNtriples ? Format.NTRIPLES : wantsTurtle ? Format.TTL : Format.RDFXML;
        }
        if (AcceptHeader.prefers(a, "text", "html", "application", "json")) return Format.HTML;
        return Format.JSON;
    }

    // ---- JSON -----------------------------------------------------------------

    private void writeJson(HttpServletResponse resp, List<ServiceStatusChecker.ServiceStatus> services)
            throws IOException {
        resp.setContentType("application/json;charset=UTF-8");
        JsonArrayBuilder arr = Json.createArrayBuilder();
        for (ServiceStatusChecker.ServiceStatus s : services) {
            JsonObjectBuilder o = Json.createObjectBuilder()
                    .add("name", s.name())
                    .add("kind", s.kind())
                    .add("uri", s.uri());
            if (s.keyConfigured() != null) o.add("keyConfigured", s.keyConfigured());
            if (s.ok() != null) o.add("ok", s.ok());
            if (s.detail() != null) o.add("detail", s.detail());
            if (s.millis() > 0) o.add("millis", s.millis());
            if (s.layers() != null) {
                JsonObjectBuilder layers = Json.createObjectBuilder();
                for (Map.Entry<String, Object> e : s.layers().entrySet()) {
                    JsonObjectBuilder l = Json.createObjectBuilder();
                    if (e.getValue() instanceof Map<?, ?> m) {
                        for (Map.Entry<?, ?> me : m.entrySet()) {
                            l.add(String.valueOf(me.getKey()), String.valueOf(me.getValue()));
                        }
                    }
                    layers.add(e.getKey(), l);
                }
                o.add("layers", layers);
            }
            arr.add(o);
        }
        resp.getWriter().write(Json.createObjectBuilder()
                .add("checkedAt", ServiceStatusChecker.lastCheckedAt().toString())
                .add("services", arr)
                .build().toString());
    }

    // ---- HTML -----------------------------------------------------------------

    private void writeHtml(HttpServletResponse resp, List<ServiceStatusChecker.ServiceStatus> services)
            throws IOException {
        resp.setContentType("text/html;charset=UTF-8");
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">")
          .append("<title>Upstream status</title></head><body>\n")
          .append("<h1>Upstream status</h1>\n")
          .append("<p>Checked at ").append(ServiceStatusChecker.lastCheckedAt())
          .append(" (cached up to 5 minutes).</p>\n<dl>\n");
        for (ServiceStatusChecker.ServiceStatus s : services) {
            sb.append("<dt>").append(esc(s.name())).append(" (").append(esc(s.kind())).append(")</dt>\n");
            sb.append("<dd>");
            if (s.ok() != null) {
                sb.append(s.ok() ? "OK" : "DOWN");
            } else {
                sb.append("not checked");
            }
            if (s.keyConfigured() != null) {
                sb.append(" &mdash; key ").append(s.keyConfigured() ? "configured" : "NOT configured");
            }
            if (s.detail() != null) {
                sb.append(" &mdash; ").append(esc(s.detail()));
            }
            if (s.millis() > 0) {
                sb.append(" (").append(s.millis()).append(" ms)");
            }
            sb.append(" &mdash; <code>").append(esc(s.uri())).append("</code>");
            if (s.layers() != null && !s.layers().isEmpty()) {
                sb.append("\n<details><summary>layers</summary><ul>\n");
                for (String layer : s.layers().keySet()) {
                    sb.append("<li>").append(esc(layer)).append("</li>\n");
                }
                sb.append("</ul></details>\n");
            }
            sb.append("</dd>\n");
        }
        sb.append("</dl>\n</body></html>");
        resp.getWriter().write(sb.toString());
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ---- RDF --------------------------------------------------------------

    private void writeRdf(HttpServletRequest req, HttpServletResponse resp,
            List<ServiceStatusChecker.ServiceStatus> services, Format fmt) throws IOException {
        String origin = ProvUtil.effectiveOrigin(req);
        String qs = req.getQueryString();
        String docUrl = origin + "/status" + (qs != null ? "?" + qs : "");
        Model m = buildStatusModel(services, docUrl, origin);

        Lang lang = fmt == Format.NTRIPLES ? Lang.NTRIPLES
                : fmt == Format.RDFXML ? Lang.RDFXML
                : Lang.TURTLE;
        String contentType = lang.equals(Lang.NTRIPLES) ? "application/n-triples;charset=UTF-8"
                : lang.equals(Lang.RDFXML) ? "application/rdf+xml;charset=UTF-8"
                : "text/turtle;charset=UTF-8";
        resp.setContentType(contentType);
        RDFWriter.create().lang(lang).base(docUrl).source(m).output(resp.getOutputStream());
    }

    /** Builds the RDF description: a {@code dcat:Catalog} ({@code docUrl}) listing every
     *  {@code services} row as a {@code dcat:service}. Package-private, static and network-free
     *  so it's directly unit-testable (mirroring linked-pdok's {@code buildStatusModel}). */
    static Model buildStatusModel(List<ServiceStatusChecker.ServiceStatus> services,
            String docUrl, String origin) {
        Model m = ModelFactory.createDefaultModel();
        m.setNsPrefix("dcat", NS_DCAT);
        m.setNsPrefix("dct", NS_DCT);
        m.setNsPrefix("prov", ProvUtil.NS_PROV);
        m.setNsPrefix("http", ErrorServlet.NS_HTTP);
        m.setNsPrefix("rdfs", RDFS.getURI());
        m.setNsPrefix("xsd", "http://www.w3.org/2001/XMLSchema#");

        Resource doc = m.createResource(docUrl);
        doc.addProperty(RDF.type, m.createResource(NS_DCAT + "Catalog"));
        doc.addProperty(m.createProperty(NS_DCT + "title"), "Linked OSM upstream service status");
        doc.addProperty(m.createProperty(ProvUtil.NS_PROV + "generatedAtTime"),
                m.createTypedLiteral(Instant.now().toString(), XSDDatatype.XSDdateTime));
        doc.addProperty(m.createProperty(ProvUtil.NS_PROV + "wasAttributedTo"),
                m.createResource(origin + "/index#osmwrap"));

        for (ServiceStatusChecker.ServiceStatus s : services) {
            Resource svc = m.createResource(s.uri());
            doc.addProperty(m.createProperty(NS_DCAT + "service"), svc);
            svc.addProperty(RDF.type, m.createResource(NS_DCAT + "DataService"));
            svc.addProperty(RDFS.label, s.name());
            svc.addProperty(m.createProperty(NS_DCT + "type"), s.kind());
            if (s.layers() != null) {
                for (Map.Entry<String, Object> e : s.layers().entrySet()) {
                    addLayerRdf(m, svc, s.uri(), e.getKey(), e.getValue());
                }
            }
            addReachabilityRdf(m, svc, s.ok(), s.detail(), s.statusCode());
        }
        return m;
    }

    /** One Tracestrack layer as a {@code dcat:Distribution} - the same deliberate, documented
     *  stretch of a Dataset-oriented DCAT predicate onto a {@code dcat:DataService} that
     *  linked-pdok's own WMS-layer RDF makes (no better-fitting standard term exists for "one
     *  addressable variant a data service can be asked to serve"). */
    private static void addLayerRdf(Model m, Resource svc, String svcUri, String layerName, Object value) {
        String layerUri = svcUri + "#layer=" + URLEncoder.encode(layerName, StandardCharsets.UTF_8);
        Resource layer = m.createResource(layerUri);
        svc.addProperty(m.createProperty(NS_DCAT + "distribution"), layer);
        layer.addProperty(RDF.type, m.createResource(NS_DCAT + "Distribution"));
        layer.addProperty(m.createProperty(NS_DCT + "title"), layerName);
        if (value instanceof Map<?, ?> map) {
            Object kind = map.get("kind");
            if (kind != null) layer.addProperty(m.createProperty(NS_DCT + "type"), String.valueOf(kind));
            Object path = map.get("path");
            if (path != null) {
                layer.addProperty(RDFS.comment,
                        "Served via the raw passthrough at /tile/tracestrack/" + path + "/{z}/{x}/{y}");
            }
        }
    }

    /** Attaches the probe outcome to {@code svc}: {@code prov:generatedAtTime} always, plus
     *  either an {@code http:Response} (a response was actually received - {@code statusCode}
     *  non-null) or an {@code rdfs:comment} carrying {@code detail} (nothing was probed at all -
     *  a config-only row, or a probe that never got a response, e.g. a timeout or DNS failure). */
    private static void addReachabilityRdf(Model m, Resource svc, Boolean ok, String detail,
            Integer statusCode) {
        svc.addProperty(m.createProperty(ProvUtil.NS_PROV + "generatedAtTime"),
                m.createTypedLiteral(Instant.now().toString(), XSDDatatype.XSDdateTime));
        if (statusCode != null) {
            Resource httpResp = m.createResource();
            httpResp.addProperty(RDF.type, m.createResource(ErrorServlet.NS_HTTP + "Response"));
            httpResp.addProperty(m.createProperty(ErrorServlet.NS_HTTP + "statusCodeValue"),
                    String.valueOf(statusCode));
            String individual = ErrorServlet.STATUS_CODE_INDIVIDUALS.get(statusCode);
            if (individual != null) {
                httpResp.addProperty(m.createProperty(ErrorServlet.NS_HTTP + "sc"),
                        m.createResource(ErrorServlet.NS_HTTP_STATUS + individual));
            }
            if (Boolean.FALSE.equals(ok) && detail != null) {
                httpResp.addProperty(m.createProperty(ErrorServlet.NS_HTTP + "body"), detail);
            }
            svc.addProperty(m.createProperty(ErrorServlet.NS_HTTP + "resp"), httpResp);
        } else if (detail != null) {
            svc.addProperty(RDFS.comment, detail);
        }
    }
}
