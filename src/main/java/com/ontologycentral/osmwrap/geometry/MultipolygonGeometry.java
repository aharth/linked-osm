package com.ontologycentral.osmwrap.geometry;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a multipolygon geometry with outer and inner rings.
 * Handles conversion to GeoJSON, WKT, and KML formats.
 * Supports both simple Polygon (1 outer + multiple inners) and MultiPolygon (multiple outers).
 */
public class MultipolygonGeometry {
    private List<Ring> outerRings;
    private List<Ring> innerRings;

    public MultipolygonGeometry() {
        this.outerRings = new ArrayList<>();
        this.innerRings = new ArrayList<>();
    }

    /**
     * Add an outer ring (boundary)
     */
    public void addOuterRing(Ring ring) {
        if (ring != null && ring.isOuter()) {
            outerRings.add(ring);
        }
    }

    /**
     * Add an inner ring (hole)
     */
    public void addInnerRing(Ring ring) {
        if (ring != null && ring.isInner()) {
            innerRings.add(ring);
        }
    }

    /**
     * Check if geometry is valid multipolygon
     * Must have at least 1 outer ring with 4+ coordinates
     */
    public boolean isValid() {
        if (outerRings.isEmpty()) {
            return false;
        }

        // At least one outer ring must be properly closed
        for (Ring outer : outerRings) {
            if (outer.isClosed() && outer.size() >= 4) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if this is a simple polygon (1 outer ring) or multipolygon (multiple outer rings)
     */
    public boolean isMultiPolygon() {
        return outerRings.size() > 1;
    }

    /**
     * Convert to GeoJSON Polygon or MultiPolygon format
     * For Polygon: all rings are included in one polygon
     * For MultiPolygon: each outer ring with its associated inner rings forms a separate polygon
     */
    public String toGeoJSON() {
        if (!isValid()) {
            return "{\"type\":\"GeometryCollection\",\"geometries\":[]}";
        }

        if (isMultiPolygon()) {
            return toGeoJSONMultiPolygon();
        } else {
            return toGeoJSONPolygon();
        }
    }

    /**
     * Convert single outer ring with all inner rings to GeoJSON Polygon
     */
    private String toGeoJSONPolygon() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"Polygon\",\"coordinates\":[");

        // Outer ring
        if (!outerRings.isEmpty()) {
            sb.append(outerRings.get(0).toGeoJSONCoordinates());
        }

        // Inner rings (holes)
        for (Ring inner : innerRings) {
            sb.append(",");
            sb.append(inner.toGeoJSONCoordinates());
        }

        sb.append("]}");
        return sb.toString();
    }

    /**
     * Convert multiple outer rings to GeoJSON MultiPolygon
     * Each polygon is [outer ring, inner rings]
     */
    private String toGeoJSONMultiPolygon() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"MultiPolygon\",\"coordinates\":[");

        for (int i = 0; i < outerRings.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }

            Ring outer = outerRings.get(i);
            sb.append("[");
            sb.append(outer.toGeoJSONCoordinates());

            // Note: In a true MultiPolygon, inner rings should be associated with their parent outer ring
            // For simplicity, we associate all inner rings with the first outer ring
            // TODO: Implement proper ring nesting detection for correct association
            if (i == 0) {
                for (Ring inner : innerRings) {
                    sb.append(",");
                    sb.append(inner.toGeoJSONCoordinates());
                }
            }

            sb.append("]");
        }

        sb.append("]}");
        return sb.toString();
    }

    /**
     * Convert to WKT POLYGON or MULTIPOLYGON format
     */
    public String toWKT() {
        if (!isValid()) {
            return "GEOMETRYCOLLECTION()";
        }

        if (isMultiPolygon()) {
            return toWKTMultiPolygon();
        } else {
            return toWKTPolygon();
        }
    }

    /**
     * Convert single outer ring with all inner rings to WKT POLYGON
     * Format: POLYGON((outer), (inner1), (inner2), ...)
     */
    private String toWKTPolygon() {
        StringBuilder sb = new StringBuilder();
        sb.append("POLYGON(");

        // Outer ring
        if (!outerRings.isEmpty()) {
            sb.append(outerRings.get(0).toWKT());
        }

        // Inner rings (holes)
        for (Ring inner : innerRings) {
            sb.append(",");
            sb.append(inner.toWKT());
        }

        sb.append(")");
        return sb.toString();
    }

    /**
     * Convert multiple outer rings to WKT MULTIPOLYGON
     * Format: MULTIPOLYGON(((outer), (inner)), ((outer)), ...)
     */
    private String toWKTMultiPolygon() {
        StringBuilder sb = new StringBuilder();
        sb.append("MULTIPOLYGON(");

        for (int i = 0; i < outerRings.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }

            Ring outer = outerRings.get(i);
            sb.append("(");
            sb.append(outer.toWKT());

            // Associate inner rings with first polygon for simplicity
            if (i == 0) {
                for (Ring inner : innerRings) {
                    sb.append(",");
                    sb.append(inner.toWKT());
                }
            }

            sb.append(")");
        }

        sb.append(")");
        return sb.toString();
    }

    /**
     * Convert to KML Polygon format with outerBoundaryIs and innerBoundaryIs
     */
    public String toKML() {
        if (!isValid()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        if (isMultiPolygon()) {
            // KML doesn't have MultiPolygon, use MultiGeometry instead
            sb.append("<MultiGeometry>");
            for (Ring outer : outerRings) {
                sb.append(ringToKMLPolygon(outer));
            }
            sb.append("</MultiGeometry>");
        } else {
            sb.append(ringToKMLPolygon(outerRings.get(0)));
        }

        return sb.toString();
    }

    /**
     * Convert a ring to KML Polygon with holes
     */
    private String ringToKMLPolygon(Ring outer) {
        StringBuilder sb = new StringBuilder();
        sb.append("<Polygon>");

        // Outer boundary (required)
        sb.append("<outerBoundaryIs>");
        sb.append("<LinearRing>");
        sb.append("<coordinates>");
        sb.append(outer.toKML());
        sb.append("</coordinates>");
        sb.append("</LinearRing>");
        sb.append("</outerBoundaryIs>");

        // Inner boundaries (holes)
        for (Ring inner : innerRings) {
            sb.append("<innerBoundaryIs>");
            sb.append("<LinearRing>");
            sb.append("<coordinates>");
            sb.append(inner.toKML());
            sb.append("</coordinates>");
            sb.append("</LinearRing>");
            sb.append("</innerBoundaryIs>");
        }

        sb.append("</Polygon>");
        return sb.toString();
    }

    /**
     * Compute the centroid of the first outer ring as the mean of its vertices.
     * Returns [lon, lat], or null if there are no valid outer rings.
     */
    public double[] getCentroid() {
        for (Ring outer : outerRings) {
            List<double[]> coords = outer.getCoordinates();
            if (coords.isEmpty()) continue;
            double sumLon = 0, sumLat = 0;
            for (double[] c : coords) {
                sumLon += c[0];
                sumLat += c[1];
            }
            return new double[]{sumLon / coords.size(), sumLat / coords.size()};
        }
        return null;
    }

    // Getters

    public List<Ring> getOuterRings() {
        return new ArrayList<>(outerRings);
    }

    public List<Ring> getInnerRings() {
        return new ArrayList<>(innerRings);
    }

    public int getOuterRingCount() {
        return outerRings.size();
    }

    public int getInnerRingCount() {
        return innerRings.size();
    }

    @Override
    public String toString() {
        return "MultipolygonGeometry{" +
                "outers=" + outerRings.size() +
                ", inners=" + innerRings.size() +
                ", valid=" + isValid() +
                ", isMultiPolygon=" + isMultiPolygon() +
                '}';
    }
}
