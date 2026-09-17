package com.ontologycentral.osmwrap.geometry;

import java.util.List;

/**
 * A geometry built from an OSM element, serialisable to every output format the wrapper
 * offers. All coordinates are WGS84 {@code [lon, lat]} (CRS84 axis order).
 *
 * <p>Implementations: {@link PointGeometry} (node), {@link LineStringGeometry} (open way,
 * route relation), {@link MultipolygonGeometry} (closed way, multipolygon/boundary relation)
 * and {@link #EMPTY}.
 */
public interface Geometry {

    String GML_NS = "xmlns:gml=\"http://www.opengis.net/gml/3.2\"";
    String GML_CRS84 = "srsName=\"http://www.opengis.net/def/crs/OGC/1.3/CRS84\"";

    /** GeoJSON geometry object. */
    String toGeoJSON();

    /** Well-known text. */
    String toWKT();

    /**
     * GML 3.2 fragment with the {@code gml} namespace declared on its root element, or
     * {@code null} for {@link #EMPTY}.
     */
    String toGML();

    /** KML geometry element (no {@code <Placemark>} wrapper). */
    String toKML();

    default boolean isEmpty() {
        return false;
    }

    /** What every format gets when an element has no usable geometry. */
    Geometry EMPTY = new Geometry() {
        @Override public String toGeoJSON() { return "{\"type\":\"GeometryCollection\",\"geometries\":[]}"; }
        @Override public String toWKT() { return "GEOMETRYCOLLECTION()"; }
        @Override public String toGML() { return null; }
        @Override public String toKML() { return ""; }
        @Override public boolean isEmpty() { return true; }
    };

    /** {@code lon lat lon lat ...} as used by {@code gml:posList} and WKT. */
    static String posList(List<double[]> coords, String pairSeparator, String coordSeparator) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < coords.size(); i++) {
            if (i > 0) sb.append(pairSeparator);
            sb.append(coords.get(i)[0]).append(coordSeparator).append(coords.get(i)[1]);
        }
        return sb.toString();
    }

    /** {@code [[lon,lat],[lon,lat],...]} for GeoJSON. */
    static String jsonCoordinates(List<double[]> coords) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < coords.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("[").append(coords.get(i)[0]).append(",").append(coords.get(i)[1]).append("]");
        }
        return sb.append("]").toString();
    }

    /** {@code lon,lat,0 lon,lat,0 ...} for KML. */
    static String kmlCoordinates(List<double[]> coords) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < coords.size(); i++) {
            if (i > 0) sb.append(" ");
            sb.append(coords.get(i)[0]).append(",").append(coords.get(i)[1]).append(",0");
        }
        return sb.toString();
    }
}
