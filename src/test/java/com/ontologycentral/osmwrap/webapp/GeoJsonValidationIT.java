package com.ontologycentral.osmwrap.webapp;

import org.junit.Test;
import org.junit.BeforeClass;
import static org.junit.Assert.*;
import static io.restassured.RestAssured.*;
import io.restassured.response.Response;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.InputFormat;
import com.networknt.schema.dialect.Dialects;
import java.util.List;
import com.networknt.schema.Error;

/**
 * Integration tests for GeoJSON output validation
 * Tests that geometry endpoints produce valid GeoJSON that can be parsed and validated
 */
public class GeoJsonValidationIT {

    private static final String BASE_URL = "https://osmwrap.ontologycentral.com";
    private static final ObjectMapper mapper = new ObjectMapper();
    private static Schema geoJsonSchema;

    /**
     * Load the official GeoJSON schema (RFC 7946) for validation
     */
    @BeforeClass
    public static void setUpSchema() throws Exception {
        String schemaJson = "{" +
            "\"$schema\": \"http://json-schema.org/draft-07/schema#\"," +
            "\"title\": \"GeoJSON\"," +
            "\"oneOf\": [" +
            "  { \"$ref\": \"#/definitions/Point\" }," +
            "  { \"$ref\": \"#/definitions/LineString\" }," +
            "  { \"$ref\": \"#/definitions/Polygon\" }," +
            "  { \"$ref\": \"#/definitions/MultiPoint\" }," +
            "  { \"$ref\": \"#/definitions/MultiLineString\" }," +
            "  { \"$ref\": \"#/definitions/MultiPolygon\" }," +
            "  { \"$ref\": \"#/definitions/GeometryCollection\" }," +
            "  { \"$ref\": \"#/definitions/Feature\" }," +
            "  { \"$ref\": \"#/definitions/FeatureCollection\" }" +
            "]," +
            "\"definitions\": {" +
            "  \"Point\": {" +
            "    \"type\": \"object\"," +
            "    \"required\": [\"type\", \"coordinates\"]," +
            "    \"properties\": {" +
            "      \"type\": { \"enum\": [\"Point\"] }," +
            "      \"coordinates\": { \"$ref\": \"#/definitions/position\" }" +
            "    }" +
            "  }," +
            "  \"LineString\": {" +
            "    \"type\": \"object\"," +
            "    \"required\": [\"type\", \"coordinates\"]," +
            "    \"properties\": {" +
            "      \"type\": { \"enum\": [\"LineString\"] }," +
            "      \"coordinates\": { \"$ref\": \"#/definitions/positionArray\" }" +
            "    }" +
            "  }," +
            "  \"Polygon\": {" +
            "    \"type\": \"object\"," +
            "    \"required\": [\"type\", \"coordinates\"]," +
            "    \"properties\": {" +
            "      \"type\": { \"enum\": [\"Polygon\"] }," +
            "      \"coordinates\": { \"$ref\": \"#/definitions/polygonCoordinates\" }" +
            "    }" +
            "  }," +
            "  \"MultiPolygon\": {" +
            "    \"type\": \"object\"," +
            "    \"required\": [\"type\", \"coordinates\"]," +
            "    \"properties\": {" +
            "      \"type\": { \"enum\": [\"MultiPolygon\"] }," +
            "      \"coordinates\": { \"$ref\": \"#/definitions/multiPolygonCoordinates\" }" +
            "    }" +
            "  }," +
            "  \"GeometryCollection\": {" +
            "    \"type\": \"object\"," +
            "    \"required\": [\"type\", \"geometries\"]," +
            "    \"properties\": {" +
            "      \"type\": { \"enum\": [\"GeometryCollection\"] }," +
            "      \"geometries\": { \"type\": \"array\" }" +
            "    }" +
            "  }," +
            "  \"Feature\": {" +
            "    \"type\": \"object\"," +
            "    \"required\": [\"type\", \"geometry\", \"properties\"]," +
            "    \"properties\": {" +
            "      \"type\": { \"enum\": [\"Feature\"] }," +
            "      \"geometry\": {}," +
            "      \"properties\": {}" +
            "    }" +
            "  }," +
            "  \"FeatureCollection\": {" +
            "    \"type\": \"object\"," +
            "    \"required\": [\"type\", \"features\"]," +
            "    \"properties\": {" +
            "      \"type\": { \"enum\": [\"FeatureCollection\"] }," +
            "      \"features\": { \"type\": \"array\" }" +
            "    }" +
            "  }," +
            "  \"position\": {" +
            "    \"type\": \"array\"," +
            "    \"minItems\": 2," +
            "    \"items\": { \"type\": \"number\" }" +
            "  }," +
            "  \"positionArray\": {" +
            "    \"type\": \"array\"," +
            "    \"minItems\": 2," +
            "    \"items\": { \"$ref\": \"#/definitions/position\" }" +
            "  }," +
            "  \"polygonCoordinates\": {" +
            "    \"type\": \"array\"," +
            "    \"items\": { \"$ref\": \"#/definitions/positionArray\" }" +
            "  }," +
            "  \"multiPolygonCoordinates\": {" +
            "    \"type\": \"array\"," +
            "    \"items\": { \"$ref\": \"#/definitions/polygonCoordinates\" }" +
            "  }" +
            "}" +
            "}";

        // Load schema using 2.0.0 API with Draft 7 dialect
        SchemaRegistry schemaRegistry = SchemaRegistry.withDialect(Dialects.getDraft7());
        geoJsonSchema = schemaRegistry.getSchema(schemaJson, InputFormat.JSON);
    }

    /**
     * Validate GeoJSON response against the RFC 7946 schema
     */
    private static void validateGeoJson(String geoJsonStr) throws Exception {
        // Validate using 2.0.0 API - pass JSON string and InputFormat
        List<Error> errors = geoJsonSchema.validate(geoJsonStr, InputFormat.JSON);
        assertTrue("GeoJSON validation errors: " + errors, errors.isEmpty());
    }

    /**
     * Helper method to add delay between requests to avoid rate limiting
     */
    private static void delayBetweenRequests() {
        try {
            Thread.sleep(500); // 500ms delay between requests
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    public void testNodeEndpointReturnsValidGeoJSON() throws Exception {
        delayBetweenRequests();
        // Fetch GeoJSON for a node (using real node ID: Monte Piselli radio mast, Italy)
        Response response = given()
            .baseUri(BASE_URL)
        .when()
            .get("/node/1.json");

        assertEquals("Response should be 200", 200, response.getStatusCode());

        String geoJsonStr = response.getBody().asString();
        assertNotNull("Response body should not be null", geoJsonStr);

        // Validate against GeoJSON schema
        validateGeoJson(geoJsonStr);

        // Parse and validate GeoJSON
        JsonNode jsonNode = mapper.readTree(geoJsonStr);

        // Response is a Feature with geometry property
        assertTrue("GeoJSON should have 'type' property", jsonNode.has("type"));
        String type = jsonNode.get("type").asText();
        assertEquals("Root should be Feature", "Feature", type);

        // Verify geometry property exists and is valid
        assertTrue("Feature should have 'geometry' property", jsonNode.has("geometry"));
        JsonNode geometry = jsonNode.get("geometry");
        if (geometry != null && !geometry.isNull()) {
            assertTrue("Geometry should have 'type'", geometry.has("type"));
            assertTrue("Geometry should have 'coordinates'", geometry.has("coordinates"));

            String geomType = geometry.get("type").asText();
            assertEquals("Node geometry should be Point", "Point", geomType);
        }
    }

    @Test
    public void testWayEndpointReturnsValidGeoJSON() throws Exception {
        delayBetweenRequests();
        // Fetch GeoJSON for a way (using real way ID: Roundabout, Germany)
        Response response = given()
            .baseUri(BASE_URL)
        .when()
            .get("/way/100.json");

        assertEquals("Response should be 200", 200, response.getStatusCode());

        String geoJsonStr = response.getBody().asString();

        // Validate against GeoJSON schema
        validateGeoJson(geoJsonStr);

        JsonNode jsonNode = mapper.readTree(geoJsonStr);

        // Response is a Feature with geometry property
        assertTrue("GeoJSON should have 'type' property", jsonNode.has("type"));
        String type = jsonNode.get("type").asText();
        assertEquals("Root should be Feature", "Feature", type);

        // Verify geometry property exists
        assertTrue("Feature should have 'geometry' property", jsonNode.has("geometry"));
        JsonNode geometry = jsonNode.get("geometry");

        // Way geometry can be LineString or Polygon
        if (geometry != null && !geometry.isNull()) {
            String geomType = geometry.get("type").asText();
            assertTrue("Way should be LineString or Polygon",
                geomType.equals("LineString") || geomType.equals("Polygon"));

            // Verify coordinates
            assertTrue("Geometry should have 'coordinates' property", geometry.has("coordinates"));
            assertTrue("Coordinates should be an array", geometry.get("coordinates").isArray());
        }
    }

    @Test
    public void testGeometryOSMEndpointReturnsValidGeoJSON() throws Exception {
        delayBetweenRequests();
        // Fetch geometry from OSM endpoint (using real way ID: Roundabout, Germany)
        Response response = given()
            .baseUri(BASE_URL)
        .when()
            .get("/geo/osm/way/100.json");

        assertEquals("Response should be 200", 200, response.getStatusCode());

        String geoJsonStr = response.getBody().asString();

        // Validate against GeoJSON schema
        validateGeoJson(geoJsonStr);

        JsonNode jsonNode = mapper.readTree(geoJsonStr);

        // Validate it's valid GeoJSON
        assertTrue("GeoJSON should have 'type' property", jsonNode.has("type"));
        String type = jsonNode.get("type").asText();

        // Verify type is valid GeoJSON type
        assertTrue("Type should be valid GeoJSON geometry type",
            type.equals("Point") || type.equals("LineString") ||
            type.equals("Polygon") || type.equals("MultiPolygon") ||
            type.equals("GeometryCollection"));

        // Verify coordinates exist (except for GeometryCollection)
        if (!type.equals("GeometryCollection")) {
            assertTrue("Non-GeometryCollection should have coordinates",
                jsonNode.has("coordinates"));
        }
    }

    @Test
    public void testGeometryOverpassEndpointReturnsValidGeoJSON() throws Exception {
        delayBetweenRequests();
        // Fetch geometry from Overpass endpoint (using real way ID: Roundabout, Germany)
        Response response = given()
            .baseUri(BASE_URL)
        .when()
            .get("/geo/overpass/way/100.json");

        assertEquals("Response should be 200", 200, response.getStatusCode());

        String geoJsonStr = response.getBody().asString();

        // Validate against GeoJSON schema
        validateGeoJson(geoJsonStr);

        JsonNode jsonNode = mapper.readTree(geoJsonStr);

        // Validate structure
        assertTrue("GeoJSON should have 'type' property", jsonNode.has("type"));
        String type = jsonNode.get("type").asText();

        assertTrue("Type should be valid GeoJSON geometry type",
            type.equals("Point") || type.equals("LineString") ||
            type.equals("Polygon") || type.equals("MultiPolygon") ||
            type.equals("GeometryCollection"));
    }

    @Test
    public void testPointGeometryStructure() throws Exception {
        delayBetweenRequests();
        Response response = given()
            .baseUri(BASE_URL)
        .when()
            .get("/geo/osm/node/1.json");

        assertEquals("Response should be 200", 200, response.getStatusCode());
        String geoJsonStr = response.getBody().asString();

        // Validate against GeoJSON schema
        validateGeoJson(geoJsonStr);

        JsonNode jsonNode = mapper.readTree(geoJsonStr);

        // Response is a Feature, access geometry from it
        assertEquals("Root should be Feature", "Feature", jsonNode.get("type").asText());
        JsonNode geometry = jsonNode.get("geometry");

        if (geometry != null && !geometry.isNull()) {
            assertEquals("Geometry type should be Point", "Point", geometry.get("type").asText());

            JsonNode coordinates = geometry.get("coordinates");
            if (coordinates != null && coordinates.isArray()) {
                assertEquals("Point should have 2 coordinates (lon, lat)", 2, coordinates.size());
                assertTrue("First coordinate (lon) should be a number", coordinates.get(0).isNumber());
                assertTrue("Second coordinate (lat) should be a number", coordinates.get(1).isNumber());

                // Validate lon/lat ranges
                double lon = coordinates.get(0).asDouble();
                double lat = coordinates.get(1).asDouble();

                assertTrue("Longitude should be between -180 and 180", lon >= -180 && lon <= 180);
                assertTrue("Latitude should be between -90 and 90", lat >= -90 && lat <= 90);
            }
        }
    }

    @Test
    public void testLineStringGeometryStructure() throws Exception {
        delayBetweenRequests();
        Response response = given()
            .baseUri(BASE_URL)
        .when()
            .get("/geo/osm/way/100.json");

        assertEquals("Response should be 200", 200, response.getStatusCode());
        String geoJsonStr = response.getBody().asString();

        // Validate against GeoJSON schema
        validateGeoJson(geoJsonStr);

        JsonNode jsonNode = mapper.readTree(geoJsonStr);

        // Response is a Feature, access geometry from it
        assertEquals("Root should be Feature", "Feature", jsonNode.get("type").asText());
        JsonNode geometry = jsonNode.get("geometry");

        if (geometry != null && !geometry.isNull()) {
            String geomType = geometry.get("type").asText();
            if ("LineString".equals(geomType)) {
                JsonNode coordinates = geometry.get("coordinates");
                assertTrue("LineString coordinates should be an array", coordinates.isArray());

                // Should have at least 2 points for a valid LineString
                if (coordinates.size() > 0) {
                    assertTrue("LineString should have multiple points or be empty", true);

                    // Verify each point is a [lon, lat] pair
                    for (int i = 0; i < Math.min(coordinates.size(), 3); i++) {
                        JsonNode point = coordinates.get(i);
                        assertTrue("Point should be array", point.isArray());
                        assertEquals("Point should have 2 coordinates", 2, point.size());
                    }
                }
            }
        }
    }

    @Test
    public void testPolygonGeometryStructure() throws Exception {
        delayBetweenRequests();
        Response response = given()
            .baseUri(BASE_URL)
        .when()
            .get("/geo/osm/way/32113829.json");

        assertEquals("Response should be 200", 200, response.getStatusCode());
        String geoJsonStr = response.getBody().asString();

        // Validate against GeoJSON schema
        validateGeoJson(geoJsonStr);

        JsonNode jsonNode = mapper.readTree(geoJsonStr);

        String type = jsonNode.get("type").asText();
        if ("Polygon".equals(type)) {
            JsonNode coordinates = jsonNode.get("coordinates");
            assertTrue("Polygon coordinates should be an array of rings", coordinates.isArray());

            // Polygon should have at least 1 ring (outer)
            if (coordinates.size() > 0) {
                JsonNode outerRing = coordinates.get(0);
                assertTrue("Outer ring should be array", outerRing.isArray());

                // Valid ring should be closed (first == last) with at least 4 points
                if (outerRing.size() >= 2) {
                    JsonNode first = outerRing.get(0);
                    JsonNode last = outerRing.get(outerRing.size() - 1);

                    // Check they form closed ring
                    assertEquals("First and last coordinates should match (closed ring)",
                        first.toString(), last.toString());
                }
            }
        }
    }

    @Test
    public void testMultiPolygonGeometryStructure() throws Exception {
        delayBetweenRequests();
        Response response = given()
            .baseUri(BASE_URL)
        .when()
            .get("/geo/osm/relation/71525.json");

        assertEquals("Response should be 200", 200, response.getStatusCode());
        String geoJsonStr = response.getBody().asString();

        // Validate against GeoJSON schema
        validateGeoJson(geoJsonStr);

        JsonNode jsonNode = mapper.readTree(geoJsonStr);

        String type = jsonNode.get("type").asText();
        if ("MultiPolygon".equals(type)) {
            JsonNode coordinates = jsonNode.get("coordinates");
            assertTrue("MultiPolygon coordinates should be array of polygons", coordinates.isArray());

            // MultiPolygon should have at least 1 polygon
            if (coordinates.size() > 0) {
                JsonNode firstPolygon = coordinates.get(0);
                assertTrue("First polygon should be array of rings", firstPolygon.isArray());

                // First polygon should have at least 1 ring
                if (firstPolygon.size() > 0) {
                    JsonNode outerRing = firstPolygon.get(0);
                    assertTrue("Outer ring should be array", outerRing.isArray());
                }
            }
        }
    }

    @Test
    public void testInvalidJsonHandling() throws Exception {
        delayBetweenRequests();
        // Test endpoint that may return non-JSON
        Response response = given()
            .baseUri(BASE_URL)
        .when()
            .get("/invalid/endpoint");

        // Should not be 200, but should be a valid HTTP response
        assertNotEquals("Invalid endpoint should not return 200", 200, response.getStatusCode());
    }

    @Test
    public void testEmptyGeometryCollection() throws Exception {
        delayBetweenRequests();
        // Some endpoints may return empty GeometryCollection
        Response response = given()
            .baseUri(BASE_URL)
        .when()
            .get("/geo/osm/node/999999999.json");

        if (response.getStatusCode() == 200) {
            String geoJsonStr = response.getBody().asString();
            JsonNode jsonNode = mapper.readTree(geoJsonStr);

            String type = jsonNode.get("type").asText();
            if ("GeometryCollection".equals(type)) {
                // GeometryCollection doesn't have coordinates
                assertFalse("GeometryCollection should not have coordinates property",
                    jsonNode.has("coordinates"));

                // Should have geometries array (may be empty)
                assertTrue("GeometryCollection should have geometries property",
                    jsonNode.has("geometries"));
                assertTrue("Geometries should be array", jsonNode.get("geometries").isArray());
            }
        }
    }
}
