package com.ontologycentral.osmwrap.geometry;

/** A single WGS84 point (an OSM node). */
public final class PointGeometry implements Geometry {
    private final double lon;
    private final double lat;

    public PointGeometry(double lon, double lat) {
        this.lon = lon;
        this.lat = lat;
    }

    public double lon() {
        return lon;
    }

    public double lat() {
        return lat;
    }

    @Override
    public String toGeoJSON() {
        return "{\"type\":\"Point\",\"coordinates\":[" + lon + "," + lat + "]}";
    }

    @Override
    public String toWKT() {
        return "POINT(" + lon + " " + lat + ")";
    }

    @Override
    public String toGML() {
        return "<gml:Point " + GML_NS + " " + GML_CRS84 + "><gml:pos>" + lon + " " + lat + "</gml:pos></gml:Point>";
    }

    @Override
    public String toKML() {
        return "<Point><coordinates>" + lon + "," + lat + ",0</coordinates></Point>";
    }
}
