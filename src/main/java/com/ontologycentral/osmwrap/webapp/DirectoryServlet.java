package com.ontologycentral.osmwrap.webapp;

import com.ontologycentral.osmwrap.AcceptHeader;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFWriter;
import org.apache.jena.vocabulary.RDF;

/**
 * Collection resource for a static directory tree that otherwise has no representation of its
 * own — currently just {@code /vocab/} ({@code osm.ttl}, {@code s3db.ttl}). Mapped as
 * {@code /vocab}+{@code /vocab/*}, so it covers every directory level under the mount, not just
 * the root. Ported from linked-inspire's {@code DirectoryServlet} (directory-tree-generic there,
 * shared between {@code /vocab/} and {@code /inspire/}).
 *
 * <p>The bare path (no trailing slash) is always a redirect to the trailing-slash form — the
 * directory-listing convention, same as Apache's {@code DirectorySlash}. The trailing-slash path
 * content-negotiates: RDF clients (the default) get an LDP {@code ldp:BasicContainer} with
 * {@code ldp:contains} per member, plus the standard PROV triple every served document here
 * carries; HTML clients get a plain browsable {@code <ul>} listing. Actual files (leaves) are
 * untouched — forwarded to the container's own "default" servlet exactly as if this servlet
 * were not mapped, so MIME types, ETags and range requests behave as before.
 */
@SuppressWarnings("serial")
public class DirectoryServlet extends HttpServlet {

    private static final String NS_LDP = "http://www.w3.org/ns/ldp#";

    enum Format { HTML, TTL, RDFXML, NTRIPLES }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
        ServletContext ctx = getServletContext();
        String contextPath = req.getContextPath();
        String path = req.getRequestURI().substring(contextPath.length());

        if (!path.endsWith("/")) {
            URL fileUrl;
            try {
                fileUrl = ctx.getResource(path);
            } catch (MalformedURLException e) {
                fileUrl = null;
            }
            if (fileUrl != null) {
                ctx.getNamedDispatcher("default").forward(req, resp);
                return;
            }
            if (ctx.getResourcePaths(path + "/") != null) {
                String lastSegment = path.substring(path.lastIndexOf('/') + 1);
                resp.sendRedirect(lastSegment + "/");
                return;
            }
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Not found: " + path);
            return;
        }

        Set<String> children = ctx.getResourcePaths(path);
        if (children == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Not found: " + path);
            return;
        }

        // name -> isDirectory, sorted
        Map<String, Boolean> entries = new TreeMap<>();
        for (String child : children) {
            boolean isDir = child.endsWith("/");
            String rel = child.substring(path.length(), child.length() - (isDir ? 1 : 0));
            entries.put(rel, isDir);
        }

        Format fmt = resolveFormat(req.getHeader("Accept"));
        resp.setHeader("Vary", "Accept");

        if (fmt == Format.HTML) {
            writeHtml(resp, path, entries);
            return;
        }

        String origin = ProvUtil.effectiveOrigin(req);
        String docUrl = origin + path;
        writeRdf(resp, docUrl, origin, entries, fmt);
    }

    /** No suffix to key off (every path here ends in a trailing slash) — pure {@code Accept}
     *  negotiation, RDF (Turtle by default) beating HTML when both are offered with the same
     *  preference, matching the family's documented default of an RDF client with no
     *  {@code Accept} header at all. Static and network-free so it's directly unit-testable. */
    static Format resolveFormat(String acceptHeader) {
        List<AcceptHeader.AcceptType> a = AcceptHeader.parse(acceptHeader);
        double rdfQ = Math.max(AcceptHeader.maxQ(a, "text", "turtle"),
                Math.max(AcceptHeader.maxQ(a, "application", "rdf+xml"),
                        AcceptHeader.maxQ(a, "application", "n-triples")));
        double htmlQ = AcceptHeader.maxQ(a, "text", "html");
        if (htmlQ > rdfQ) return Format.HTML;

        // N-Triples wins only if it beats BOTH other RDF candidates, not just Turtle - same
        // three-way shape as StatusServlet.resolveFormat.
        boolean wantsNtriples = AcceptHeader.prefers(a, "application", "n-triples", "application", "rdf+xml")
                && AcceptHeader.prefers(a, "application", "n-triples", "text", "turtle");
        boolean wantsRdfxml = AcceptHeader.prefers(a, "application", "rdf+xml", "text", "turtle");
        return wantsNtriples ? Format.NTRIPLES : wantsRdfxml ? Format.RDFXML : Format.TTL;
    }

    // ---- HTML -----------------------------------------------------------------

    private void writeHtml(HttpServletResponse resp, String path, Map<String, Boolean> entries) throws IOException {
        resp.setContentType("text/html;charset=UTF-8");
        java.io.PrintWriter w = resp.getWriter();
        w.println("<!DOCTYPE html><html><head><title>Index of " + esc(path) + "</title></head><body>");
        int depth = (int) path.chars().filter(c -> c == '/').count() - 1; // "/a/b/" -> 2
        w.println("<p><a href=\"" + "../".repeat(Math.max(depth, 1)) + "\">Home</a> &rsaquo; Index of " + esc(path) + "</p>");
        w.println("<h1>Index of " + esc(path) + "</h1>");
        w.println("<ul>");
        if (depth > 1) w.println("<li><a href=\"..\">.. (parent directory)</a></li>");
        for (Map.Entry<String, Boolean> e : entries.entrySet()) {
            String name = e.getKey() + (e.getValue() ? "/" : "");
            w.println("<li><a href=\"" + esc(name) + "\">" + esc(name) + "</a></li>");
        }
        w.println("</ul>");
        w.println("<hr/><a href=\"http://ontologycentral.com/\">OntologyCentral</a>, 2009-2026.");
        w.println("</body></html>");
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ---- RDF --------------------------------------------------------------

    private void writeRdf(HttpServletResponse resp, String docUrl, String origin, Map<String, Boolean> entries,
            Format fmt) throws IOException {
        Model model = ModelFactory.createDefaultModel();
        Resource doc = model.createResource(docUrl);
        Property genTime  = model.createProperty(ProvUtil.NS_PROV + "generatedAtTime");
        Property attrTo   = model.createProperty(ProvUtil.NS_PROV + "wasAttributedTo");
        Property contains = model.createProperty(NS_LDP + "contains");
        Property label    = model.createProperty("http://www.w3.org/2000/01/rdf-schema#label");
        Resource basicContainer = model.createResource(NS_LDP + "BasicContainer");
        Resource ldpResource    = model.createResource(NS_LDP + "Resource");

        doc.addProperty(RDF.type, basicContainer);
        doc.addProperty(RDF.type, ldpResource);
        doc.addProperty(genTime, model.createTypedLiteral(Instant.now().toString(), XSDDatatype.XSDdateTime));
        doc.addProperty(attrTo, model.createResource(origin + "/index#osmwrap"));

        for (Map.Entry<String, Boolean> e : entries.entrySet()) {
            String name = e.getKey() + (e.getValue() ? "/" : "");
            Resource member = model.createResource(docUrl + name);
            member.addProperty(RDF.type, e.getValue() ? basicContainer : ldpResource);
            member.addProperty(label, e.getKey());
            doc.addProperty(contains, member);
        }

        Lang lang = fmt == Format.NTRIPLES ? Lang.NTRIPLES
                : fmt == Format.RDFXML ? Lang.RDFXML
                : Lang.TURTLE;
        String contentType = lang.equals(Lang.NTRIPLES) ? "application/n-triples;charset=UTF-8"
                : lang.equals(Lang.RDFXML) ? "application/rdf+xml;charset=UTF-8"
                : "text/turtle;charset=UTF-8";
        resp.setContentType(contentType);
        RDFWriter.create().lang(lang).base(docUrl).source(model).output(resp.getOutputStream());
    }
}
