package com.ontologycentral.osmwrap;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class AcceptHeaderTest {

    // The browser/semantic-web client header from the bug report
    static final String SEMANTIC_BROWSER =
        "application/ld+json;q=1,application/n-quads;q=1,application/n-triples;q=1,"
        + "application/rdf+xml;q=1,application/trig;q=1,text/turtle;q=1,text/n3;q=1,"
        + "text/html;q=0.950,application/xhtml+xml;q=0.950,application/xml;q=0.850,"
        + "*/*;q=0.750";

    // --- parse ---

    @Test
    public void parseEmpty() {
        assertEquals(List.of(), AcceptHeader.parse(null));
        assertEquals(List.of(), AcceptHeader.parse(""));
        assertEquals(List.of(), AcceptHeader.parse("   "));
    }

    @Test
    public void parseSingle() {
        List<AcceptHeader.AcceptType> list = AcceptHeader.parse("text/turtle");
        assertEquals(1, list.size());
        assertEquals("text", list.get(0).type());
        assertEquals("turtle", list.get(0).subtype());
        assertEquals(1.0, list.get(0).q(), 0.001);
    }

    @Test
    public void parseQValue() {
        List<AcceptHeader.AcceptType> list = AcceptHeader.parse("text/html;q=0.9, application/json;q=0.5");
        assertEquals("text", list.get(0).type());
        assertEquals("html", list.get(0).subtype());
        assertEquals(0.9, list.get(0).q(), 0.001);
        assertEquals(0.5, list.get(1).q(), 0.001);
    }

    @Test
    public void parseSortsByQDescending() {
        List<AcceptHeader.AcceptType> list =
            AcceptHeader.parse("text/html;q=0.5, application/json, */*;q=0.1");
        assertEquals(1.0, list.get(0).q(), 0.001); // application/json
        assertEquals(0.5, list.get(1).q(), 0.001); // text/html
        assertEquals(0.1, list.get(2).q(), 0.001); // */*
    }

    @Test
    public void parseWildcard() {
        List<AcceptHeader.AcceptType> list = AcceptHeader.parse("*/*");
        assertEquals("*", list.get(0).type());
        assertEquals("*", list.get(0).subtype());
    }

    // --- maxQ ---

    @Test
    public void maxQExplicitMatch() {
        List<AcceptHeader.AcceptType> list = AcceptHeader.parse("application/rdf+xml;q=0.8");
        assertEquals(0.8, AcceptHeader.maxQ(list, "application", "rdf+xml"), 0.001);
    }

    @Test
    public void maxQWildcardFallback() {
        List<AcceptHeader.AcceptType> list = AcceptHeader.parse("*/*;q=0.5");
        assertEquals(0.5, AcceptHeader.maxQ(list, "application", "json"), 0.001);
        assertEquals(0.5, AcceptHeader.maxQ(list, "text", "turtle"), 0.001);
    }

    @Test
    public void maxQAbsent() {
        List<AcceptHeader.AcceptType> list = AcceptHeader.parse("text/html");
        assertEquals(0.0, AcceptHeader.maxQ(list, "application", "json"), 0.001);
    }

    @Test
    public void maxQExplicitBeatsWildcard() {
        // explicit rdf+xml;q=1 should win over */*;q=0.75 for the rdf+xml slot
        List<AcceptHeader.AcceptType> list =
            AcceptHeader.parse("application/rdf+xml;q=1, */*;q=0.75");
        assertEquals(1.0, AcceptHeader.maxQ(list, "application", "rdf+xml"), 0.001);
        // json is not explicit → falls back to */*
        assertEquals(0.75, AcceptHeader.maxQ(list, "application", "json"), 0.001);
    }

    // --- semantic-browser header: RDF should win ---

    @Test
    public void semanticBrowserPrefersRdfOverJson() {
        List<AcceptHeader.AcceptType> list = AcceptHeader.parse(SEMANTIC_BROWSER);
        double qJson = Math.max(
            AcceptHeader.maxQ(list, "application", "geo+json"),
            AcceptHeader.maxQ(list, "application", "json"));
        double qRdf = Math.max(
            AcceptHeader.maxQ(list, "application", "rdf+xml"),
            AcceptHeader.maxQ(list, "text", "turtle"));
        assertTrue("RDF should beat JSON for semantic browser Accept", qRdf > qJson);
    }

    @Test
    public void semanticBrowserRdfXmlQ() {
        List<AcceptHeader.AcceptType> list = AcceptHeader.parse(SEMANTIC_BROWSER);
        assertEquals(1.0, AcceptHeader.maxQ(list, "application", "rdf+xml"), 0.001);
    }

    @Test
    public void semanticBrowserJsonOnlyViaWildcard() {
        List<AcceptHeader.AcceptType> list = AcceptHeader.parse(SEMANTIC_BROWSER);
        // application/json and application/geo+json are only matched via */*;q=0.75
        assertEquals(0.75, AcceptHeader.maxQ(list, "application", "json"), 0.001);
        assertEquals(0.75, AcceptHeader.maxQ(list, "application", "geo+json"), 0.001);
    }

    // --- explicit JSON request ---

    @Test
    public void explicitGeoJsonWins() {
        List<AcceptHeader.AcceptType> list = AcceptHeader.parse("application/geo+json");
        double qJson = Math.max(
            AcceptHeader.maxQ(list, "application", "geo+json"),
            AcceptHeader.maxQ(list, "application", "json"));
        double qRdf = Math.max(
            AcceptHeader.maxQ(list, "application", "rdf+xml"),
            AcceptHeader.maxQ(list, "text", "turtle"));
        assertTrue("geo+json should win when explicitly requested", qJson > qRdf);
    }

    @Test
    public void explicitJsonWins() {
        List<AcceptHeader.AcceptType> list = AcceptHeader.parse("application/json");
        double qJson = Math.max(
            AcceptHeader.maxQ(list, "application", "geo+json"),
            AcceptHeader.maxQ(list, "application", "json"));
        double qRdf = Math.max(
            AcceptHeader.maxQ(list, "application", "rdf+xml"),
            AcceptHeader.maxQ(list, "text", "turtle"));
        assertTrue("application/json should win when explicitly requested", qJson > qRdf);
    }

    // --- wildcard alone → RDF default ---

    @Test
    public void wildcardAloneDefaultsToRdf() {
        List<AcceptHeader.AcceptType> list = AcceptHeader.parse("*/*");
        double qJson = Math.max(
            AcceptHeader.maxQ(list, "application", "geo+json"),
            AcceptHeader.maxQ(list, "application", "json"));
        double qRdf = Math.max(
            AcceptHeader.maxQ(list, "application", "rdf+xml"),
            AcceptHeader.maxQ(list, "text", "turtle"));
        // Both are equal; qJson > qRdf must be false so we serve RDF
        assertFalse("*/* alone must not trigger JSON mode", qJson > qRdf);
    }

    // --- mixed q: JSON lower than RDF ---

    @Test
    public void jsonLowerQThanRdf() {
        List<AcceptHeader.AcceptType> list =
            AcceptHeader.parse("application/json;q=0.5, application/rdf+xml;q=0.9");
        double qJson = Math.max(
            AcceptHeader.maxQ(list, "application", "geo+json"),
            AcceptHeader.maxQ(list, "application", "json"));
        double qRdf = Math.max(
            AcceptHeader.maxQ(list, "application", "rdf+xml"),
            AcceptHeader.maxQ(list, "text", "turtle"));
        assertFalse("Lower-q JSON should not win over higher-q RDF", qJson > qRdf);
    }

    @Test
    public void jsonHigherQThanRdf() {
        List<AcceptHeader.AcceptType> list =
            AcceptHeader.parse("application/json;q=0.9, application/rdf+xml;q=0.5");
        double qJson = Math.max(
            AcceptHeader.maxQ(list, "application", "geo+json"),
            AcceptHeader.maxQ(list, "application", "json"));
        double qRdf = Math.max(
            AcceptHeader.maxQ(list, "application", "rdf+xml"),
            AcceptHeader.maxQ(list, "text", "turtle"));
        assertTrue("Higher-q JSON should beat lower-q RDF", qJson > qRdf);
    }

    // --- RdfFilter: rdf+xml vs turtle preference ---

    @Test
    public void prefersRdfXmlOverTurtle() {
        List<AcceptHeader.AcceptType> list =
            AcceptHeader.parse("application/rdf+xml;q=1.0, text/turtle;q=0.5");
        assertTrue(AcceptHeader.prefers(list, "application", "rdf+xml", "text", "turtle"));
    }

    @Test
    public void prefersTurtleOverRdfXml() {
        List<AcceptHeader.AcceptType> list =
            AcceptHeader.parse("application/rdf+xml;q=0.5, text/turtle;q=1.0");
        assertFalse(AcceptHeader.prefers(list, "application", "rdf+xml", "text", "turtle"));
    }

    @Test
    public void emptyHeaderDoesNotPreferRdfXml() {
        List<AcceptHeader.AcceptType> list = AcceptHeader.parse(null);
        assertFalse(AcceptHeader.prefers(list, "application", "rdf+xml", "text", "turtle"));
    }
}
