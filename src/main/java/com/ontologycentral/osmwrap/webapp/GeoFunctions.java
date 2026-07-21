package com.ontologycentral.osmwrap.webapp;

import org.apache.jena.geosparql.implementation.GeometryWrapper;
import org.apache.jena.geosparql.implementation.datatype.WKTDatatype;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase1;
import org.apache.jena.sparql.function.FunctionBase2;
import org.apache.jena.sparql.function.FunctionRegistry;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;

/**
 * GeoSPARQL 1.1 (OGC 22-047r1) functions not provided by jena-geosparql 6.0.0:
 * geof:metricArea, geof:metricLength, geof:metricDistance, geof:centroid.
 *
 * Plus {@code fn:dimension}, the topological dimension (0 = point, 1 = curve,
 * 2 = surface) backing the ATKIS SymbologyCatalog (ADV SK50) DT50 styling rules.
 * The catalog's binary spatial predicates ({@code mdl:geo_crosses},
 * {@code mdl:geo_touches}, {@code mdl:geo_intersects}, {@code mdl:geo_equals},
 * {@code mdl:geo_contains}) map directly to jena-geosparql's standard, JTS-backed
 * {@code geof:sfCrosses} / {@code sfTouches} / {@code sfIntersects} /
 * {@code sfEquals} / {@code sfContains} (registered by GeoSPARQLConfig in Listener),
 * so no custom binary functions are needed. The catalog's {@code geo_isArea} /
 * {@code geo_isCurve} / {@code geo_isPoint} are expressed as
 * {@code fn:dimension(?g) = 2 / 1 / 0}. These relations are topological (DE-9IM),
 * so evaluating directly on EPSG:4326 lon/lat coordinates is correct.
 * Declare {@code PREFIX fn: <}{@value #FN}{@code >}.
 *
 * Inputs are parsed via jena-geosparql's {@link GeometryWrapper} (handles both
 * geo:wktLiteral and geo:gmlLiteral). Coordinates are assumed to be in EPSG:4326
 * lon/lat (CRS84) — every RDF-emitting servlet in this project reprojects to
 * EPSG:4326 before publishing. Geometries with a different srsURI bypass the
 * reprojection and the result will be wrong; out of scope for this iteration.
 *
 * Math: spherical Earth, R = 6371008.8 m (WGS84 mean radius). Errors vs WGS84
 * geodesic are &lt;0.5% for polygons up to several hundred km — adequate for
 * NUTS regions and INSPIRE features.
 */
public final class GeoFunctions {

    private static final String GEOF = "http://www.opengis.net/def/function/geosparql/";
    /** Project function namespace (mirrors the /vocab/{name}# convention). */
    public static final String FN = "https://osmwrap.ontologycentral.com/vocab/fn#";
    private static final double R = 6371008.8;

    private GeoFunctions() {}

    public static void register() {
        FunctionRegistry r = FunctionRegistry.get();
        r.put(GEOF + "metricArea",     MetricArea.class);
        r.put(GEOF + "metricLength",   MetricLength.class);
        r.put(GEOF + "metricDistance", MetricDistance.class);
        r.put(GEOF + "centroid",       Centroid.class);
        r.put(FN + "dimension",        Dimension.class);
    }

    /**
     * fn:dimension(g) — topological dimension of a geometry as xsd:integer
     * (0 = point, 1 = curve, 2 = surface; the maximum over a collection).
     * Backs the ATKIS rules: isPoint = {@code = 0}, isCurve = {@code = 1},
     * isArea = {@code = 2}. Mirrors JTS {@code Geometry.getDimension()}.
     */
    public static class Dimension extends FunctionBase1 {
        @Override public NodeValue exec(NodeValue v) {
            return NodeValue.makeInteger(geom(v).getDimension());
        }
    }

    private static Geometry geom(NodeValue v) {
        return GeometryWrapper.extract(v).getXYGeometry();
    }

    public static class MetricArea extends FunctionBase1 {
        @Override public NodeValue exec(NodeValue v) {
            Geometry g = GeometryWrapper.extract(v).getXYGeometry();
            return NodeValue.makeDouble(area(g));
        }
    }

    public static class MetricLength extends FunctionBase1 {
        @Override public NodeValue exec(NodeValue v) {
            Geometry g = GeometryWrapper.extract(v).getXYGeometry();
            return NodeValue.makeDouble(length(g));
        }
    }

    public static class MetricDistance extends FunctionBase2 {
        @Override public NodeValue exec(NodeValue a, NodeValue b) {
            Geometry ga = GeometryWrapper.extract(a).getXYGeometry();
            Geometry gb = GeometryWrapper.extract(b).getXYGeometry();
            Coordinate ca = (ga instanceof Point) ? ga.getCoordinate() : ga.getCentroid().getCoordinate();
            Coordinate cb = (gb instanceof Point) ? gb.getCoordinate() : gb.getCentroid().getCoordinate();
            return NodeValue.makeDouble(haversine(ca.y, ca.x, cb.y, cb.x));
        }
    }

    public static class Centroid extends FunctionBase1 {
        @Override public NodeValue exec(NodeValue v) {
            Geometry g = GeometryWrapper.extract(v).getXYGeometry();
            Point c = g.getCentroid();
            String wkt = "POINT(" + c.getX() + " " + c.getY() + ")";
            return NodeValue.makeNode(NodeFactory.createLiteralDT(wkt, WKTDatatype.INSTANCE));
        }
    }

    /** Polygon / MultiPolygon area on a sphere via Green's-theorem on lon/lat. */
    private static double area(Geometry g) {
        if (g instanceof Polygon) return polygonArea((Polygon) g);
        if (g instanceof MultiPolygon) {
            double sum = 0;
            for (int i = 0; i < g.getNumGeometries(); i++)
                sum += polygonArea((Polygon) g.getGeometryN(i));
            return sum;
        }
        return 0.0;
    }

    private static double polygonArea(Polygon p) {
        double a = ringArea(p.getExteriorRing().getCoordinates());
        for (int i = 0; i < p.getNumInteriorRing(); i++)
            a -= ringArea(p.getInteriorRingN(i).getCoordinates());
        return a;
    }

    /** Spherical-excess approximation: A = R² · |Σ (λ_{i+1} − λ_i)(sin φ_i + sin φ_{i+1})| / 2. */
    private static double ringArea(Coordinate[] ring) {
        int n = ring.length;
        if (n < 4) return 0;
        double sum = 0;
        for (int i = 0; i < n - 1; i++) {
            double lon1 = Math.toRadians(ring[i].x);
            double lat1 = Math.toRadians(ring[i].y);
            double lon2 = Math.toRadians(ring[i + 1].x);
            double lat2 = Math.toRadians(ring[i + 1].y);
            sum += (lon2 - lon1) * (Math.sin(lat1) + Math.sin(lat2));
        }
        return Math.abs(sum) * R * R / 2.0;
    }

    /**
     * LineString / MultiLineString geodesic length, or polygon perimeter
     * (sum of all rings, matching GeoSPARQL 1.1 metricLength semantics).
     */
    private static double length(Geometry g) {
        if (g instanceof Point) return 0;
        if (g instanceof Polygon) return polygonPerimeter((Polygon) g);
        if (g instanceof org.locationtech.jts.geom.LineString)
            return coordsLength(g.getCoordinates());
        // MultiLineString, MultiPolygon, GeometryCollection
        double sum = 0;
        for (int i = 0; i < g.getNumGeometries(); i++)
            sum += length(g.getGeometryN(i));
        return sum;
    }

    private static double polygonPerimeter(Polygon p) {
        double per = coordsLength(p.getExteriorRing().getCoordinates());
        for (int i = 0; i < p.getNumInteriorRing(); i++)
            per += coordsLength(p.getInteriorRingN(i).getCoordinates());
        return per;
    }

    private static double coordsLength(Coordinate[] c) {
        double sum = 0;
        for (int i = 0; i < c.length - 1; i++)
            sum += haversine(c[i].y, c[i].x, c[i + 1].y, c[i + 1].x);
        return sum;
    }

    /** Great-circle distance between two lon/lat points (degrees) in metres. */
    private static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dphi = Math.toRadians(lat2 - lat1);
        double dlam = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dphi / 2) * Math.sin(dphi / 2)
                 + Math.cos(phi1) * Math.cos(phi2) * Math.sin(dlam / 2) * Math.sin(dlam / 2);
        return 2 * R * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
