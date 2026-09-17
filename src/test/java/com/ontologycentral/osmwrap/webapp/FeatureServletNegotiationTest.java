package com.ontologycentral.osmwrap.webapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import com.ontologycentral.osmwrap.webapp.FeatureServlet.Negotiated;

/** {@link FeatureServlet#negotiate}: extension first, then Accept, Turtle by default. */
public class FeatureServletNegotiationTest {

    private static final String FIREFOX =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8";
    private static final String CHROME =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7";

    @Test
    public void extensionWinsOverAccept() {
        assertEquals(new Negotiated("100", "json"), FeatureServlet.negotiate("100.json", FIREFOX));
        assertEquals(new Negotiated("100", "rdf"), FeatureServlet.negotiate("100.ttl", FIREFOX));
        assertEquals(new Negotiated("100", "rdf"), FeatureServlet.negotiate("100.rdf", "application/geo+json"));
        assertEquals(new Negotiated("100", "gml"), FeatureServlet.negotiate("100.gml", FIREFOX));
        assertEquals(new Negotiated("100", "html"), FeatureServlet.negotiate("100.html", "text/turtle"));
    }

    @Test
    public void browsersGetHtml() {
        assertEquals(new Negotiated("100", "html"), FeatureServlet.negotiate("100", FIREFOX));
        assertEquals(new Negotiated("100", "html"), FeatureServlet.negotiate("100", CHROME));
    }

    @Test
    public void dataClientsKeepGettingData() {
        assertEquals("curl default", new Negotiated("100", "rdf"), FeatureServlet.negotiate("100", "*/*"));
        assertEquals("no header", new Negotiated("100", "rdf"), FeatureServlet.negotiate("100", null));
        assertEquals(new Negotiated("100", "rdf"), FeatureServlet.negotiate("100", "text/turtle"));
        assertEquals(new Negotiated("100", "rdf"), FeatureServlet.negotiate("100", "application/rdf+xml"));
        assertEquals(new Negotiated("100", "json"), FeatureServlet.negotiate("100", "application/geo+json"));
        assertEquals(new Negotiated("100", "json"), FeatureServlet.negotiate("100", "application/json"));
        assertEquals(new Negotiated("100", "gml"), FeatureServlet.negotiate("100", "application/gml+xml"));
        assertEquals("semantic-web browser: RDF at q=1 beats html at 0.95",
                new Negotiated("100", "rdf"), FeatureServlet.negotiate("100",
                        "text/turtle;q=1,application/rdf+xml;q=1,text/html;q=0.950,*/*;q=0.750"));
        assertEquals("html tied with data is not a preference",
                new Negotiated("100", "rdf"), FeatureServlet.negotiate("100", "text/html,text/turtle"));
    }

    @Test
    public void doubleExtensionsAndEmptyIds() {
        assertEquals(new Negotiated("123", "json"), FeatureServlet.negotiate("123.json.json", null));
        assertNull(FeatureServlet.negotiate("", null));
        assertNull(FeatureServlet.negotiate(".json", null));
    }
}
