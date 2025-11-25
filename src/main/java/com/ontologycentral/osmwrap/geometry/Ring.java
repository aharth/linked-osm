package com.ontologycentral.osmwrap.geometry;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a closed ring of geographic coordinates.
 * A ring is a sequence of coordinate pairs where the first and last coordinates are identical.
 * Used for constructing polygons (outer rings) and holes (inner rings) in multipolygon relations.
 */
public class Ring {
    private List<double[]> coordinates;
    private String role;  // "outer" or "inner"
    private boolean isClosed;

    /**
     * Create a ring from a list of coordinates and role
     * @param coordinates List of [lon, lat] coordinate pairs
     * @param role "outer" for boundary ring, "inner" for hole ring
     */
    public Ring(List<double[]> coordinates, String role) {
        this.coordinates = new ArrayList<>(coordinates);
        this.role = role != null ? role : "outer";
        this.isClosed = false;
        validate();
    }

    /**
     * Validate and close the ring if necessary
     * Ensures the ring has at least 3 unique points (4 with closure)
     * and that first coordinate equals last coordinate
     */
    public void validate() {
        if (coordinates.isEmpty()) {
            this.isClosed = false;
            return;
        }

        // Ensure ring is closed (first == last)
        double[] first = coordinates.get(0);
        double[] last = coordinates.get(coordinates.size() - 1);

        if (!coordinatesEqual(first, last)) {
            // Close the ring
            coordinates.add(new double[]{first[0], first[1]});
        }

        this.isClosed = coordinates.size() >= 4;  // At least 3 unique points + closure
    }

    /**
     * Check if two coordinates are equal
     */
    private boolean coordinatesEqual(double[] c1, double[] c2) {
        return Math.abs(c1[0] - c2[0]) < 1e-9 && Math.abs(c1[1] - c2[1]) < 1e-9;
    }

    /**
     * Compute the signed area of the ring using the shoelace formula
     * Positive = counter-clockwise (exterior ring)
     * Negative = clockwise (interior ring/hole)
     */
    public double computeSignedArea() {
        if (coordinates.size() < 4) {
            return 0.0;
        }

        double area = 0.0;
        for (int i = 0; i < coordinates.size() - 1; i++) {
            double[] c1 = coordinates.get(i);
            double[] c2 = coordinates.get(i + 1);
            area += (c2[0] - c1[0]) * (c2[1] + c1[1]);
        }
        return area / 2.0;
    }

    /**
     * Check if ring is oriented counter-clockwise (exterior ring)
     * GeoJSON RFC 7946 specifies CCW orientation for exterior rings
     */
    public boolean isCounterClockwise() {
        return computeSignedArea() > 0;
    }

    /**
     * Reverse the ring orientation (useful for fixing CCW/CW mismatches)
     */
    public void reverseOrientation() {
        List<double[]> reversed = new ArrayList<>();
        for (int i = coordinates.size() - 1; i >= 0; i--) {
            double[] c = coordinates.get(i);
            reversed.add(new double[]{c[0], c[1]});
        }
        this.coordinates = reversed;
    }

    /**
     * Convert ring to GeoJSON coordinate array
     * @return [[lon, lat], [lon, lat], ..., [lon, lat]]
     */
    public String toGeoJSONCoordinates() {
        if (!isClosed || coordinates.isEmpty()) {
            return "[]";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < coordinates.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            double[] coord = coordinates.get(i);
            sb.append("[").append(coord[0]).append(",").append(coord[1]).append("]");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Convert ring to WKT format (for POLYGON(...) syntax)
     * @return (lon lat, lon lat, ..., lon lat)
     */
    public String toWKT() {
        if (!isClosed || coordinates.isEmpty()) {
            return "()";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("(");
        for (int i = 0; i < coordinates.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            double[] coord = coordinates.get(i);
            sb.append(coord[0]).append(" ").append(coord[1]);
        }
        sb.append(")");
        return sb.toString();
    }

    /**
     * Convert ring to KML format
     * @return <coordinates>lon,lat,0 lon,lat,0 ...</coordinates>
     */
    public String toKML() {
        if (!isClosed || coordinates.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < coordinates.size(); i++) {
            if (i > 0) {
                sb.append(" ");
            }
            double[] coord = coordinates.get(i);
            sb.append(coord[0]).append(",").append(coord[1]).append(",0");
        }
        return sb.toString();
    }

    // Getters and setters

    public List<double[]> getCoordinates() {
        return new ArrayList<>(coordinates);
    }

    public String getRole() {
        return role;
    }

    public boolean isClosed() {
        return isClosed;
    }

    public int size() {
        return coordinates.size();
    }

    public boolean isOuter() {
        return "outer".equals(role);
    }

    public boolean isInner() {
        return "inner".equals(role);
    }

    @Override
    public String toString() {
        return "Ring{" +
                "role='" + role + '\'' +
                ", isClosed=" + isClosed +
                ", points=" + coordinates.size() +
                ", area=" + String.format("%.2f", computeSignedArea()) +
                '}';
    }
}
