package com.ontologycentral.osmwrap.webapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.Test;

/** The url-pattern → logical-route reduction behind the {@code /routes} manifest. */
public class RoutesServletTest {

    @Test
    public void collapsesSuffixesAndWildcardsToDistinctSortedRoutes() {
        List<String> routes = RoutesServlet.logicalRoutes(List.of(
                "/nominatim/search", "/nominatim/search.json", "/nominatim/search.rdf",
                "/nominatim/search.ttl",
                "/overpass/features", "/overpass/features.json",
                "/overpass/poi", "/overpass/around",
                "/overpass/node/*", "/overpass/way/*", "/overpass/relation/*",
                "/osm/node/*", "/osm/relation/*", "/osm/way/*",
                "/geo/osm/*", "/geo/overpass/*",
                "/changeset/*", "/tag/*", "/error", "/sparql",
                "/routes", "/routes.json"));

        // Path-carrying route names, distinct + sorted; suffixes and /* wildcards collapsed.
        assertEquals(
                List.of(
                        "changeset", "error", "geo/osm", "geo/overpass", "nominatim/search",
                        "osm/node", "osm/relation", "osm/way", "overpass/around",
                        "overpass/features", "overpass/node", "overpass/poi",
                        "overpass/relation", "overpass/way", "routes", "sparql", "tag"),
                routes);
    }

    @Test
    public void dropsEmptyAndUnnormalisablePatterns() {
        // The filter mappings (/*) and root must not appear as routes.
        List<String> routes = RoutesServlet.logicalRoutes(List.of("/", "/*", "/nominatim/search"));
        assertEquals(List.of("nominatim/search"), routes);
        assertFalse(routes.contains(""));
        assertTrue(routes.contains("nominatim/search"));
    }

    @Test
    public void formatsByRouteGroupsSuffixesAndLeavesWildcardRoutesEmpty() {
        var m = RoutesServlet.formatsByRoute(List.of(
                "/nominatim/search", "/nominatim/search.json", "/nominatim/search.rdf",
                "/nominatim/search.ttl",
                "/osm/node/*", // wildcard record route: content-negotiated, no enumerated suffixes
                "/routes", "/routes.json"));
        assertEquals(Set.of("json", "rdf", "ttl"), m.get("nominatim/search"));
        assertTrue("a wildcard record route enumerates no formats", m.get("osm/node").isEmpty());
        assertEquals(Set.of("json"), m.get("routes"));
    }
}
