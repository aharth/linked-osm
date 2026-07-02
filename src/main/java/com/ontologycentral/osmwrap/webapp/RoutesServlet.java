package com.ontologycentral.osmwrap.webapp;

import jakarta.servlet.ServletRegistration;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A machine-readable <b>interface manifest</b> at {@code /routes} (and {@code /routes.json}): the
 * <em>deployed</em> endpoints, each with the representation {@code formats} it serves and the query
 * {@code params} it accepts — introspected from this servlet context's own mappings, so it reflects
 * exactly what is live, with nothing hand-maintained to drift.
 *
 * <p>It lets a consumer (the Granergize webapp) regenerate a typed route contract from the running
 * service AND describe the interface to users (which routes take which params, which formats) — the
 * "option C" source of truth: the deployed wrapper, not a checked-in {@code web.xml} copy. See the
 * app's {@code explore/explore-wrapper-contract-drift.md}.
 *
 * <p>Shape: {@code {"routes":[{"name":"nominatim/search","formats":["json",…],"params":["q","limit"]}]}}.
 * Route names keep their path (e.g. {@code nominatim/search}, {@code overpass/poi}). {@code formats}
 * are derived from the url-pattern suffixes; {@code params} are declared in {@link #ROUTE_PARAMS},
 * mirroring each servlet's {@code getParameter} reads (the path-addressed record routes —
 * {@code osm/node}, {@code overpass/way}, {@code tag}, … — take none). Sorted by name, CORS-open.
 */
@SuppressWarnings("serial")
public class RoutesServlet extends HttpServlet {

    /** Representation suffixes stripped to recover the logical route (and reported as `formats`). */
    private static final Set<String> FORMAT_SUFFIXES = Set.of(
            ".ttl", ".nt", ".rdf", ".jsonld", ".geojson", ".json", ".html");

    /** Query params each QUERY route accepts (the wrapper's own interface declaration). Mirrors the
     *  servlets' {@code getParameter} reads; the path-addressed record routes take none. */
    private static final Map<String, List<String>> ROUTE_PARAMS = Map.of(
            "nominatim/search", List.of("q", "limit"),
            "overpass/around", List.of("lat", "lon", "radius", "limit"),
            "overpass/features", List.of("bbox", "filter", "type"),
            "overpass/poi", List.of("bbox", "filter", "limit"),
            "sparql", List.of("query", "format"));

    @Override
    public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Set<String> patterns = new TreeSet<>();
        for (ServletRegistration reg : getServletContext().getServletRegistrations().values()) {
            patterns.addAll(reg.getMappings());
        }
        Map<String, TreeSet<String>> formats = formatsByRoute(patterns);

        resp.setContentType("application/json;charset=UTF-8");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Access-Control-Allow-Origin", "*");
        try (PrintWriter w = resp.getWriter()) {
            w.print("{\"routes\":[");
            boolean first = true;
            for (Map.Entry<String, TreeSet<String>> e : formats.entrySet()) {
                if (!first) {
                    w.print(",");
                }
                first = false;
                w.print("{\"name\":\"" + e.getKey() + "\",\"formats\":"
                        + jsonArray(e.getValue()) + ",\"params\":"
                        + jsonArray(ROUTE_PARAMS.getOrDefault(e.getKey(), List.of())) + "}");
            }
            w.print("]}");
        }
    }

    /**
     * Map each logical route to the sorted set of representation formats it serves (from the
     * url-patterns' suffixes; a route with only a bare/wildcard pattern gets an empty set —
     * content-negotiated). Pure (no servlet API), so it is unit-testable.
     */
    static Map<String, TreeSet<String>> formatsByRoute(Collection<String> patterns) {
        Map<String, TreeSet<String>> out = new TreeMap<>();
        for (String pattern : patterns) {
            String r = pattern.startsWith("/") ? pattern.substring(1) : pattern;
            String fmt = null;
            if (r.endsWith("/*")) {
                r = r.substring(0, r.length() - 2);
            } else {
                int dot = r.lastIndexOf('.');
                if (dot > 0 && FORMAT_SUFFIXES.contains(r.substring(dot))) {
                    fmt = r.substring(dot + 1);
                    r = r.substring(0, dot);
                }
            }
            if (r.isEmpty() || r.contains("*")) {
                continue;
            }
            TreeSet<String> fmts = out.computeIfAbsent(r, k -> new TreeSet<>());
            if (fmt != null) {
                fmts.add(fmt);
            }
        }
        return out;
    }

    /**
     * The sorted, distinct set of logical route names — the back-compatible view of the manifest
     * (a consumer that only needs names reads {@code routes[].name}). Pure. Kept for callers/tests
     * that assert the name set independent of formats/params.
     */
    static List<String> logicalRoutes(Collection<String> patterns) {
        return List.copyOf(formatsByRoute(patterns).keySet());
    }

    private static String jsonArray(Collection<String> values) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String v : values) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append("\"").append(v).append("\"");
        }
        return sb.append("]").toString();
    }
}
