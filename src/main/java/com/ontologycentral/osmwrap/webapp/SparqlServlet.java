package com.ontologycentral.osmwrap.webapp;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
        try {
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

            queryString = URLDecoder.decode(queryString, StandardCharsets.UTF_8);

            Query query = QueryFactory.create(queryString);

            // Resolve relative URIs in FROM / FROM NAMED clauses
            String resolvedQueryString = queryString;
            for (String fromUri : query.getGraphURIs()) {
                String absoluteUri = resolveUri(fromUri, req);
                resolvedQueryString = resolvedQueryString.replace("<" + fromUri + ">", "<" + absoluteUri + ">");
            }
            for (String namedGraphUri : query.getNamedGraphURIs()) {
                String absoluteUri = resolveUri(namedGraphUri, req);
                resolvedQueryString = resolvedQueryString.replace("<" + namedGraphUri + ">", "<" + absoluteUri + ">");
            }

            query = QueryFactory.create(resolvedQueryString);

            List<String> defaultGraphList = new ArrayList<>();
            List<String> namedGraphList = new ArrayList<>();

            for (String fromUri : query.getGraphURIs()) {
                defaultGraphList.add(resolveUri(fromUri, req));
            }
            for (String namedGraphUri : query.getNamedGraphURIs()) {
                namedGraphList.add(resolveUri(namedGraphUri, req));
            }

            Dataset dataset;
            if (!defaultGraphList.isEmpty() || !namedGraphList.isEmpty()) {
                dataset = DatasetUtils.createDataset(defaultGraphList, namedGraphList);
            } else {
                dataset = DatasetFactory.create();
            }

            // Remove FROM clauses — data already loaded into dataset
            String queryStringForExecution = resolvedQueryString;
            if (!query.getGraphURIs().isEmpty()) {
                queryStringForExecution = queryStringForExecution.replaceAll("FROM\\s+<[^>]+>", "");
            }
            Query queryForExecution = QueryFactory.create(queryStringForExecution);

            try (QueryExecution qexec = QueryExecutionFactory.create(queryForExecution, dataset)) {
                String format = getOutputFormat(req.getHeader("Accept"), req.getParameter("format"));
                resp.setCharacterEncoding("UTF-8");

                if (queryForExecution.isSelectType()) {
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
                } else if (queryForExecution.isConstructType()) {
                    Model result = qexec.execConstruct();
                    resp.setContentType("text/turtle");
                    java.io.OutputStream out = resp.getOutputStream();
                    result.write(out, "TTL");
                    out.flush();
                } else if (queryForExecution.isDescribeType()) {
                    Model result = qexec.execDescribe();
                    resp.setContentType("text/turtle");
                    java.io.OutputStream out = resp.getOutputStream();
                    result.write(out, "TTL");
                    out.flush();
                } else if (queryForExecution.isAskType()) {
                    boolean result = qexec.execAsk();
                    resp.setContentType("application/sparql-results+json");
                    PrintWriter out = resp.getWriter();
                    out.println("{\"boolean\": " + result + "}");
                    out.flush();
                } else {
                    resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Unsupported query type");
                }
            }

        } catch (Exception e) {
            _log.log(Level.SEVERE, e.getMessage(), e);
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error executing query: " + e.getMessage());
        }
    }

    private String resolveUri(String uri, HttpServletRequest req) {
        if (uri.startsWith("http://") || uri.startsWith("https://")) {
            return uri;
        }

        String scheme = req.getHeader("X-Forwarded-Proto");
        if (scheme == null) scheme = req.getScheme();
        String serverName = req.getHeader("X-Forwarded-Host");
        if (serverName == null) serverName = req.getServerName();
        int serverPort = req.getServerPort();
        String contextPath = req.getContextPath();

        String baseUrl = scheme + "://" + serverName;
        if (!serverName.contains(":") &&
            (("http".equals(scheme) && serverPort != 80) ||
             ("https".equals(scheme) && serverPort != 443))) {
            baseUrl += ":" + serverPort;
        }
        baseUrl += contextPath + "/";

        return baseUrl + uri;
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
        out.println("  <p><a href=\"/\">Linked OSM</a></p>");
        out.println("  <h1>SPARQL</h1>");
        out.println("  <p>Execute SPARQL queries over OSM Linked Data loaded via <code>FROM</code> clauses.</p>");
        out.println("  <form method=\"GET\" action=\"sparql\">");
        out.println("    <div>");
        out.println("    <textarea cols=\"100\" rows=\"20\" name=\"query\">PREFIX geo: &lt;http://www.w3.org/2003/01/geo/wgs84_pos#&gt;");
        out.println("PREFIX dc:  &lt;http://purl.org/dc/elements/1.1/&gt;");
        out.println("PREFIX rdf: &lt;http://www.w3.org/1999/02/22-rdf-syntax-ns#&gt;");
        out.println("");
        out.println("SELECT ?s ?lat ?lon ?name");
        out.println("FROM &lt;https://osmwrap.ontologycentral.com/node/1.rdf&gt;");
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
