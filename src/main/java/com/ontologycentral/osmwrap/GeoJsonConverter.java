package com.ontologycentral.osmwrap;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts upstream XML responses to GeoJSON FeatureCollections.
 */
public final class GeoJsonConverter {

	private GeoJsonConverter() {}

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
		sb.append("{\"type\":\"FeatureCollection\",\"features\":[");

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
		sb.append("{\"type\":\"FeatureCollection\",\"features\":[");

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
				Pattern tagPattern = Pattern.compile("<tag\\s+k=['\"]([^'\"]*)['\"]\\s+v=['\"]([^'\"]*)['\"]");
				Matcher tagMatcher = tagPattern.matcher(body);
				while (tagMatcher.find()) {
					sb.append(",\"").append(escapeJson(decodeXmlEntities(tagMatcher.group(1)))).append("\":\"");
					sb.append(escapeJson(decodeXmlEntities(tagMatcher.group(2)))).append("\"");
				}
			}

			sb.append("}}");
		}

		sb.append("]}");
		return sb.toString();
	}

	/**
	 * Convert OSM API map response to a GeoJSON FeatureCollection.
	 * Extracts nodes with tags as Points, and ways as LineStrings
	 * (node coordinates are in the same XML response).
	 */
	public static String osmMapToGeoJson(String xml) {
		StringBuilder sb = new StringBuilder();
		sb.append("{\"type\":\"FeatureCollection\",\"features\":[");

		// First pass: build node coordinate lookup (all nodes have lat/lon in map response)
		Map<String, double[]> nodeCoords = new HashMap<>();
		Pattern nodePattern = Pattern.compile("<node\\s([^>]*?)/>|<node\\s([^>]*?)>(.*?)</node>", Pattern.DOTALL);
		Matcher nodeMatcher = nodePattern.matcher(xml);

		// Also collect nodes that have tags (these are named features worth showing)
		java.util.List<String[]> taggedNodes = new java.util.ArrayList<>();

		while (nodeMatcher.find()) {
			String attrs = nodeMatcher.group(1) != null ? nodeMatcher.group(1) : nodeMatcher.group(2);
			String body = nodeMatcher.group(3);

			String id = extractAttr(attrs, "id");
			String lat = extractAttr(attrs, "lat");
			String lon = extractAttr(attrs, "lon");
			if (id == null || lat == null || lon == null) continue;

			try {
				nodeCoords.put(id, new double[]{ Double.parseDouble(lon), Double.parseDouble(lat) });
			} catch (NumberFormatException e) {
				continue;
			}

			// Node with tags = named feature
			if (body != null && body.contains("<tag")) {
				taggedNodes.add(new String[]{ id, lat, lon, body });
			}
		}

		boolean first = true;

		// Emit tagged nodes as Point features
		for (String[] node : taggedNodes) {
			if (!first) sb.append(",");
			first = false;

			sb.append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[");
			sb.append(node[2]).append(",").append(node[1]);
			sb.append("]},\"properties\":{");
			sb.append("\"osm_type\":\"node\"");
			sb.append(",\"osm_id\":\"").append(escapeJson(node[0])).append("\"");

			Pattern tagPattern = Pattern.compile("<tag\\s+k=['\"]([^'\"]*)['\"]\\s+v=['\"]([^'\"]*)['\"]");
			Matcher tagMatcher = tagPattern.matcher(node[3]);
			while (tagMatcher.find()) {
				sb.append(",\"").append(escapeJson(tagMatcher.group(1))).append("\":\"");
				sb.append(escapeJson(tagMatcher.group(2))).append("\"");
			}

			sb.append("}}");
		}

		// Second pass: extract ways as LineStrings
		Pattern wayPattern = Pattern.compile("<way\\s([^>]*?)>(.*?)</way>", Pattern.DOTALL);
		Matcher wayMatcher = wayPattern.matcher(xml);

		while (wayMatcher.find()) {
			String wayAttrs = wayMatcher.group(1);
			String wayBody = wayMatcher.group(2);

			String wayId = extractAttr(wayAttrs, "id");

			// Collect nd refs and resolve coordinates
			Pattern ndPattern = Pattern.compile("<nd\\s+ref=['\"]([^'\"]*)['\"]");
			Matcher ndMatcher = ndPattern.matcher(wayBody);

			StringBuilder coords = new StringBuilder();
			int coordCount = 0;
			String firstRef = null;
			String lastRef = null;
			while (ndMatcher.find()) {
				String ref = ndMatcher.group(1);
				double[] c = nodeCoords.get(ref);
				if (c != null) {
					if (coordCount == 0) firstRef = ref;
					lastRef = ref;
					if (coordCount > 0) coords.append(",");
					coords.append("[").append(c[0]).append(",").append(c[1]).append("]");
					coordCount++;
				}
			}

			if (coordCount < 2) continue;

			if (!first) sb.append(",");
			first = false;

			// Closed way (first node == last node) -> Polygon
			boolean closed = coordCount >= 4 && firstRef != null && firstRef.equals(lastRef);
			String geomType = closed ? "Polygon" : "LineString";

			sb.append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"").append(geomType).append("\",\"coordinates\":");
			if (closed) sb.append("[");
			sb.append("[").append(coords).append("]");
			if (closed) sb.append("]");
			sb.append("},\"properties\":{");
			sb.append("\"osm_type\":\"way\"");
			sb.append(",\"osm_id\":\"").append(escapeJson(wayId)).append("\"");

			// Extract way tags
			Pattern tagPattern = Pattern.compile("<tag\\s+k=['\"]([^'\"]*)['\"]\\s+v=['\"]([^'\"]*)['\"]");
			Matcher tagMatcher = tagPattern.matcher(wayBody);
			while (tagMatcher.find()) {
				sb.append(",\"").append(escapeJson(tagMatcher.group(1))).append("\":\"");
				sb.append(escapeJson(tagMatcher.group(2))).append("\"");
			}

			sb.append("}}");
		}

		sb.append("]}");
		return sb.toString();
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
