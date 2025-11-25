package com.ontologycentral.osmwrap.geometry;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;

public class MultipolygonGeometryTest {

    private Ring createSimpleRing() {
        List<double[]> coordinates = new ArrayList<>();
        coordinates.add(new double[]{0, 0});
        coordinates.add(new double[]{1, 0});
        coordinates.add(new double[]{1, 1});
        coordinates.add(new double[]{0, 1});
        return new Ring(coordinates, "outer");
    }

    private Ring createHoleRing() {
        List<double[]> coordinates = new ArrayList<>();
        coordinates.add(new double[]{0.25, 0.25});
        coordinates.add(new double[]{0.75, 0.25});
        coordinates.add(new double[]{0.75, 0.75});
        coordinates.add(new double[]{0.25, 0.75});
        return new Ring(coordinates, "inner");
    }

    @Test
    public void testSimplePolygonWithSingleOuter() {
        MultipolygonGeometry geom = new MultipolygonGeometry();
        Ring outer = createSimpleRing();
        geom.addOuterRing(outer);

        assertTrue("Geometry with single outer should be valid", geom.isValid());
        assertFalse("Geometry with single outer should not be multipolygon", geom.isMultiPolygon());
    }

    @Test
    public void testPolygonWithHole() {
        MultipolygonGeometry geom = new MultipolygonGeometry();
        Ring outer = createSimpleRing();
        Ring hole = createHoleRing();

        geom.addOuterRing(outer);
        geom.addInnerRing(hole);

        assertTrue("Geometry with outer and hole should be valid", geom.isValid());
        assertFalse("Polygon with hole should not be multipolygon", geom.isMultiPolygon());
    }

    @Test
    public void testMultiPolygonWithMultipleOuters() {
        MultipolygonGeometry geom = new MultipolygonGeometry();
        Ring outer1 = createSimpleRing();

        List<double[]> coords2 = new ArrayList<>();
        coords2.add(new double[]{2, 2});
        coords2.add(new double[]{3, 2});
        coords2.add(new double[]{3, 3});
        coords2.add(new double[]{2, 3});
        Ring outer2 = new Ring(coords2, "outer");

        geom.addOuterRing(outer1);
        geom.addOuterRing(outer2);

        assertTrue("Geometry with multiple outers should be valid", geom.isValid());
        assertTrue("Geometry with multiple outers should be multipolygon", geom.isMultiPolygon());
    }

    @Test
    public void testInvalidGeometryNoOuters() {
        MultipolygonGeometry geom = new MultipolygonGeometry();
        assertFalse("Geometry with no outers should be invalid", geom.isValid());
    }

    @Test
    public void testGeoJSONPolygonOutput() {
        MultipolygonGeometry geom = new MultipolygonGeometry();
        geom.addOuterRing(createSimpleRing());

        String geoJson = geom.toGeoJSON();
        assertNotNull("GeoJSON should not be null", geoJson);
        assertTrue("GeoJSON should contain Polygon type", geoJson.contains("\"type\"") && geoJson.contains("Polygon"));
        assertTrue("GeoJSON should contain coordinates", geoJson.contains("\"coordinates\""));
    }

    @Test
    public void testGeoJSONMultiPolygonOutput() {
        MultipolygonGeometry geom = new MultipolygonGeometry();
        geom.addOuterRing(createSimpleRing());

        List<double[]> coords2 = new ArrayList<>();
        coords2.add(new double[]{2, 2});
        coords2.add(new double[]{3, 2});
        coords2.add(new double[]{3, 3});
        coords2.add(new double[]{2, 3});
        geom.addOuterRing(new Ring(coords2, "outer"));

        String geoJson = geom.toGeoJSON();
        assertNotNull("GeoJSON should not be null", geoJson);
        assertTrue("GeoJSON should contain MultiPolygon type", geoJson.contains("MultiPolygon"));
    }

    @Test
    public void testWKTPolygonOutput() {
        MultipolygonGeometry geom = new MultipolygonGeometry();
        geom.addOuterRing(createSimpleRing());

        String wkt = geom.toWKT();
        assertNotNull("WKT should not be null", wkt);
        assertTrue("WKT should contain POLYGON", wkt.contains("POLYGON"));
    }

    @Test
    public void testWKTMultiPolygonOutput() {
        MultipolygonGeometry geom = new MultipolygonGeometry();
        geom.addOuterRing(createSimpleRing());

        List<double[]> coords2 = new ArrayList<>();
        coords2.add(new double[]{2, 2});
        coords2.add(new double[]{3, 2});
        coords2.add(new double[]{3, 3});
        coords2.add(new double[]{2, 3});
        geom.addOuterRing(new Ring(coords2, "outer"));

        String wkt = geom.toWKT();
        assertNotNull("WKT should not be null", wkt);
        assertTrue("WKT should contain MULTIPOLYGON", wkt.contains("MULTIPOLYGON"));
    }

    @Test
    public void testKMLOutput() {
        MultipolygonGeometry geom = new MultipolygonGeometry();
        geom.addOuterRing(createSimpleRing());

        String kml = geom.toKML();
        assertNotNull("KML should not be null", kml);
        assertTrue("KML should contain Polygon element", kml.contains("<Polygon>"));
    }

    @Test
    public void testPolygonWithMultipleHoles() {
        MultipolygonGeometry geom = new MultipolygonGeometry();
        Ring outer = createSimpleRing();
        Ring hole1 = createHoleRing();

        List<double[]> hole2Coords = new ArrayList<>();
        hole2Coords.add(new double[]{0.1, 0.1});
        hole2Coords.add(new double[]{0.2, 0.1});
        hole2Coords.add(new double[]{0.2, 0.2});
        hole2Coords.add(new double[]{0.1, 0.2});
        Ring hole2 = new Ring(hole2Coords, "inner");

        geom.addOuterRing(outer);
        geom.addInnerRing(hole1);
        geom.addInnerRing(hole2);

        assertTrue("Polygon with multiple holes should be valid", geom.isValid());
        assertFalse("Polygon should not be multipolygon", geom.isMultiPolygon());
    }
}
