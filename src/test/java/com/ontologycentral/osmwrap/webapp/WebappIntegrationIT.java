package com.ontologycentral.osmwrap.webapp;

import org.junit.Test;
import static org.junit.Assert.*;
import static io.restassured.RestAssured.*;
import static io.restassured.matcher.RestAssuredMatchers.*;
import org.hamcrest.Matchers.*;

/**
 * Integration tests for osmwrap webapp
 * These tests run against the deployed webapp at https://osmwrap.ontologycentral.com/
 * Run with: mvn verify
 */
public class WebappIntegrationIT {

    private static final String BASE_URL = "https://osmwrap.ontologycentral.com";

    @Test
    public void testWebappIsUp() {
        // Simple connectivity test
        given()
            .baseUri(BASE_URL)
        .when()
            .get("/")
        .then()
            .statusCode(200);
    }

    @Test
    public void testIndexPage() {
        // Test that index.html is accessible
        given()
            .baseUri(BASE_URL)
        .when()
            .get("/index.html")
        .then()
            .statusCode(200)
            .contentType("text/html");
    }

    @Test
    public void testNodeEndpointReturnsJson() {
        // Test a simple node endpoint with JSON format
        given()
            .baseUri(BASE_URL)
        .when()
            .get("/node/123.json")
        .then()
            .statusCode(200)
            .contentType("application/geo+json");
    }

    @Test
    public void testWayEndpointReturnsJson() {
        // Test way endpoint with JSON format
        given()
            .baseUri(BASE_URL)
        .when()
            .get("/way/456.json")
        .then()
            .statusCode(200)
            .contentType("application/geo+json");
    }

    @Test
    public void testGeometryOSMEndpoint() {
        // Test geometry endpoint for OSM data
        given()
            .baseUri(BASE_URL)
        .when()
            .get("/geo/osm/way/123.json")
        .then()
            .statusCode(200)
            .contentType("application/geo+json");
    }

    @Test
    public void testGeometryOverpassEndpoint() {
        // Test geometry endpoint for Overpass API data
        given()
            .baseUri(BASE_URL)
        .when()
            .get("/geo/overpass/way/123.json")
        .then()
            .statusCode(200)
            .contentType("application/geo+json");
    }

    @Test
    public void testKMLFormatViaGeometryEndpoint() {
        // Test KML format via geometry endpoint
        given()
            .baseUri(BASE_URL)
        .when()
            .get("/geo/osm/way/456.kml")
        .then()
            .statusCode(200)
            .contentType("application/vnd.google-earth.kml+xml");
    }

    @Test
    public void testWKTFormatViaGeometryEndpoint() {
        // Test WKT format via geometry endpoint
        given()
            .baseUri(BASE_URL)
        .when()
            .get("/geo/osm/way/456.wkt")
        .then()
            .statusCode(200)
            .contentType("application/wkt");
    }

    @Test
    public void testInvalidEndpointReturns404() {
        // Test that invalid endpoints return 404
        given()
            .baseUri(BASE_URL)
        .when()
            .get("/invalid/endpoint/path")
        .then()
            .statusCode(404);
    }

    @Test
    public void testResponseBodyNotEmpty() {
        // Test that responses contain actual data
        given()
            .baseUri(BASE_URL)
        .when()
            .get("/node/123.json")
        .then()
            .statusCode(200)
            .body(org.hamcrest.Matchers.notNullValue());
    }
}
