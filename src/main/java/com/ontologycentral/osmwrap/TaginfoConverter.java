package com.ontologycentral.osmwrap;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * Converts Taginfo API responses to SKOS RDF/JSON representations.
 */
public class TaginfoConverter {
	static final String TAGINFO_API_BASE = "https://taginfo.openstreetmap.org/api/4";
	private static final Logger _log = Logger.getLogger(TaginfoConverter.class.getName());

	/**
	 * Fetch key overview from Taginfo API
	 */
	public String fetchKeyInfo(String key) throws IOException {
		String url = TAGINFO_API_BASE + "/key/overview?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8);
		return HttpClientUtil.fetchUrl(url);
	}

	/**
	 * Fetch all values for a key from Taginfo API (with paging)
	 */
	public String fetchKeyValues(String key) throws IOException {
		String url = TAGINFO_API_BASE + "/key/values?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8) + "&page=1&rp=50";
		return HttpClientUtil.fetchUrl(url);
	}

	/**
	 * Fetch wiki documentation for a key
	 */
	public String fetchKeyWiki(String key) throws IOException {
		String url = TAGINFO_API_BASE + "/key/" + URLEncoder.encode(key, StandardCharsets.UTF_8) + "/wiki_pages";
		return HttpClientUtil.fetchUrl(url);
	}

	/**
	 * Fetch all keys from Taginfo API
	 */
	public String fetchAllKeys() throws IOException {
		String url = TAGINFO_API_BASE + "/keys/all?limit=200&rp=200";
		return HttpClientUtil.fetchUrl(url);
	}

	/**
	 * Convert Taginfo JSON to SKOS RDF/XML format using relative URIs
	 */
	public String convertToSKOSRDF(String key, String keyInfoJson, String valuesJson) {
		return convertToSKOSRDF(key, keyInfoJson, valuesJson, "", "/tag/");
	}

	/**
	 * Convert Taginfo JSON to SKOS RDF/XML format with namespace variants
	 */
	public String convertToSKOSRDF(String key, String keyInfoJson, String valuesJson, String namespacesJson, String baseUri) {
		// If key contains colon, it's a namespace variant - don't look for sub-variants
		if (key.contains(":")) {
			return convertToSKOSRDF(key, keyInfoJson, valuesJson, baseUri);
		}

		StringBuilder rdf = new StringBuilder();
		rdf.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
		rdf.append("<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"\n");
		rdf.append("         xmlns:skos=\"http://www.w3.org/2004/02/skos/core#\"\n");
		rdf.append("         xmlns:rdfs=\"http://www.w3.org/2000/01/rdf-schema#\"\n");
		rdf.append("         xmlns:osm=\"http://osm.geovocab.org/vocab#\"\n");
		rdf.append("         xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n");

		// Main concept for the key
		rdf.append("\n  <rdf:Description rdf:about=\"").append(baseUri).append(escapeXml(key)).append("\">\n");
		rdf.append("    <rdf:type rdf:resource=\"http://www.w3.org/2004/02/skos/core#Concept\"/>\n");
		rdf.append("    <skos:prefLabel xml:lang=\"en\">").append(escapeXml(key)).append("</skos:prefLabel>\n");

		// Extract statistics
		long countAll = extractCount(keyInfoJson, "\"type\":\"all\"");
		long countNodes = extractCount(keyInfoJson, "\"type\":\"nodes\"");
		long countWays = extractCount(keyInfoJson, "\"type\":\"ways\"");
		long countRelations = extractCount(keyInfoJson, "\"type\":\"relations\"");

		if (countAll > 0) {
			rdf.append("    <osm:countAll rdf:datatype=\"http://www.w3.org/2001/XMLSchema#long\">").append(countAll).append("</osm:countAll>\n");
		}
		if (countNodes > 0) {
			rdf.append("    <osm:countNodes rdf:datatype=\"http://www.w3.org/2001/XMLSchema#long\">").append(countNodes).append("</osm:countNodes>\n");
		}
		if (countWays > 0) {
			rdf.append("    <osm:countWays rdf:datatype=\"http://www.w3.org/2001/XMLSchema#long\">").append(countWays).append("</osm:countWays>\n");
		}
		if (countRelations > 0) {
			rdf.append("    <osm:countRelations rdf:datatype=\"http://www.w3.org/2001/XMLSchema#long\">").append(countRelations).append("</osm:countRelations>\n");
		}

		// Add namespace variants as narrower concepts (Layer 2)
		String[] variants = extractNamespaceVariants(namespacesJson, key);
		for (String variant : variants) {
			if (variant != null && !variant.isEmpty()) {
				rdf.append("    <skos:narrower rdf:resource=\"").append(baseUri).append(escapeXml(variant)).append("\"/>\n");
			}
		}

		// Add values as narrower concepts (also Layer 2, or Layer 3 if variants exist)
		String[] values = extractValues(valuesJson);
		if (values.length > 0 && variants.length == 0) {
			// Only show values if no namespace variants exist
			for (String value : values) {
				if (value != null && !value.isEmpty()) {
					rdf.append("    <skos:narrower rdf:resource=\"").append(baseUri).append(escapeXml(key)).append("=").append(escapeXml(value)).append("\"/>\n");
				}
			}
		}

		rdf.append("  </rdf:Description>\n");

		// Add variant concepts
		for (String variant : variants) {
			if (variant != null && !variant.isEmpty()) {
				rdf.append("\n  <rdf:Description rdf:about=\"").append(baseUri).append(escapeXml(variant)).append("\">\n");
				rdf.append("    <rdf:type rdf:resource=\"http://www.w3.org/2004/02/skos/core#Concept\"/>\n");
				rdf.append("    <skos:prefLabel xml:lang=\"en\">").append(escapeXml(variant)).append("</skos:prefLabel>\n");
				rdf.append("    <skos:broader rdf:resource=\"").append(baseUri).append(escapeXml(key)).append("\"/>\n");
				rdf.append("  </rdf:Description>\n");
			}
		}

		// Add value concepts (only if no variants)
		if (variants.length == 0) {
			for (String value : values) {
				if (value != null && !value.isEmpty()) {
					rdf.append("\n  <rdf:Description rdf:about=\"").append(baseUri).append(escapeXml(key)).append("=").append(escapeXml(value)).append("\">\n");
					rdf.append("    <rdf:type rdf:resource=\"http://www.w3.org/2004/02/skos/core#Concept\"/>\n");
					rdf.append("    <skos:prefLabel xml:lang=\"en\">").append(escapeXml(value)).append("</skos:prefLabel>\n");
					rdf.append("    <skos:broader rdf:resource=\"").append(baseUri).append(escapeXml(key)).append("\"/>\n");
					rdf.append("  </rdf:Description>\n");
				}
			}
		}

		rdf.append("\n</rdf:RDF>\n");
		return rdf.toString();
	}

	/**
	 * Convert Taginfo JSON to SKOS RDF/XML format
	 * @param key The OSM tag key
	 * @param keyInfoJson The Taginfo key overview JSON
	 * @param valuesJson The Taginfo values JSON
	 * @param baseUri The base URI for concepts (e.g., "/tag/" or "http://example.com/tag/")
	 */
	public String convertToSKOSRDF(String key, String keyInfoJson, String valuesJson, String baseUri) {
		StringBuilder rdf = new StringBuilder();
		rdf.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
		rdf.append("<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"\n");
		rdf.append("         xmlns:skos=\"http://www.w3.org/2004/02/skos/core#\"\n");
		rdf.append("         xmlns:rdfs=\"http://www.w3.org/2000/01/rdf-schema#\"\n");
		rdf.append("         xmlns:osm=\"http://osm.geovocab.org/vocab#\"\n");
		rdf.append("         xmlns:dc=\"http://purl.org/dc/elements/1.1/\"\n");
		rdf.append("         xmlns:foaf=\"http://xmlns.com/foaf/0.1/\">\n");

		// Main concept for the key
		rdf.append("\n  <rdf:Description rdf:about=\"").append(baseUri).append(escapeXml(key)).append("\">\n");
		rdf.append("    <rdf:type rdf:resource=\"http://www.w3.org/2004/02/skos/core#Concept\"/>\n");
		rdf.append("    <skos:prefLabel xml:lang=\"en\">").append(escapeXml(key)).append("</skos:prefLabel>\n");

		// Extract statistics from the Taginfo response structure
		// The response has counts array with type/count pairs
		long countAll = extractCount(keyInfoJson, "\"type\":\"all\"");
		long countNodes = extractCount(keyInfoJson, "\"type\":\"nodes\"");
		long countWays = extractCount(keyInfoJson, "\"type\":\"ways\"");
		long countRelations = extractCount(keyInfoJson, "\"type\":\"relations\"");

		if (countAll > 0) {
			rdf.append("    <osm:countAll rdf:datatype=\"http://www.w3.org/2001/XMLSchema#long\">").append(countAll).append("</osm:countAll>\n");
		}
		if (countNodes > 0) {
			rdf.append("    <osm:countNodes rdf:datatype=\"http://www.w3.org/2001/XMLSchema#long\">").append(countNodes).append("</osm:countNodes>\n");
		}
		if (countWays > 0) {
			rdf.append("    <osm:countWays rdf:datatype=\"http://www.w3.org/2001/XMLSchema#long\">").append(countWays).append("</osm:countWays>\n");
		}
		if (countRelations > 0) {
			rdf.append("    <osm:countRelations rdf:datatype=\"http://www.w3.org/2001/XMLSchema#long\">").append(countRelations).append("</osm:countRelations>\n");
		}

		// Add values as narrower concepts
		String[] values = extractValues(valuesJson);
		if (values.length > 0) {
			for (String value : values) {
				if (value != null && !value.isEmpty()) {
					rdf.append("    <skos:narrower rdf:resource=\"").append(baseUri).append(escapeXml(key)).append("=").append(escapeXml(value)).append("\"/>\n");
				}
			}
		}

		rdf.append("  </rdf:Description>\n");

		// Add value concepts
		for (String value : values) {
			if (value != null && !value.isEmpty()) {
				rdf.append("\n  <rdf:Description rdf:about=\"").append(baseUri).append(escapeXml(key)).append("=").append(escapeXml(value)).append("\">\n");
				rdf.append("    <rdf:type rdf:resource=\"http://www.w3.org/2004/02/skos/core#Concept\"/>\n");
				rdf.append("    <skos:prefLabel xml:lang=\"en\">").append(escapeXml(value)).append("</skos:prefLabel>\n");
				rdf.append("    <skos:broader rdf:resource=\"").append(baseUri).append(escapeXml(key)).append("\"/>\n");
				rdf.append("  </rdf:Description>\n");
			}
		}

		rdf.append("\n</rdf:RDF>\n");
		return rdf.toString();
	}

	/**
	 * Convert Taginfo JSON to SKOS JSON-LD format using relative URIs
	 */
	public String convertToSKOSJson(String key, String keyInfoJson, String valuesJson) {
		return convertToSKOSJson(key, keyInfoJson, valuesJson, "", "/tag/");
	}

	/**
	 * Convert Taginfo JSON to SKOS JSON-LD format with namespace variants
	 */
	public String convertToSKOSJson(String key, String keyInfoJson, String valuesJson, String namespacesJson, String baseUri) {
		// If key contains colon, it's a namespace variant - don't look for sub-variants
		if (key.contains(":")) {
			return convertToSKOSJson(key, keyInfoJson, valuesJson, baseUri);
		}

		StringBuilder json = new StringBuilder();
		json.append("{\n");
		json.append("  \"@context\": {\n");
		json.append("    \"@vocab\": \"http://www.w3.org/2004/02/skos/core#\",\n");
		json.append("    \"osm\": \"http://osm.geovocab.org/vocab#\",\n");
		json.append("    \"dc\": \"http://purl.org/dc/elements/1.1/\"\n");
		json.append("  },\n");
		json.append("  \"@id\": \"").append(baseUri).append(escapeJson(key)).append("\",\n");
		json.append("  \"@type\": \"Concept\",\n");
		json.append("  \"prefLabel\": \"").append(escapeJson(key)).append("\",\n");

		long countAll = extractCount(keyInfoJson, "\"type\":\"all\"");
		long countNodes = extractCount(keyInfoJson, "\"type\":\"nodes\"");
		long countWays = extractCount(keyInfoJson, "\"type\":\"ways\"");
		long countRelations = extractCount(keyInfoJson, "\"type\":\"relations\"");

		json.append("  \"osm:statistics\": {\n");
		if (countAll > 0) {
			json.append("    \"osm:countAll\": ").append(countAll).append(",\n");
		}
		if (countNodes > 0) {
			json.append("    \"osm:countNodes\": ").append(countNodes).append(",\n");
		}
		if (countWays > 0) {
			json.append("    \"osm:countWays\": ").append(countWays).append(",\n");
		}
		if (countRelations > 0) {
			json.append("    \"osm:countRelations\": ").append(countRelations);
		}
		json.append("\n  },\n");

		// Extract namespace variants and values
		String[] variants = extractNamespaceVariants(namespacesJson, key);
		String[] values = extractValues(valuesJson);

		// Show narrower concepts (variants or values)
		json.append("  \"narrower\": [\n");
		int count = 0;

		// Add variants first if they exist
		for (String variant : variants) {
			if (variant != null && !variant.isEmpty()) {
				if (count > 0) json.append(",\n");
				json.append("    {\n");
				json.append("      \"@id\": \"").append(baseUri).append(escapeJson(variant)).append("\",\n");
				json.append("      \"@type\": \"Concept\",\n");
				json.append("      \"prefLabel\": \"").append(escapeJson(variant)).append("\"\n");
				json.append("    }");
				count++;
			}
		}

		// Add values only if no variants exist
		if (variants.length == 0) {
			for (int i = 0; i < values.length; i++) {
				if (values[i] != null && !values[i].isEmpty()) {
					if (count > 0) json.append(",\n");
					json.append("    {\n");
					json.append("      \"@id\": \"").append(baseUri).append(escapeJson(key)).append("=").append(escapeJson(values[i])).append("\",\n");
					json.append("      \"@type\": \"Concept\",\n");
					json.append("      \"prefLabel\": \"").append(escapeJson(values[i])).append("\"\n");
					json.append("    }");
					count++;
				}
			}
		}

		json.append("\n  ]\n");
		json.append("}\n");
		return json.toString();
	}

	/**
	 * Convert Taginfo JSON to SKOS JSON-LD format
	 * @param key The OSM tag key
	 * @param keyInfoJson The Taginfo key overview JSON
	 * @param valuesJson The Taginfo values JSON
	 * @param baseUri The base URI for concepts (e.g., "/tag/" or "http://example.com/tag/")
	 */
	public String convertToSKOSJson(String key, String keyInfoJson, String valuesJson, String baseUri) {
		StringBuilder json = new StringBuilder();
		json.append("{\n");
		json.append("  \"@context\": {\n");
		json.append("    \"@vocab\": \"http://www.w3.org/2004/02/skos/core#\",\n");
		json.append("    \"osm\": \"http://osm.geovocab.org/vocab#\",\n");
		json.append("    \"dc\": \"http://purl.org/dc/elements/1.1/\"\n");
		json.append("  },\n");
		json.append("  \"@id\": \"").append(baseUri).append(escapeJson(key)).append("\",\n");
		json.append("  \"@type\": \"Concept\",\n");
		json.append("  \"prefLabel\": \"").append(escapeJson(key)).append("\",\n");

		long countAll = extractCount(keyInfoJson, "\"type\":\"all\"");
		long countNodes = extractCount(keyInfoJson, "\"type\":\"nodes\"");
		long countWays = extractCount(keyInfoJson, "\"type\":\"ways\"");
		long countRelations = extractCount(keyInfoJson, "\"type\":\"relations\"");

		json.append("  \"osm:statistics\": {\n");
		if (countAll > 0) {
			json.append("    \"osm:countAll\": ").append(countAll).append(",\n");
		}
		if (countNodes > 0) {
			json.append("    \"osm:countNodes\": ").append(countNodes).append(",\n");
		}
		if (countWays > 0) {
			json.append("    \"osm:countWays\": ").append(countWays).append(",\n");
		}
		if (countRelations > 0) {
			json.append("    \"osm:countRelations\": ").append(countRelations);
		}
		json.append("\n  },\n");

		String[] values = extractValues(valuesJson);
		json.append("  \"narrower\": [\n");
		for (int i = 0; i < values.length; i++) {
			if (values[i] != null && !values[i].isEmpty()) {
				json.append("    {\n");
				json.append("      \"@id\": \"").append(baseUri).append(escapeJson(key)).append("=").append(escapeJson(values[i])).append("\",\n");
				json.append("      \"@type\": \"Concept\",\n");
				json.append("      \"prefLabel\": \"").append(escapeJson(values[i])).append("\"\n");
				json.append("    }");
				if (i < values.length - 1) {
					json.append(",");
				}
				json.append("\n");
			}
		}
		json.append("  ]\n");
		json.append("}\n");
		return json.toString();
	}

	/**
	 * Extract count value from Taginfo response for a specific type
	 * Looks for {"type":"xyz","count":number} pattern
	 */
	private long extractCount(String json, String typePattern) {
		try {
			int index = json.indexOf(typePattern);
			if (index == -1) {
				return 0;
			}
			// Find the count field after the type field
			int countIndex = json.indexOf("\"count\":", index);
			if (countIndex == -1) {
				return 0;
			}
			int startIndex = countIndex + 8; // length of "count":
			while (startIndex < json.length() && Character.isWhitespace(json.charAt(startIndex))) {
				startIndex++;
			}

			int endIndex = startIndex;
			while (endIndex < json.length() && Character.isDigit(json.charAt(endIndex))) {
				endIndex++;
			}

			if (endIndex > startIndex) {
				return Long.parseLong(json.substring(startIndex, endIndex));
			}
		} catch (Exception e) {
			// Silently return 0 on parse error
		}
		return 0;
	}

	/**
	 * Extract all values from the Taginfo values response
	 * Looks for "value": "xyz" patterns in the data array
	 */
	private String[] extractValues(String json) {
		try {
			java.util.List<String> values = new java.util.ArrayList<>();
			int index = 0;
			while (true) {
				index = json.indexOf("\"value\":", index);
				if (index == -1) {
					break;
				}
				int startIndex = index + 8; // length of "value":
				while (startIndex < json.length() && Character.isWhitespace(json.charAt(startIndex))) {
					startIndex++;
				}

				if (startIndex < json.length() && json.charAt(startIndex) == '"') {
					startIndex++;
					int endIndex = json.indexOf('"', startIndex);
					if (endIndex != -1) {
						String value = json.substring(startIndex, endIndex);
						// Only add non-empty values, limit to first 20
						if (!value.isEmpty() && values.size() < 20) {
							values.add(value);
						}
						index = endIndex + 1;
					} else {
						break;
					}
				} else {
					break;
				}
			}
			return values.toArray(new String[0]);
		} catch (Exception e) {
			return new String[0];
		}
	}

	/**
	 * Convert all keys to SKOS RDF/XML format
	 */
	public String convertKeysToSKOSRDF(String allKeysJson) {
		return convertKeysToSKOSRDF(allKeysJson, "/tag/");
	}

	/**
	 * Convert all keys to SKOS RDF/XML format with custom base URI
	 */
	public String convertKeysToSKOSRDF(String allKeysJson, String baseUri) {
		StringBuilder rdf = new StringBuilder();
		rdf.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
		rdf.append("<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"\n");
		rdf.append("         xmlns:skos=\"http://www.w3.org/2004/02/skos/core#\"\n");
		rdf.append("         xmlns:rdfs=\"http://www.w3.org/2000/01/rdf-schema#\"\n");
		rdf.append("         xmlns:osm=\"http://osm.geovocab.org/vocab#\"\n");
		rdf.append("         xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n");

		// Concept scheme
		rdf.append("\n  <rdf:Description rdf:about=\"").append(baseUri).append("index\">\n");
		rdf.append("    <rdf:type rdf:resource=\"http://www.w3.org/2004/02/skos/core#ConceptScheme\"/>\n");
		rdf.append("    <skos:prefLabel xml:lang=\"en\">OpenStreetMap Tag Keys</skos:prefLabel>\n");
		rdf.append("    <dc:title xml:lang=\"en\">Index of OpenStreetMap Tag Keys</dc:title>\n");
		rdf.append("    <dc:description xml:lang=\"en\">A SKOS concept scheme listing all OpenStreetMap tag keys from Taginfo</dc:description>\n");

		// Extract and add all keys
		String[] keys = extractAllKeys(allKeysJson);
		for (String key : keys) {
			if (key != null && !key.isEmpty()) {
				rdf.append("    <skos:hasConcept rdf:resource=\"").append(baseUri).append(escapeXml(key)).append("\"/>\n");
			}
		}

		rdf.append("  </rdf:Description>\n");

		// Add key concepts
		for (String key : keys) {
			if (key != null && !key.isEmpty()) {
				rdf.append("\n  <rdf:Description rdf:about=\"").append(baseUri).append(escapeXml(key)).append("\">\n");
				rdf.append("    <rdf:type rdf:resource=\"http://www.w3.org/2004/02/skos/core#Concept\"/>\n");
				rdf.append("    <skos:inScheme rdf:resource=\"").append(baseUri).append("index\"/>\n");
				rdf.append("    <skos:prefLabel xml:lang=\"en\">").append(escapeXml(key)).append("</skos:prefLabel>\n");
				rdf.append("  </rdf:Description>\n");
			}
		}

		rdf.append("\n</rdf:RDF>\n");
		return rdf.toString();
	}

	/**
	 * Convert all keys to JSON-LD format
	 */
	public String convertKeysToSKOSJson(String allKeysJson) {
		return convertKeysToSKOSJson(allKeysJson, "/tag/");
	}

	/**
	 * Convert all keys to JSON-LD format with custom base URI
	 */
	public String convertKeysToSKOSJson(String allKeysJson, String baseUri) {
		StringBuilder json = new StringBuilder();
		json.append("{\n");
		json.append("  \"@context\": {\n");
		json.append("    \"@vocab\": \"http://www.w3.org/2004/02/skos/core#\",\n");
		json.append("    \"osm\": \"http://osm.geovocab.org/vocab#\",\n");
		json.append("    \"dc\": \"http://purl.org/dc/elements/1.1/\"\n");
		json.append("  },\n");
		json.append("  \"@id\": \"").append(baseUri).append("index\",\n");
		json.append("  \"@type\": \"ConceptScheme\",\n");
		json.append("  \"prefLabel\": \"OpenStreetMap Tag Keys\",\n");
		json.append("  \"dc:title\": \"Index of OpenStreetMap Tag Keys\",\n");
		json.append("  \"dc:description\": \"A SKOS concept scheme listing all OpenStreetMap tag keys from Taginfo\",\n");

		// Extract and add all keys
		String[] keys = extractAllKeys(allKeysJson);
		json.append("  \"hasConcept\": [\n");

		for (int i = 0; i < keys.length; i++) {
			if (keys[i] != null && !keys[i].isEmpty()) {
				json.append("    {\n");
				json.append("      \"@id\": \"").append(baseUri).append(escapeJson(keys[i])).append("\",\n");
				json.append("      \"@type\": \"Concept\",\n");
				json.append("      \"prefLabel\": \"").append(escapeJson(keys[i])).append("\",\n");
				json.append("      \"inScheme\": \"").append(baseUri).append("index\"\n");
				json.append("    }");
				if (i < keys.length - 1) {
					json.append(",");
				}
				json.append("\n");
			}
		}

		json.append("  ]\n");
		json.append("}\n");
		return json.toString();
	}

	/**
	 * Extract all base keys (Layer 1 - no colon) from the Taginfo response
	 */
	private String[] extractAllKeys(String json) {
		try {
			java.util.List<String> keys = new java.util.ArrayList<>();
			int index = 0;
			while (true) {
				index = json.indexOf("\"key\":", index);
				if (index == -1) {
					break;
				}
				int startIndex = index + 6; // length of "key":
				while (startIndex < json.length() && Character.isWhitespace(json.charAt(startIndex))) {
					startIndex++;
				}

				if (startIndex < json.length() && json.charAt(startIndex) == '"') {
					startIndex++;
					int endIndex = json.indexOf('"', startIndex);
					if (endIndex != -1) {
						String key = json.substring(startIndex, endIndex);
						// Only add base keys: non-empty, no special chars, no colons (Layer 1 only)
						if (!key.isEmpty() && !key.startsWith("*") && !key.startsWith(":") &&
						    !key.startsWith("+") && !key.matches("^[A-Z].*") &&
						    !key.contains(":")) {  // IMPORTANT: Filter out namespace variants here
							if (!keys.contains(key)) {
								keys.add(key);
							}
						}
						index = endIndex + 1;
					} else {
						break;
					}
				} else {
					break;
				}
			}
			return keys.toArray(new String[0]);
		} catch (Exception e) {
			return new String[0];
		}
	}

	/**
	 * Extract namespace variants (Layer 2 - with colon) for a given base key
	 * e.g., for key "name", returns ["name:en", "name:fr", "name:de", ...]
	 */
	public String[] extractNamespaceVariants(String json, String baseKey) {
		return extractNamespaceVariants(json, baseKey, 1000); // Minimum 1000 uses
	}

	/**
	 * Extract namespace variants (keys with colons) for a base key, filtered by minimum usage count.
	 * Only returns variants that have at least minCount uses.
	 */
	public String[] extractNamespaceVariants(String json, String baseKey, long minCount) {
		try {
			java.util.List<String> variants = new java.util.ArrayList<>();
			int index = 0;
			String keyPattern = "\"key\":\"" + baseKey + ":";
			while (true) {
				index = json.indexOf(keyPattern, index);
				if (index == -1) {
					break;
				}
				// Extract the full key including the namespace
				int startIndex = index + 7; // length of "key":"
				int endIndex = json.indexOf('"', startIndex);
				if (endIndex != -1) {
					String key = json.substring(startIndex, endIndex);

					// Extract count_all for this key from the same object
					long countAll = extractCountFromKeyObject(json, index);

					// Only include if usage is above minimum threshold
					if (countAll >= minCount && !variants.contains(key)) {
						variants.add(key);
					}
					index = endIndex + 1;
				} else {
					break;
				}
			}
			return variants.toArray(new String[0]);
		} catch (Exception e) {
			return new String[0];
		}
	}

	/**
	 * Extract count_all value from a key object in the JSON response.
	 * Looks for "count_all": <number> following the current index.
	 */
	private long extractCountFromKeyObject(String json, int keyIndex) {
		try {
			int searchStart = keyIndex;

			// Find opening brace for this key object
			for (int i = keyIndex - 1; i >= 0; i--) {
				if (json.charAt(i) == '{') {
					searchStart = i;
					break;
				}
			}

			// Look for count_all in this object
			String countPattern = "\"count_all\":";
			int countIndex = json.indexOf(countPattern, searchStart);

			// Make sure it's in the same object (check for closing brace between key and count)
			int closeBrace = json.indexOf("}", searchStart);
			if (countIndex != -1 && closeBrace != -1 && countIndex < closeBrace) {
				// Extract the number after count_all
				int numberStart = countIndex + countPattern.length();
				int numberEnd = numberStart;
				while (numberEnd < json.length() && Character.isDigit(json.charAt(numberEnd))) {
					numberEnd++;
				}
				if (numberEnd > numberStart) {
					String countStr = json.substring(numberStart, numberEnd);
					return Long.parseLong(countStr);
				}
			}
			return 0;
		} catch (Exception e) {
			return 0;
		}
	}

	private String escapeXml(String str) {
		if (str == null) {
			return "";
		}
		return str.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
	}

	private String escapeJson(String str) {
		if (str == null) {
			return "";
		}
		return str.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
	}
}
