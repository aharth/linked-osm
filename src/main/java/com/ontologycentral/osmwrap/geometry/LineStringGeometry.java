package com.ontologycentral.osmwrap.geometry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** An open sequence of WGS84 points (an unclosed way, or a route relation's members flattened). */
public final class LineStringGeometry implements Geometry {
    private final List<double[]> coords;

    /** @param coords at least two {@code [lon, lat]} pairs */
    public LineStringGeometry(List<double[]> coords) {
        if (coords.size() < 2) {
            throw new IllegalArgumentException("a LineString needs at least two coordinates");
        }
        this.coords = Collections.unmodifiableList(new ArrayList<>(coords));
    }

    public List<double[]> coordinates() {
        return coords;
    }

    @Override
    public String toGeoJSON() {
        return "{\"type\":\"LineString\",\"coordinates\":" + Geometry.jsonCoordinates(coords) + "}";
    }

    @Override
    public String toWKT() {
        return "LINESTRING(" + Geometry.posList(coords, ",", " ") + ")";
    }

    @Override
    public String toGML() {
        return "<gml:LineString " + GML_NS + " " + GML_CRS84 + "><gml:posList>"
                + Geometry.posList(coords, " ", " ") + "</gml:posList></gml:LineString>";
    }

    @Override
    public String toKML() {
        return "<LineString><coordinates>" + Geometry.kmlCoordinates(coords) + "</coordinates></LineString>";
    }
}
