package com.ontologycentral.osmwrap.webapp;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.ResultSet;
import org.apache.jena.query.ResultSetFormatter;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.sparql.util.DatasetUtils;

@SuppressWarnings("serial")
public class SparqlServlet extends HttpServlet {
    private static final Logger _log = Logger.getLogger(SparqlServlet.class.getName());

    public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        handleSparqlRequest(req, resp);
    }

    public void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        handleSparqlRequest(req, resp);
    }

    private void handleSparqlRequest(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String queryString = req.getParameter("query");
        if (queryString == null || queryString.trim().isEmpty()) {
            if ("GET".equals(req.getMethod())) {
                showSparqlForm(req, resp);
                return;
            } else {
                resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing 'query' parameter");
                return;
            }
        }

        // Step 1 — Compute server base and parse (400 on syntax error)
        String scheme = req.getHeader("X-Forwarded-Proto");
        if (scheme == null) scheme = req.getScheme();
        String host = req.getHeader("X-Forwarded-Host");
        if (host == null) host = req.getHeader("Host");
        if (host == null) host = req.getServerName();
        String serverBase = scheme + "://" + host + "/";

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

        // Step 4 — Load graphs (500 on fetch failure)
        Dataset dataset;
        try {
            dataset = DatasetUtils.createDataset(defaultGraphs, namedGraphs);
        } catch (Exception e) {
            _log.log(Level.SEVERE, e.getMessage(), e);
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Failed to load graph data: " + e.getMessage());
            return;
        }

        // Step 5 — Strip FROM clauses, re-parse, execute (500 on failure)
        String stripped = toParse
                .replaceAll("(?i)FROM\\s+NAMED\\s+<[^>]+>", "")
                .replaceAll("(?i)FROM\\s+<[^>]+>", "");
        Query execQuery = QueryFactory.create(stripped);

        String format = getOutputFormat(req.getHeader("Accept"), req.getParameter("format"));
        resp.setCharacterEncoding("UTF-8");
        try (QueryExecution qexec = QueryExecutionFactory.create(execQuery, dataset)) {
            if (execQuery.isSelectType()) {
                ResultSet results = qexec.execSelect();
                java.io.OutputStream out = resp.getOutputStream();
                if ("json".equals(format)) {
                    resp.setContentType("application/sparql-results+json");
                    ResultSetFormatter.outputAsJSON(out, results);
                } else if ("xml".equals(format)) {
                    resp.setContentType("application/sparql-results+xml");
                    ResultSetFormatter.outputAsXML(out, results);
                } else {
                    resp.setContentType("text/tab-separated-values");
                    ResultSetFormatter.outputAsTSV(out, results);
                }
                out.flush();
            } else if (execQuery.isConstructType()) {
                Model result = qexec.execConstruct();
                resp.setContentType("text/turtle");
                java.io.OutputStream out = resp.getOutputStream();
                result.write(out, "TTL");
                out.flush();
            } else if (execQuery.isDescribeType()) {
                Model result = qexec.execDescribe();
                resp.setContentType("text/turtle");
                java.io.OutputStream out = resp.getOutputStream();
                result.write(out, "TTL");
                out.flush();
            } else if (execQuery.isAskType()) {
                boolean result = qexec.execAsk();
                resp.setContentType("application/sparql-results+json");
                PrintWriter out = resp.getWriter();
                out.println("{\"boolean\": " + result + "}");
                out.flush();
            } else {
                resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Unsupported query type");
            }
        } catch (Exception e) {
            _log.log(Level.SEVERE, e.getMessage(), e);
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Query execution failed: " + e.getMessage());
        }
    }

    private String getOutputFormat(String acceptHeader, String formatParam) {
        if (formatParam != null) {
            return formatParam.toLowerCase();
        }
        if (acceptHeader != null) {
            acceptHeader = acceptHeader.toLowerCase();
            if (acceptHeader.contains("application/sparql-results+json")) return "json";
            if (acceptHeader.contains("application/sparql-results+xml")) return "xml";
            if (acceptHeader.contains("text/tab-separated-values")) return "tsv";
        }
        return "json";
    }

    private void showSparqlForm(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("text/html");
        resp.setCharacterEncoding("UTF-8");
        PrintWriter out = resp.getWriter();

        out.println("<!DOCTYPE html>");
        out.println("<html lang=\"en\">");
        out.println("<head>");
        out.println("  <meta charset=\"UTF-8\">");
        out.println("  <title>SPARQL — Linked OSM</title>");
        out.println("</head>");
        out.println("<body>");
        out.println("  <p><a href=\"/\">Home</a></p>");
        out.println("  <h1>SPARQL</h1>");
        out.println("  <p>Execute SPARQL queries over OSM Linked Data loaded via <code>FROM</code> and <code>FROM NAMED</code> clauses.</p>");
        out.println("  <form method=\"GET\" action=\"sparql\">");
        out.println("    <div>");
        out.println("    <textarea cols=\"100\" rows=\"20\" name=\"query\">PREFIX geo: &lt;http://www.w3.org/2003/01/geo/wgs84_pos#&gt;");
        out.println("PREFIX dc:  &lt;http://purl.org/dc/elements/1.1/&gt;");
        out.println("PREFIX rdf: &lt;http://www.w3.org/1999/02/22-rdf-syntax-ns#&gt;");
        out.println("");
        out.println("SELECT ?s ?lat ?lon ?name");
        out.println("FROM &lt;/node/1.rdf&gt;");
        out.println("WHERE {");
        out.println("  ?s geo:lat ?lat ;");
        out.println("     geo:long ?lon .");
        out.println("  OPTIONAL { ?s dc:title ?name . }");
        out.println("}</textarea>");
        out.println("    </div>");
        out.println("    <div>");
        out.println("      <input type=\"radio\" name=\"format\" value=\"tsv\"> TSV");
        out.println("      <input type=\"radio\" name=\"format\" value=\"json\" checked> JSON");
        out.println("      <input type=\"radio\" name=\"format\" value=\"xml\"> XML");
        out.println("    </div>");
        out.println("    <div>");
        out.println("      <input type=\"reset\"> <input type=\"submit\">");
        out.println("    </div>");
        out.println("  </form>");
        out.println("</body>");
        out.println("</html>");

        out.close();
    }
}
