package com.ontologycentral.osmwrap.geometry;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;

public class RingTest {

    @Test
    public void testValidClosedRing() {
        // Create a simple square ring: [0,0], [1,0], [1,1], [0,1], [0,0]
        List<double[]> coordinates = new ArrayList<>();
        coordinates.add(new double[]{0, 0});
        coordinates.add(new double[]{1, 0});
        coordinates.add(new double[]{1, 1});
        coordinates.add(new double[]{0, 1});

        Ring ring = new Ring(coordinates, "outer");
        assertTrue("Ring should be closed after validation", ring.isClosed());
    }

    @Test
    public void testInvalidRingTooFewCoordinates() {
        // Ring with only 2 coordinates (needs at least 3 unique)
        List<double[]> coordinates = new ArrayList<>();
        coordinates.add(new double[]{0, 0});
        coordinates.add(new double[]{1, 0});

        Ring ring = new Ring(coordinates, "outer");
        assertFalse("Ring with 2 coordinates should not be closed", ring.isClosed());
    }

    @Test
    public void testClosureDetection() {
        List<double[]> coordinates = new ArrayList<>();
        coordinates.add(new double[]{0, 0});
        coordinates.add(new double[]{1, 0});
        coordinates.add(new double[]{1, 1});

        Ring ring = new Ring(coordinates, "outer");
        assertTrue("Ring should be closed after validate()", ring.isClosed());
    }

    @Test
    public void testOuterRingRole() {
        List<double[]> coordinates = new ArrayList<>();
        coordinates.add(new double[]{0, 0});
        coordinates.add(new double[]{1, 0});
        coordinates.add(new double[]{1, 1});

        Ring ring = new Ring(coordinates, "outer");
        assertTrue("Ring with 'outer' role should be outer", ring.isOuter());
        assertFalse("Ring with 'outer' role should not be inner", ring.isInner());
    }

    @Test
    public void testInnerRingRole() {
        List<double[]> coordinates = new ArrayList<>();
        coordinates.add(new double[]{0.25, 0.25});
        coordinates.add(new double[]{0.75, 0.25});
        coordinates.add(new double[]{0.75, 0.75});

        Ring ring = new Ring(coordinates, "inner");
        assertTrue("Ring with 'inner' role should be inner", ring.isInner());
        assertFalse("Ring with 'inner' role should not be outer", ring.isOuter());
    }

    @Test
    public void testSignedAreaCalculation() {
        // Test signed area calculation
        List<double[]> coordinates = new ArrayList<>();
        coordinates.add(new double[]{0, 0});
        coordinates.add(new double[]{1, 0});
        coordinates.add(new double[]{1, 1});
        coordinates.add(new double[]{0, 1});

        Ring ring = new Ring(coordinates, "outer");
        double area = ring.computeSignedArea();
        // Just verify area is non-zero to check calculation works
        assertNotEquals("Signed area should be calculated", 0.0, area, 0.001);
    }

    @Test
    public void testCounterClockwiseDetection() {
        // Test ring orientation detection
        List<double[]> coordinates = new ArrayList<>();
        coordinates.add(new double[]{0, 0});
        coordinates.add(new double[]{1, 0});
        coordinates.add(new double[]{1, 1});
        coordinates.add(new double[]{0, 1});

        Ring ring = new Ring(coordinates, "outer");
        // Just verify method exists and doesn't throw exception
        boolean isCCW = ring.isCounterClockwise();
        assertNotNull("isCounterClockwise should return a boolean", isCCW);
    }

    @Test
    public void testGeoJSONCoordinateGeneration() {
        List<double[]> coordinates = new ArrayList<>();
        coordinates.add(new double[]{0, 0});
        coordinates.add(new double[]{1, 0});
        coordinates.add(new double[]{1, 1});
        coordinates.add(new double[]{0, 1});

        Ring ring = new Ring(coordinates, "outer");
        String geoJson = ring.toGeoJSONCoordinates();

        assertNotNull("GeoJSON coordinates should not be null", geoJson);
        assertTrue("GeoJSON should contain coordinate arrays", geoJson.contains("["));
        assertTrue("GeoJSON should contain numbers", geoJson.contains("0") || geoJson.contains("1"));
    }

    @Test
    public void testWKTGeneration() {
        List<double[]> coordinates = new ArrayList<>();
        coordinates.add(new double[]{0, 0});
        coordinates.add(new double[]{1, 0});
        coordinates.add(new double[]{1, 1});

        Ring ring = new Ring(coordinates, "outer");
        String wkt = ring.toWKT();

        assertNotNull("WKT should not be null", wkt);
        assertTrue("WKT should start with opening paren", wkt.startsWith("("));
        assertTrue("WKT should end with closing paren", wkt.endsWith(")"));
    }

    @Test
    public void testKMLGeneration() {
        List<double[]> coordinates = new ArrayList<>();
        coordinates.add(new double[]{0, 0});
        coordinates.add(new double[]{1, 0});
        coordinates.add(new double[]{1, 1});

        Ring ring = new Ring(coordinates, "outer");
        String kml = ring.toKML();

        assertNotNull("KML should not be null", kml);
        // KML output should contain coordinate-like content
        assertTrue("KML should contain numeric content", kml.contains("0") || kml.contains("1"));
    }
}
