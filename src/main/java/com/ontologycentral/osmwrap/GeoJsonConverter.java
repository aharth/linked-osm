package com.ontologycentral.osmwrap;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ontologycentral.osmwrap.geometry.MultipolygonHandler;


/**
 * Converts upstream XML responses to GeoJSON FeatureCollections.
 */
public final class GeoJsonConverter {


    private static final Pattern TAG_PATTERN =
        Pattern.compile("<tag\\s+k=['\"]([^'\"]*)['\"]\\s+v=['\"]([^'\"]*)['\"]");

    private GeoJsonConverter() {}

    private static String osmTagKey(String key) {
        return "/tag/" + key;
    }

    private static String transformOsmValue(String key, String value) {
        if ("wikidata".equals(key) && value.matches("[QP]\\d+")) {
            return "https://www.wikidata.org/wiki/" + value;
        }
        if ("wikipedia".equals(key) && value.contains(":")) {
            int colon = value.indexOf(':');
            String lang = value.substring(0, colon);
            String article = value.substring(colon + 1).replace(' ', '_');
            return "https://" + lang + ".wikipedia.org/wiki/" + article;
        }
        return value;
    }

    private static String decodeXmlEntities(String s) {
        if (s == null) return null;
        return s.replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'");
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"':  sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    /**
     * Convert Nominatim XML search results to a GeoJSON FeatureCollection.
     * Parses &lt;place&gt; elements with lat, lon, osm_type, osm_id, display_name attributes.
     */
    public static String nominatimToGeoJson(String xml) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"FeatureCollection\",\"@context\":\"https://geojson.org/geojson-ld/geojson-context.jsonld\",\"features\":[");

        Pattern placePattern = Pattern.compile("<place\\s[^>]*?/>|<place\\s[^>]*?>[^<]*</place>");
        Matcher placeMatcher = placePattern.matcher(xml);

        boolean first = true;
        while (placeMatcher.find()) {
            String place = placeMatcher.group();

            String lat = extractAttr(place, "lat");
            String lon = extractAttr(place, "lon");
            if (lat == null || lon == null) continue;

            if (!first) sb.append(",");
            first = false;

            String osmType = extractAttr(place, "osm_type");
            String osmId = extractAttr(place, "osm_id");
            String displayName = extractAttr(place, "display_name");
            String placeClass = extractAttr(place, "class");
            String placeType = extractAttr(place, "type");

            sb.append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[");
            sb.append(lon).append(",").append(lat);
            sb.append("]},\"properties\":{");
            sb.append("\"osm_type\":\"").append(escapeJson(osmType)).append("\"");
            sb.append(",\"osm_id\":\"").append(escapeJson(osmId)).append("\"");
            sb.append(",\"display_name\":\"").append(escapeJson(displayName)).append("\"");
            if (placeClass != null) {
                sb.append(",\"class\":\"").append(escapeJson(placeClass)).append("\"");
            }
            if (placeType != null) {
                sb.append(",\"type\":\"").append(escapeJson(placeType)).append("\"");
            }
            sb.append("}}");
        }

        sb.append("]}");
        return sb.toString();
    }

    /**
     * Convert Overpass XML response (nodes with tags) to a GeoJSON FeatureCollection.
     * Used by POI and Around servlets.
     */
    public static String overpassNodesToGeoJson(String xml) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"FeatureCollection\",\"@context\":\"https://geojson.org/geojson-ld/geojson-context.jsonld\"");
        Matcher noteMatcher = Pattern.compile("<note>([^<]*)</note>").matcher(xml);
        if (noteMatcher.find()) {
            sb.append(",\"attribution\":\"").append(escapeJson(noteMatcher.group(1).trim())).append("\"");
        }
        sb.append(",\"features\":[");

        // Match both self-closing nodes and nodes with child tags
        Pattern nodePattern = Pattern.compile("<node\\s([^>]*?)/>|<node\\s([^>]*?)>(.*?)</node>", Pattern.DOTALL);
        Matcher nodeMatcher = nodePattern.matcher(xml);

        boolean first = true;
        while (nodeMatcher.find()) {
            String attrs = nodeMatcher.group(1) != null ? nodeMatcher.group(1) : nodeMatcher.group(2);
            String body = nodeMatcher.group(3); // null for self-closing

            String lat = extractAttr(attrs, "lat");
            String lon = extractAttr(attrs, "lon");
            if (lat == null || lon == null) continue;

            String id = extractAttr(attrs, "id");

            if (!first) sb.append(",");
            first = false;

            sb.append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[");
            sb.append(lon).append(",").append(lat);
            sb.append("]},\"properties\":{");
            sb.append("\"osm_type\":\"node\"");
            sb.append(",\"osm_id\":\"").append(escapeJson(id)).append("\"");

            // Extract tags
            if (body != null) {
                Matcher tagMatcher = TAG_PATTERN.matcher(body);
                while (tagMatcher.find()) {
                    String tagKey = decodeXmlEntities(tagMatcher.group(1));
                    String tagValue = decodeXmlEntities(tagMatcher.group(2));
                    sb.append(",\"").append(escapeJson(osmTagKey(tagKey))).append("\":\"");
                    sb.append(escapeJson(transformOsmValue(tagKey, tagValue))).append("\"");
                }
            }

            sb.append("}}");
        }

        sb.append("]}");
        return sb.toString();
    }

    /**
     * {@code <nd>} children carry inline lat/lon with {@code out geom}, but the
     * attribute order differs by container: way geometry keeps the node ref first
     * ({@code <nd ref=".." lat=".." lon=".."/>}) while relation-member geometry
     * omits it ({@code <nd lat=".." lon=".."/>}). Match the whole tag and pull
     * the attributes by name instead of relying on position.
     */
    private static final Pattern ND_PATTERN = Pattern.compile("<nd\\b([^>]*?)/?>");

    /** Inline {@code <nd>} coordinates of a way/member body as [lon, lat] pairs. */
    private static List<double[]> parseNdCoords(String body) {
        List<double[]> coords = new ArrayList<>();
        Matcher ndm = ND_PATTERN.matcher(body);
        while (ndm.find()) {
            String lat = extractAttr(ndm.group(1), "lat");
            String lon = extractAttr(ndm.group(1), "lon");
            if (lat == null || lon == null) continue;
            try {
                coords.add(new double[]{Double.parseDouble(lon), Double.parseDouble(lat)});
            } catch (NumberFormatException e) {
                // skip
            }
        }
        return coords;
    }

    /**
     * Convert Overpass XML response with {@code out geom} to a GeoJSON FeatureCollection.
     * Handles nodes (Point), ways (LineString/Polygon), and relations
     * (Polygon/MultiPolygon for multipolygons, MultiLineString for routes).
     */
    public static String overpassFeaturesToGeoJson(String xml) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"FeatureCollection\",\"@context\":\"https://geojson.org/geojson-ld/geojson-context.jsonld\"");
        Matcher noteMatcher = Pattern.compile("<note>([^<]*)</note>").matcher(xml);
        if (noteMatcher.find()) {
            sb.append(",\"attribution\":\"").append(escapeJson(noteMatcher.group(1).trim())).append("\"");
        }
        sb.append(",\"features\":[");

        boolean first = true;

        // Nodes
        Pattern nodePattern = Pattern.compile("<node\\s([^>]*?)/>|<node\\s([^>]*?)>(.*?)</node>", Pattern.DOTALL);
        Matcher nodeMatcher = nodePattern.matcher(xml);
        while (nodeMatcher.find()) {
            String attrs = nodeMatcher.group(1) != null ? nodeMatcher.group(1) : nodeMatcher.group(2);
            String body = nodeMatcher.group(3);

            String lat = extractAttr(attrs, "lat");
            String lon = extractAttr(attrs, "lon");
            if (lat == null || lon == null) continue;

            String id = extractAttr(attrs, "id");

            if (!first) sb.append(",");
            first = false;

            sb.append("{\"type\":\"Feature\"");
            sb.append(",\"id\":\"/overpass/node/").append(escapeJson(id)).append("#id\"");
            sb.append(",\"geometry\":{\"type\":\"Point\",\"coordinates\":[");
            sb.append(lon).append(",").append(lat);
            sb.append("]},\"properties\":{");
            sb.append("\"osm_type\":\"node\"");
            sb.append(",\"osm_id\":\"").append(escapeJson(id)).append("\"");
            if (body != null) {
                Matcher tagMatcher = TAG_PATTERN.matcher(body);
                while (tagMatcher.find()) {
                    String tagKey = decodeXmlEntities(tagMatcher.group(1));
                    String tagValue = decodeXmlEntities(tagMatcher.group(2));
                    sb.append(",\"").append(escapeJson(osmTagKey(tagKey))).append("\":\"");
                    sb.append(escapeJson(transformOsmValue(tagKey, tagValue))).append("\"");
                }
            }
            sb.append("}}");
        }

        // Ways (inline nd lat/lon from out geom)
        Pattern wayPattern = Pattern.compile("<way\\s([^>]*?)>(.*?)</way>", Pattern.DOTALL);
        Matcher wayMatcher = wayPattern.matcher(xml);
        while (wayMatcher.find()) {
            String wayAttrs = wayMatcher.group(1);
            String wayBody = wayMatcher.group(2);
            String wayId = extractAttr(wayAttrs, "id");

            List<double[]> coords = parseNdCoords(wayBody);

            if (coords.size() < 2) continue;

            if (!first) sb.append(",");
            first = false;

            boolean closed = coords.size() >= 4
                    && Math.abs(coords.get(0)[0] - coords.get(coords.size() - 1)[0]) < 1e-9
                    && Math.abs(coords.get(0)[1] - coords.get(coords.size() - 1)[1]) < 1e-9;
            String geomType = closed ? "Polygon" : "LineString";

            sb.append("{\"type\":\"Feature\"");
            sb.append(",\"id\":\"/overpass/way/").append(escapeJson(wayId)).append("#id\"");
            sb.append(",\"geometry\":{\"type\":\"").append(geomType).append("\",\"coordinates\":");
            if (closed) sb.append("[");
            sb.append("[");
            for (int i = 0; i < coords.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("[").append(coords.get(i)[0]).append(",").append(coords.get(i)[1]).append("]");
            }
            sb.append("]");
            if (closed) sb.append("]");
            sb.append("},\"properties\":{");
            sb.append("\"osm_type\":\"way\"");
            sb.append(",\"osm_id\":\"").append(escapeJson(wayId)).append("\"");
            Matcher tagMatcher = TAG_PATTERN.matcher(wayBody);
            while (tagMatcher.find()) {
                String tagKey = decodeXmlEntities(tagMatcher.group(1));
                String tagValue = decodeXmlEntities(tagMatcher.group(2));
                sb.append(",\"").append(escapeJson(osmTagKey(tagKey))).append("\":\"");
                sb.append(escapeJson(transformOsmValue(tagKey, tagValue))).append("\"");
            }
            sb.append("}}");
        }

        // Relations
        Pattern memberPattern = Pattern.compile(
                "<member type=['\"]way['\"] ref=['\"][^'\"]*['\"] role=['\"]([^'\"]*)['\"]>(.*?)</member>",
                Pattern.DOTALL);
        Pattern relPattern = Pattern.compile("<relation\\s([^>]*?)>(.*?)</relation>", Pattern.DOTALL);
        Matcher relMatcher = relPattern.matcher(xml);
        while (relMatcher.find()) {
            String relAttrs = relMatcher.group(1);
            String relBody = relMatcher.group(2);
            String relId = extractAttr(relAttrs, "id");

            List<List<double[]>> outerSegments = new ArrayList<>();
            List<List<double[]>> innerSegments = new ArrayList<>();
            List<List<double[]>> otherSegments = new ArrayList<>();

            Matcher mm = memberPattern.matcher(relBody);
            while (mm.find()) {
                String role = mm.group(1);
                String memberBody = mm.group(2);
                List<double[]> seg = parseNdCoords(memberBody);
                if (seg.size() < 2) continue;
                if ("outer".equals(role)) outerSegments.add(seg);
                else if ("inner".equals(role)) innerSegments.add(seg);
                else otherSegments.add(seg);
            }

            String geomJson;
            if (!outerSegments.isEmpty()) {
                geomJson = MultipolygonHandler.toGeoJSON(
                        MultipolygonHandler.buildFromSegments(outerSegments, innerSegments));
            } else {
                // Route or generic: MultiLineString from all segments
                List<List<double[]>> all = new ArrayList<>(otherSegments);
                all.addAll(innerSegments);
                if (all.isEmpty()) {
                    geomJson = "null";
                } else {
                    StringBuilder msb = new StringBuilder("{\"type\":\"MultiLineString\",\"coordinates\":[");
                    for (int si = 0; si < all.size(); si++) {
                        if (si > 0) msb.append(",");
                        msb.append("[");
                        List<double[]> seg = all.get(si);
                        for (int ci = 0; ci < seg.size(); ci++) {
                            if (ci > 0) msb.append(",");
                            msb.append("[").append(seg.get(ci)[0]).append(",").append(seg.get(ci)[1]).append("]");
                        }
                        msb.append("]");
                    }
                    msb.append("]}");
                    geomJson = msb.toString();
                }
            }

            if (!first) sb.append(",");
            first = false;

            sb.append("{\"type\":\"Feature\"");
            sb.append(",\"id\":\"/overpass/relation/").append(escapeJson(relId)).append("#id\"");
            sb.append(",\"geometry\":").append(geomJson);
            sb.append(",\"properties\":{");
            sb.append("\"osm_type\":\"relation\"");
            sb.append(",\"osm_id\":\"").append(escapeJson(relId)).append("\"");
            Matcher tagMatcher = TAG_PATTERN.matcher(relBody);
            while (tagMatcher.find()) {
                String tagKey = decodeXmlEntities(tagMatcher.group(1));
                String tagValue = decodeXmlEntities(tagMatcher.group(2));
                sb.append(",\"").append(escapeJson(osmTagKey(tagKey))).append("\":\"");
                sb.append(escapeJson(transformOsmValue(tagKey, tagValue))).append("\"");
            }
            sb.append("}}");
        }

        sb.append("]}");
        return sb.toString();
    }

    /**
     * A single OSM element as a GeoJSON Feature with its tags as properties.
     *
     * @param tags         the element's tags as {@code {k, v}} pairs
     * @param elementType  "node", "way", or "relation"
     * @param id           the OSM element ID (used for the GeoJSON "id" field)
     * @param geometryJson GeoJSON geometry object string, or null
     * @param sourcePrefix URI prefix of the wrapper endpoint, e.g. {@code /osm}
     */
    public static String osmFeatureToGeoJson(List<String[]> tags, String elementType, String id,
            String geometryJson, String sourcePrefix) {
        StringBuilder props = new StringBuilder();
        props.append("\"osm_type\":\"").append(escapeJson(elementType)).append("\"");
        props.append(",\"osm_id\":\"").append(escapeJson(id)).append("\"");

        for (String[] kv : tags) {
            props.append(",\"").append(escapeJson(osmTagKey(kv[0]))).append("\":\"");
            props.append(escapeJson(transformOsmValue(kv[0], kv[1]))).append("\"");
        }

        return "{\"type\":\"Feature\""
                + ",\"@context\":\"https://geojson.org/geojson-ld/geojson-context.jsonld\""
                + ",\"id\":\"" + sourcePrefix + "/" + elementType + "/" + escapeJson(id) + "#id\""
                + ",\"properties\":{" + props + "}"
                + ",\"geometry\":" + (geometryJson != null ? geometryJson : "null")
                + "}";
    }

    private static String extractAttr(String element, String attrName) {
        Pattern p = Pattern.compile(attrName + "=['\"]([^'\"]*)['\"]");
        Matcher m = p.matcher(element);
        if (m.find()) {
            return decodeXmlEntities(m.group(1));
        }
        return null;
    }
}
