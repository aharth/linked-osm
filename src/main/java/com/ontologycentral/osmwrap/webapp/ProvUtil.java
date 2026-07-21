package com.ontologycentral.osmwrap.webapp;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import jakarta.json.Json;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;

/**
 * Shared PROV provenance utility.
 * Adds document-level PROV triples using absolute URIs so Jena can relativize
 * them when the writer is configured with .base(docUrl).
 */
class ProvUtil {

    static final String NS_PROV = "http://www.w3.org/ns/prov#";

    /**
     * Returns the effective request scheme, checking in order:
     * 1. X-Forwarded-Proto header (set by the reverse proxy)
     * 2. base-scheme context-param from web.xml (defensive fallback)
     * 3. req.getScheme() (direct connection)
     */
    static String effectiveScheme(HttpServletRequest req) {
        String h = req.getHeader("X-Forwarded-Proto");
        if (h != null) return h;
        String fb = req.getServletContext().getInitParameter("base-scheme");
        return (fb != null) ? fb : req.getScheme();
    }

    /**
     * Returns the effective origin (scheme + "://" + host) for the request,
     * honouring X-Forwarded-Proto and X-Forwarded-Host proxy headers.
     * Use this as the single source of truth when building absolute docUrls,
     * jenaBase and the XSLT wfs-base/vocab-base parameters.
     */
    static String effectiveOrigin(HttpServletRequest req) {
        String scheme = effectiveScheme(req);
        String host = req.getHeader("X-Forwarded-Host");
        if (host == null) host = req.getHeader("Host");
        if (host == null) host = req.getServerName();
        // The wrapper may be mounted under a PATH PREFIX at the public host;
        // the reverse proxy forwards it as X-Forwarded-Prefix, which becomes
        // part of the public base every in-app absolute path is appended to.
        // Without a forwarded prefix (DIRECT access to the Tomcat context,
        // e.g. tc.ontologycentral.com/linked-osm-1.0.0-SNAPSHOT/...), the
        // servlet context path IS the public mount at that host — falling
        // back to it keeps the minted identifiers resolvable there. Behind
        // the proxy the forwarded prefix still wins and the internal context
        // path stays out of the public identity. Ported from
        // linked-pdok/linked-adv (shared-by-copy).
        String prefix = req.getHeader("X-Forwarded-Prefix");
        if (prefix == null) {
            String ctx = req.getContextPath();
            prefix = (ctx == null) ? "" : ctx;
        } else {
            prefix = prefix.split(",")[0].trim();
            if (!prefix.isEmpty() && !prefix.startsWith("/")) prefix = "/" + prefix;
            while (prefix.endsWith("/")) prefix = prefix.substring(0, prefix.length() - 1);
        }
        return scheme + "://" + host + prefix;
    }

    /**
     * Add document-level PROV triples to model {@code m}:
     * <pre>
     *   &lt;{docUrl}&gt; prov:hadPrimarySource &lt;upstreamUrl&gt; ;
     *       prov:generatedAtTime "{now}"^^xsd:dateTime ;
     *       prov:wasAttributedTo &lt;{root}/index#osmwrap&gt; .
     * </pre>
     * When the Jena writer is called with {@code .base(docUrl)}, these
     * serialise as {@code <>}, {@code <upstreamUrl>} (unchanged, external),
     * and {@code </index#osmwrap>} respectively.
     *
     * @param docUrl      canonical (suffix-free) URL of the document being served
     * @param upstreamUrl URL of the upstream source fetched to build this document
     */
    /**
     * As {@link #addDocumentProv(Model, String, String)} but also attaches a
     * {@code dct:spatial} blank node with a {@code locn:geometry} GML Envelope
     * literal when {@code bbox} is non-null.
     *
     * @param bbox bounding box as "W,S,E,N" in EPSG:4326, or null to omit
     */
    static void addDocumentProv(Model m, String docUrl, String upstreamUrl, String bbox) {
        addDocumentProv(m, docUrl, upstreamUrl);
        if (bbox == null) return;
        String[] parts = bbox.split(",");
        if (parts.length < 4) return;
        try {
            double w = Double.parseDouble(parts[0].trim());
            double s = Double.parseDouble(parts[1].trim());
            double e = Double.parseDouble(parts[2].trim());
            double n = Double.parseDouble(parts[3].trim());
            String rootUrl;
            try {
                URI u = URI.create(docUrl);
                rootUrl = u.getScheme() + "://" + u.getHost()
                        + (u.getPort() > 0 ? ":" + u.getPort() : "");
            } catch (Exception ex) { rootUrl = ""; }
            String bboxNs = rootUrl + "/vocab/bbox#";
            String geoNs  = "http://www.w3.org/2003/01/geo/wgs84_pos#";
            Property southWest = m.createProperty(bboxNs + "southWest");
            Property northEast = m.createProperty(bboxNs + "northEast");
            Property geoLat    = m.createProperty(geoNs + "lat");
            Property geoLong   = m.createProperty(geoNs + "long");
            Resource swPoint   = m.createResource();
            swPoint.addProperty(geoLat,  m.createTypedLiteral(s, XSDDatatype.XSDdecimal));
            swPoint.addProperty(geoLong, m.createTypedLiteral(w, XSDDatatype.XSDdecimal));
            Resource nePoint   = m.createResource();
            nePoint.addProperty(geoLat,  m.createTypedLiteral(n, XSDDatatype.XSDdecimal));
            nePoint.addProperty(geoLong, m.createTypedLiteral(e, XSDDatatype.XSDdecimal));
            Resource bboxNode  = m.createResource();
            bboxNode.addProperty(RDF.type,   m.createResource(bboxNs + "BoundingBox"));
            bboxNode.addProperty(southWest, swPoint);
            bboxNode.addProperty(northEast, nePoint);
            m.createResource(docUrl).addProperty(
                    m.createProperty("http://purl.org/dc/terms/spatial"), bboxNode);
        } catch (NumberFormatException ex) {
            // malformed bbox — skip spatial triple
        }
    }

    static void addDocumentProv(Model m, String docUrl, String upstreamUrl) {
        String rootUrl;
        try {
            URI u = URI.create(docUrl);
            rootUrl = u.getScheme() + "://" + u.getHost()
                    + (u.getPort() > 0 ? ":" + u.getPort() : "");
        } catch (Exception e) {
            rootUrl = "";
        }
        String now = Instant.now().toString();
        Resource doc      = m.createResource(docUrl);
        Resource upstream = m.createResource(upstreamUrl);
        Resource agent    = m.createResource(rootUrl + "/index#osmwrap");
        Property primary  = m.createProperty(NS_PROV + "hadPrimarySource");
        Property genTime  = m.createProperty(NS_PROV + "generatedAtTime");
        Property attrTo   = m.createProperty(NS_PROV + "wasAttributedTo");

        doc.addProperty(primary, upstream);
        doc.addProperty(genTime, m.createTypedLiteral(now, XSDDatatype.XSDdateTime));
        doc.addProperty(attrTo, agent);
    }

    /**
     * Write a JSON error response: {"error": "...", "upstream": "..."}.
     * upstream may be null (omitted from JSON if so).
     */
    static void sendJsonError(HttpServletResponse resp, int status, String error, String upstream)
            throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json;charset=UTF-8");
        var b = Json.createObjectBuilder().add("error", error);
        if (upstream != null) b.add("upstream", upstream);
        resp.getWriter().write(b.build().toString());
    }
}
