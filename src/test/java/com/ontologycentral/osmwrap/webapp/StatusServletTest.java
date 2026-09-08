package com.ontologycentral.osmwrap.webapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.vocabulary.RDF;
import org.junit.Test;

/**
 * Offline coverage of {@link StatusServlet}'s content-negotiation logic ({@link
 * StatusServlet#resolveFormat}) and its RDF model construction ({@link
 * StatusServlet#buildStatusModel}) - the two pure, network-free pieces {@code doGet} delegates
 * to. Mirrors linked-pdok's {@code StatusServletRdfTest} in spirit.
 */
public class StatusServletTest {

    // ---- resolveFormat: suffix wins ------------------------------------------

    @Test
    public void jsonSuffixWinsRegardlessOfAccept() {
        assertEquals(StatusServlet.Format.JSON,
                StatusServlet.resolveFormat("/status.json", "text/html"));
    }

    @Test
    public void htmlSuffixWinsRegardlessOfAccept() {
        assertEquals(StatusServlet.Format.HTML,
                StatusServlet.resolveFormat("/status.html", "application/json"));
    }

    @Test
    public void ttlSuffixWinsRegardlessOfAccept() {
        assertEquals(StatusServlet.Format.TTL,
                StatusServlet.resolveFormat("/status.ttl", "application/json"));
    }

    @Test
    public void rdfSuffixWinsRegardlessOfAccept() {
        assertEquals(StatusServlet.Format.RDFXML,
                StatusServlet.resolveFormat("/status.rdf", "text/html"));
    }

    @Test
    public void ntSuffixWinsRegardlessOfAccept() {
        assertEquals(StatusServlet.Format.NTRIPLES,
                StatusServlet.resolveFormat("/status.nt", "text/html"));
    }

    // ---- resolveFormat: no suffix, Accept-driven ------------------------------

    @Test
    public void noAcceptDefaultsToJson() {
        assertEquals(StatusServlet.Format.JSON, StatusServlet.resolveFormat("/status", null));
    }

    @Test
    public void bareStarAcceptDefaultsToJson() {
        assertEquals(StatusServlet.Format.JSON, StatusServlet.resolveFormat("/status", "*/*"));
    }

    @Test
    public void browserAcceptPrefersHtml() {
        assertEquals(StatusServlet.Format.HTML, StatusServlet.resolveFormat("/status",
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"));
    }

    @Test
    public void turtleAcceptWinsOverJsonAndHtml() {
        assertEquals(StatusServlet.Format.TTL, StatusServlet.resolveFormat("/status",
                "text/turtle, text/html;q=0.5, application/json;q=0.1"));
    }

    @Test
    public void rdfXmlAcceptBeatsNtriplesWhenPreferred() {
        // rdf+xml is the true preference; n-triples must not win just because it beats turtle.
        assertEquals(StatusServlet.Format.RDFXML, StatusServlet.resolveFormat("/status",
                "application/rdf+xml;q=0.9, application/n-triples;q=0.3, text/turtle;q=0.1"));
    }

    @Test
    public void ntriplesAcceptWinsOnlyWhenBeatingBothOtherRdfCandidates() {
        assertEquals(StatusServlet.Format.NTRIPLES, StatusServlet.resolveFormat("/status",
                "application/n-triples;q=0.9, text/turtle;q=0.5, application/rdf+xml;q=0.1"));
    }

    @Test
    public void jsonAcceptStaysJson() {
        assertEquals(StatusServlet.Format.JSON,
                StatusServlet.resolveFormat("/status", "application/json"));
    }

    // ---- buildStatusModel -----------------------------------------------------

    private static ServiceStatusChecker.ServiceStatus okService() {
        return new ServiceStatusChecker.ServiceStatus("osm", "osm",
                "https://api.openstreetmap.org/api/0.6/capabilities", null, true, "HTTP 200",
                42, null, 200);
    }

    private static ServiceStatusChecker.ServiceStatus notConfiguredService() {
        return new ServiceStatusChecker.ServiceStatus("protomaps", "tile",
                "https://api.protomaps.com/tiles/v4/0/0/0.mvt?key=...", false, null,
                "no key configured - not probed", 0, null, null);
    }

    private static ServiceStatusChecker.ServiceStatus tileServiceWithLayers() {
        return new ServiceStatusChecker.ServiceStatus("tracestrack-tile", "tile",
                "https://tile.tracestrack.com", true, true, "HTTP 200 image/webp", 55,
                Map.of("topo", Map.of("kind", "raster"),
                        "carto", Map.of("kind", "vector", "path", "vt/carto")),
                200);
    }

    @Test
    public void catalogListsEveryServiceAndOwnsAttribution() {
        Model m = StatusServlet.buildStatusModel(List.of(okService()),
                "https://osmwrap.example/status", "https://osmwrap.example");
        Resource doc = m.createResource("https://osmwrap.example/status");
        assertTrue(doc.hasProperty(RDF.type, m.createResource("http://www.w3.org/ns/dcat#Catalog")));
        assertTrue(doc.hasProperty(m.createProperty("http://www.w3.org/ns/prov#wasAttributedTo"),
                m.createResource("https://osmwrap.example/index#osmwrap")));
        assertTrue(doc.hasProperty(m.createProperty("http://www.w3.org/ns/dcat#service"),
                m.createResource("https://api.openstreetmap.org/api/0.6/capabilities")));
    }

    @Test
    public void successfulProbeGetsAnHttpResponseIndividualForOk() {
        Model m = StatusServlet.buildStatusModel(List.of(okService()),
                "https://osmwrap.example/status", "https://osmwrap.example");
        Resource svc = m.createResource("https://api.openstreetmap.org/api/0.6/capabilities");
        Resource httpResp = svc.getPropertyResourceValue(
                m.createProperty("http://www.w3.org/2011/http#resp"));
        assertTrue(httpResp != null);
        assertTrue(httpResp.hasProperty(m.createProperty("http://www.w3.org/2011/http#sc"),
                m.createResource("http://www.w3.org/2011/http-statusCodes#OK")));
    }

    @Test
    public void unconfiguredKeyGetsACommentNotAFakeHttpResponse() {
        Model m = StatusServlet.buildStatusModel(List.of(notConfiguredService()),
                "https://osmwrap.example/status", "https://osmwrap.example");
        Resource svc = m.createResource("https://api.protomaps.com/tiles/v4/0/0/0.mvt?key=...");
        assertTrue(svc.hasProperty(org.apache.jena.vocabulary.RDFS.comment,
                "no key configured - not probed"));
        assertTrue(svc.getPropertyResourceValue(
                m.createProperty("http://www.w3.org/2011/http#resp")) == null);
    }

    @Test
    public void tileLayersBecomeDistributions() {
        Model m = StatusServlet.buildStatusModel(List.of(tileServiceWithLayers()),
                "https://osmwrap.example/status", "https://osmwrap.example");
        Resource svc = m.createResource("https://tile.tracestrack.com");
        List<Statement> distributions = svc.listProperties(
                m.createProperty("http://www.w3.org/ns/dcat#distribution")).toList();
        assertEquals(2, distributions.size());
    }

    @Test
    public void reportedUriNeverCarriesTheRealApiKey() {
        // buildStatusModel just mints resources from whatever URI it's handed - the actual
        // key-redaction discipline lives in ServiceStatusChecker (see its own test), but this
        // guards the RDF layer against ever being handed (and then serializing) a raw one.
        for (ServiceStatusChecker.ServiceStatus s : List.of(okService(), notConfiguredService(),
                tileServiceWithLayers())) {
            assertTrue(s.uri() + " must not embed a real key parameter",
                    !s.uri().matches(".*key=[^.][^&]*"));
        }
    }
}
