package com.ontologycentral.osmwrap.webapp;

import com.github.benmanes.caffeine.cache.Cache;
import com.ontologycentral.osmwrap.AcceptHeader;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.ResultSet;
import org.apache.jena.query.ResultSetFormatter;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;

@SuppressWarnings("serial")
public class SparqlServlet extends HttpServlet {

    private static final Logger _log = Logger.getLogger(SparqlServlet.class.getName());

    /** Caches upstream RDF graphs keyed by URI; avoids re-fetching the same resource. */
    private static final Cache<String, Model> GRAPH_CACHE = Caffeine.newBuilder()
            .maximumSize(200)
            .expireAfterWrite(Duration.ofHours(1))
            .build();

    @Override
    public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
        handleSparqlRequest(req, resp);
    }

    @Override
    public void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
        handleSparqlRequest(req, resp);
    }


    /**
     * SPARQL 1.1 Service Description: what a GET without a query returns to
     * non-HTML clients — the machine-readable counterpart of sparql.html.
     * Relative URIs resolve against the request URI, so no origin handling.
     */
    private void writeServiceDescription(HttpServletResponse resp) throws IOException {
        resp.setContentType("text/turtle;charset=UTF-8");
        resp.getWriter().write("""
                @prefix sd: <http://www.w3.org/ns/sparql-service-description#> .
                @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .

                <#service> a sd:Service ;
                  sd:endpoint <> ;
                  sd:supportedLanguage sd:SPARQL11Query ;
                  sd:resultFormat <http://www.w3.org/ns/formats/SPARQL_Results_JSON>, <http://www.w3.org/ns/formats/SPARQL_Results_XML>, <http://www.w3.org/ns/formats/Turtle> ;
                  sd:feature sd:DereferencesURIs ;
                  rdfs:comment "Queries must name their data with FROM / FROM NAMED clauses that resolve to this wrapper's own endpoints (e.g. FROM </map.rdf?bbox=...>); external graph URIs are rejected." ;
                  rdfs:seeAlso <sparql.html> .
                """);
    }

    /** Reject any query parameter outside the endpoint's closed set (400 lists the known ones). */
    private static boolean knownParamsOnly(HttpServletRequest req, HttpServletResponse resp,
            java.util.List<String> allowed) throws IOException {
        for (String p : java.util.Collections.list(req.getParameterNames())) {
            if (!allowed.contains(p)) {
                resp.sendError(HttpServletResponse.SC_BAD_REQUEST,
                        "Unknown parameter: " + p + " (known: " + String.join(", ", allowed) + ")");
                return false;
            }
        }
        return true;
    }

    private void handleSparqlRequest(HttpServletRequest req, HttpServletResponse resp)
            throws IOException, ServletException {
        req.setCharacterEncoding("UTF-8");
        if (!knownParamsOnly(req, resp, java.util.List.of("query"))) return;
        String queryString = req.getParameter("query");
        if (queryString == null || queryString.trim().isEmpty()) {
            if ("GET".equals(req.getMethod())) {
                List<AcceptHeader.AcceptType> accepted = AcceptHeader.parse(req.getHeader("Accept"));
                if (AcceptHeader.prefers(accepted, "text", "html", "text", "turtle")) {
                    resp.sendRedirect("sparql.html");
                } else {
                    writeServiceDescription(resp);
                }
                return;
            } else {
                resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing 'query' parameter");
                return;
            }
        }

        String format = getOutputFormat(req.getHeader("Accept"), req.getServletPath());
        long t0 = System.currentTimeMillis();

        // Step 1 — Compute server base and parse (400 on syntax error). The base
        // is the wrapper's PUBLIC origin INCLUDING a reverse-proxy path mount,
        // so a relative FROM <wfs?…> resolves to this wrapper's /wfs regardless
        // of the mount, and the same-origin gate below is scoped to the wrapper
        // (not the whole host).
        String serverBase = ProvUtil.effectiveOrigin(req) + "/";

        String toParse = queryString.stripLeading().toLowerCase().startsWith("base")
                ? queryString
                : "BASE <" + serverBase + ">\n" + queryString;

        Query query;
        try {
            query = QueryFactory.create(toParse);
        } catch (Exception e) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST,
                    "Bad query: " + e.getMessage());
            return;
        }

        // Step 2 — Require FROM / FROM NAMED (400)
        List<String> defaultGraphs = new ArrayList<>(query.getGraphURIs());
        List<String> namedGraphs   = new ArrayList<>(query.getNamedGraphURIs());

        if (defaultGraphs.isEmpty() && namedGraphs.isEmpty()) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST,
                    "At least one FROM or FROM NAMED clause is required");
            return;
        }

        // Step 3 — Reject external URIs (400)
        for (String uri : defaultGraphs) {
            if (!uri.startsWith(serverBase)) {
                resp.sendError(HttpServletResponse.SC_BAD_REQUEST,
                        "External FROM graph not permitted: " + uri);
                return;
            }
        }
        for (String uri : namedGraphs) {
            if (!uri.startsWith(serverBase)) {
                resp.sendError(HttpServletResponse.SC_BAD_REQUEST,
                        "External FROM NAMED graph not permitted: " + uri);
                return;
            }
        }

        // Step 4 — Load graphs (cache by URI to avoid re-fetching the same resource)
        long tLoad = System.currentTimeMillis();
        Dataset dataset = DatasetFactory.createGeneral();
        for (String uri : defaultGraphs) {
            try {
                Model m = GRAPH_CACHE.getIfPresent(uri);
                if (m == null) {
                    m = ModelFactory.createDefaultModel();
                    readGraph(m, uri);
                    GRAPH_CACHE.put(uri, m);
                }
                dataset.getDefaultModel().add(m);
            } catch (Exception e) {
                _log.log(Level.WARNING, "Failed to load <" + uri + ">: " + e.getMessage(), e);
                resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                        "Failed to load graph <" + uri + ">: " + graphLoadReason(e));
                return;
            }
        }
        for (String uri : namedGraphs) {
            try {
                Model m = GRAPH_CACHE.getIfPresent(uri);
                if (m == null) {
                    m = ModelFactory.createDefaultModel();
                    readGraph(m, uri);
                    GRAPH_CACHE.put(uri, m);
                }
                dataset.addNamedModel(uri, m);
            } catch (Exception e) {
                _log.log(Level.WARNING, "Failed to load <" + uri + ">: " + e.getMessage(), e);
                resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                        "Failed to load graph <" + uri + ">: " + graphLoadReason(e));
                return;
            }
        }
        long loadMs = System.currentTimeMillis() - tLoad;

        // Step 5 — Strip FROM clauses, re-parse, execute; capture output for caching
        String stripped = toParse
                .replaceAll("(?i)FROM\\s+NAMED\\s+<[^>]+>", "")
                .replaceAll("(?i)FROM\\s+<[^>]+>", "");
        Query execQuery = QueryFactory.create(stripped);

        long tExec = System.currentTimeMillis();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        String contentType = null;
        try (QueryExecution qexec = QueryExecutionFactory.create(execQuery, dataset)) {
            if (execQuery.isSelectType()) {
                ResultSet results = qexec.execSelect();
                if ("json".equals(format)) {
contentType = "application/sparql-results+json;charset=UTF-8";
                    ResultSetFormatter.outputAsJSON(baos, results);
                } else if ("xml".equals(format)) {
                    contentType = "application/sparql-results+xml;charset=UTF-8";
                    ResultSetFormatter.outputAsXML(baos, results);
                } else {
                    contentType = "text/tab-separated-values;charset=UTF-8";
                    ResultSetFormatter.outputAsTSV(baos, results);
                }
            } else if (execQuery.isConstructType()) {
                Model result = qexec.execConstruct();
                if ("rdf".equals(format)) {
                    contentType = "application/rdf+xml;charset=UTF-8";
                    addQueryProv(result, serverBase + "sparql", defaultGraphs, namedGraphs);
                    result.write(new OutputStreamWriter(baos, StandardCharsets.UTF_8), "RDF/XML");
                } else if ("ttl".equals(format)) {
                    contentType = "text/turtle;charset=UTF-8";
                    addQueryProv(result, serverBase + "sparql", defaultGraphs, namedGraphs);
                    result.write(new OutputStreamWriter(baos, StandardCharsets.UTF_8), "TURTLE");
                } else {
                    contentType = "application/geo+json;charset=UTF-8";
                    baos.write(modelToGeoJson(result).getBytes(StandardCharsets.UTF_8));
                }
            } else if (execQuery.isDescribeType()) {
                Model result = qexec.execDescribe();
                if ("rdf".equals(format)) {
                    contentType = "application/rdf+xml;charset=UTF-8";
                    addQueryProv(result, serverBase + "sparql", defaultGraphs, namedGraphs);
                    result.write(new OutputStreamWriter(baos, StandardCharsets.UTF_8), "RDF/XML");
                } else if ("ttl".equals(format)) {
                    contentType = "text/turtle;charset=UTF-8";
                    addQueryProv(result, serverBase + "sparql", defaultGraphs, namedGraphs);
                    result.write(new OutputStreamWriter(baos, StandardCharsets.UTF_8), "TURTLE");
                } else {
                    contentType = "application/geo+json;charset=UTF-8";
                    baos.write(modelToGeoJson(result).getBytes(StandardCharsets.UTF_8));
                }
            } else if (execQuery.isAskType()) {
                contentType = "application/sparql-results+json;charset=UTF-8";
                baos.write(("{\"boolean\": " + qexec.execAsk() + "}").getBytes(StandardCharsets.UTF_8));
            } else {
                resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Unsupported query type");
                return;
            }
        } catch (Exception e) {
            _log.log(Level.SEVERE, e.getMessage(), e);
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Query execution failed: " + e.getMessage());
            return;
        }

        long execMs = System.currentTimeMillis() - tExec;
        long totalMs = System.currentTimeMillis() - t0;
        resp.setHeader("Server-Timing",
                "load;desc=\"graph-load\";dur=" + loadMs
                + ", exec;desc=\"query-exec\";dur=" + execMs
                + ", total;desc=\"total\";dur=" + totalMs);
        resp.setCharacterEncoding("UTF-8");
        resp.setContentType(contentType);
        resp.getOutputStream().write(baos.toByteArray());
    }

    /**
     * Adds PROV provenance triples to a CONSTRUCT/DESCRIBE result model.
     * Each FROM/FROM NAMED graph URI becomes a prov:hadPrimarySource of the document.
     */
    private static void addQueryProv(Model m, String docUrl,
            List<String> defaultGraphs, List<String> namedGraphs) {
        List<String> all = new java.util.ArrayList<>(defaultGraphs);
        all.addAll(namedGraphs);
        if (all.isEmpty()) return;
        ProvUtil.addDocumentProv(m, docUrl, all.get(0));
        if (all.size() > 1) {
            Property primary = m.createProperty(ProvUtil.NS_PROV + "hadPrimarySource");
            Resource docRes = m.createResource(docUrl);
            for (int i = 1; i < all.size(); i++)
                docRes.addProperty(primary, m.createResource(all.get(i)));
        }
    }

    /**
     * Converts a Jena Model to a GeoJSON FeatureCollection.
     * Each URI subject becomes a GeoJSON Feature: with GML geometry if it has a locn:geometry
     * literal, or with null geometry otherwise (so properties are always visible in the panel).
     */
    private static String modelToGeoJson(Model model) {
        Property locnGeometry = model.getProperty("http://www.w3.org/ns/locn#geometry");
        JsonArrayBuilder features = Json.createArrayBuilder();
        org.apache.jena.rdf.model.ResIterator subjects = model.listSubjects();
        while (subjects.hasNext()) {
            Resource subj = subjects.nextResource();
            if (!subj.isURIResource()) continue;
            String subjectUri = subj.getURI();

            // Collect properties (excluding locnGeometry)
            jakarta.json.JsonObjectBuilder props = Json.createObjectBuilder();
            StmtIterator propStmts = model.listStatements(subj, null, (RDFNode) null);
            while (propStmts.hasNext()) {
                Statement s = propStmts.next();
                if (s.getPredicate().equals(locnGeometry)) continue;
                String key = s.getPredicate().getURI();
                if (s.getObject().isLiteral()) {
                    props.add(key, s.getObject().asLiteral().getString());
                } else if (s.getObject().isURIResource()) {
                    props.add(key, s.getObject().asResource().getURI());
                } else if (s.getObject().isAnon()) {
                    props.add(key, collectBnode(model, s.getObject().asResource(), 0));
                }
            }

            // Convert locn:geometry literal → GeoJSON geometry, or null if absent.
            // Branch on literal datatype: geo:wktLiteral (most servlets, GeoSPARQL
            // geof:centroid output, etc.) → JTS WKTReader; geo:gmlLiteral or
            // legacy untyped → existing GML path.
            jakarta.json.JsonObjectBuilder fb = Json.createObjectBuilder();
            fb.add("type", "Feature");
            fb.add("id", subjectUri);
            Statement geomStmt = model.getProperty(subj, locnGeometry);
            JsonObject geojsonGeom = null;
            if (geomStmt != null && geomStmt.getObject().isLiteral()) {
                String litValue = geomStmt.getObject().asLiteral().getString();
                String dtUri = geomStmt.getObject().asLiteral().getDatatypeURI();
                boolean isWkt = "http://www.opengis.net/ont/geosparql#wktLiteral".equals(dtUri);
                // GISCO documents carry WKT geometry literals only (no GML sources here).
                try {
                    if (isWkt) {
                        geojsonGeom = wktToGeoJson(litValue);
                    }
                } catch (Exception e) {
                    _log.log(Level.WARNING, "modelToGeoJson: geometry conversion failed for <" + subjectUri + ">: " + e.getMessage());
                }
            }
            if (geojsonGeom != null) fb.add("geometry", geojsonGeom);
            else fb.addNull("geometry");
            fb.add("properties", props);
            features.add(fb);
        }

        return Json.createObjectBuilder()
                .add("type", "FeatureCollection")
                .add("features", features)
                .build().toString();
    }

    /**
     * Convert a GeoSPARQL WKT literal value to a GeoJSON geometry JsonObject.
     * Strips the optional leading CRS URI prefix (e.g. "&lt;...epsg/0/4326&gt; POINT(...)")
     * that GeoSPARQL allows. Coordinates are emitted in the literal's CRS axis order
     * — for the project's data this is EPSG:4326 lon/lat (i.e. already GeoJSON-compatible).
     */
    private static JsonObject wktToGeoJson(String wktLiteral) throws Exception {
        String wkt = wktLiteral.trim();
        if (wkt.startsWith("<")) {
            int end = wkt.indexOf('>');
            if (end > 0) wkt = wkt.substring(end + 1).trim();
        }
        org.locationtech.jts.geom.Geometry geom =
                new org.locationtech.jts.io.WKTReader().read(wkt);
        org.locationtech.jts.io.geojson.GeoJsonWriter w =
                new org.locationtech.jts.io.geojson.GeoJsonWriter();
        w.setEncodeCRS(false);
        try (JsonReader jr = Json.createReader(new StringReader(w.write(geom)))) {
            return jr.readObject();
        }
    }

    /** Recursively collects blank node properties into a JSON object, up to 5 levels deep. */
    private static jakarta.json.JsonValue collectBnode(Model model, Resource bnode, int depth) {
        if (depth > 5) return Json.createValue("[...]");
        jakarta.json.JsonObjectBuilder obj = Json.createObjectBuilder();
        StmtIterator it = model.listStatements(bnode, null, (RDFNode) null);
        while (it.hasNext()) {
            Statement s = it.next();
            String key = s.getPredicate().getURI();
            if (s.getObject().isLiteral()) {
                obj.add(key, s.getObject().asLiteral().getString());
            } else if (s.getObject().isURIResource()) {
                obj.add(key, s.getObject().asResource().getURI());
            } else if (s.getObject().isAnon()) {
                obj.add(key, collectBnode(model, s.getObject().asResource(), depth + 1));
            }
        }
        return obj.build();
    }

    /** Returns a short, safe reason string for a graph-load failure (no internal paths). */
    /**
     * Accept header for fetching FROM/FROM NAMED graphs. Prefers N-Triples — line-oriented,
     * so it serialises and re-parses much faster (~2.4×) than RDF/XML for this internal
     * fetch→parse round-trip — and falls back to RDF/XML then Turtle for endpoints that don't
     * offer it. A format-neutral graph URI (e.g. {@code /wfs?…}) is content-negotiated to
     * N-Triples; an explicit {@code .rdf}/{@code .ttl} suffix wins (the producer honours the
     * suffix over Accept), so the byte format always matches what the FROM URI advertises.
     */
    private static final String GRAPH_ACCEPT =
            "application/n-triples, application/rdf+xml;q=0.5, text/turtle;q=0.4";

    /** Fetch and parse an RDF graph, content-negotiating the fastest available serialisation. */
    private static void readGraph(Model m, String uri) {
        org.apache.jena.riot.RDFParser.create()
                .source(uri)
                .acceptHeader(GRAPH_ACCEPT)
                .parse(m);
    }

    private static String graphLoadReason(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return "fetch failed";
        // Jena wraps HTTP failures as "404" or "Failed to load ... 404" etc.
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b(\\d{3})\\b").matcher(msg);
        if (m.find()) return "HTTP " + m.group(1);
        if (msg.toLowerCase().contains("not found")) return "HTTP 404";
        if (msg.toLowerCase().contains("connect")) return "connection failed";
        return "fetch failed";
    }

    private String getOutputFormat(String acceptHeader, String servletPath) {
        // File suffix overrides Accept header
        if (servletPath.endsWith(".json")) return "json";
        if (servletPath.endsWith(".ttl"))  return "ttl";
        if (servletPath.endsWith(".rdf"))  return "rdf";
        if (servletPath.endsWith(".tsv"))  return "tsv";
        // Accept header negotiation for suffix-less URIs
        if (acceptHeader != null) {
            String a = acceptHeader.toLowerCase();
            if (a.contains("application/sparql-results+json")) return "json";
            if (a.contains("application/sparql-results+xml"))  return "xml";
            if (a.contains("text/tab-separated-values"))       return "tsv";
            if (a.contains("text/turtle"))                     return "ttl";
            if (a.contains("application/rdf+xml"))             return "rdf";
        }
        return "json";
    }

}
