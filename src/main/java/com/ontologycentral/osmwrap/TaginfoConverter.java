package com.ontologycentral.osmwrap;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.logging.Logger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Converts Taginfo API responses to SKOS RDF/JSON representations.
 */
public class TaginfoConverter {
    static final String TAGINFO_API_BASE = "https://taginfo.openstreetmap.org/api/4";
    private static final Logger _log = Logger.getLogger(TaginfoConverter.class.getName());

    /**
     * Raw Taginfo API responses keyed by request URL. Same TTL as the Cache-Control
     * header TagServlet sends on its own responses (24h), so a repeat request within
     * that window skips the round trip to Taginfo entirely rather than just letting
     * a browser/CDN skip re-requesting osmwrap itself.
     */
    private static final Cache<String, String> HTTP_CACHE = Caffeine.newBuilder()
            .maximumSize(5000)
            .expireAfterWrite(Duration.ofHours(24))
            .build();

    private static String fetchCached(String url) throws IOException {
        String cached = HTTP_CACHE.getIfPresent(url);
        if (cached != null) {
            return cached;
        }
        String body = HttpClientUtil.fetchUrl(url);
        HTTP_CACHE.put(url, body);
        return body;
    }

    /**
     * Fetch key overview from Taginfo API
     */
    public String fetchKeyInfo(String key) throws IOException {
        String url = TAGINFO_API_BASE + "/key/overview?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8);
        return fetchCached(url);
    }

    /**
     * Fetch all values for a key from Taginfo API (with paging)
     */
    public String fetchKeyValues(String key) throws IOException {
        // sortname/sortorder are required, not defaults: without them Taginfo returns
        // values in essentially arbitrary order (observed: single-use junk like "#16"
        // ahead of "yes"/"house" for `building`), not ranked by actual usage.
        String url = TAGINFO_API_BASE + "/key/values?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8)
            + "&page=1&rp=50&sortname=count&sortorder=desc";
        return fetchCached(url);
    }

    /**
     * Fetch wiki documentation for a key from Taginfo API
     */
    public String fetchKeyWiki(String key) throws IOException {
        String url = TAGINFO_API_BASE + "/key/wiki_pages?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8);
        return fetchCached(url);
    }

    /**
     * Fetch wiki documentation for one key=value combination from Taginfo API
     */
    public String fetchTagWiki(String key, String value) throws IOException {
        String url = TAGINFO_API_BASE + "/tag/wiki_pages?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8)
            + "&value=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
        return fetchCached(url);
    }

    /**
     * Fetch stats for one key=value combination from Taginfo API
     */
    public String fetchTagStats(String key, String value) throws IOException {
        String url = TAGINFO_API_BASE + "/tag/stats?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8)
            + "&value=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
        return fetchCached(url);
    }

    /**
     * Fetch all keys from Taginfo API
     */
    public String fetchAllKeys() throws IOException {
        String url = TAGINFO_API_BASE + "/keys/all?limit=200&rp=200";
        return fetchCached(url);
    }

    /**
     * Convert Taginfo JSON to SKOS RDF/XML format using relative URIs
     */
    public String convertToSKOSRDF(String key, String keyInfoJson, String valuesJson) {
        return convertToSKOSRDF(key, keyInfoJson, valuesJson, "", "/tag/", "");
    }

    /**
     * Convert Taginfo JSON to SKOS RDF/XML format with namespace variants
     */
    public String convertToSKOSRDF(String key, String keyInfoJson, String valuesJson, String namespacesJson, String baseUri, String wikiJson) {
        // If key contains colon, it's a namespace variant - don't look for sub-variants
        if (key.contains(":")) {
            return convertToSKOSRDF(key, keyInfoJson, valuesJson, baseUri, wikiJson);
        }

        StringBuilder rdf = new StringBuilder();
        rdf.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        rdf.append("<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"\n");
        rdf.append("         xmlns:skos=\"http://www.w3.org/2004/02/skos/core#\"\n");
        rdf.append("         xmlns:rdfs=\"http://www.w3.org/2000/01/rdf-schema#\"\n");
        rdf.append("         xmlns:osm=\"http://osm.geovocab.org/vocab#\"\n");
        rdf.append("         xmlns:prov=\"http://www.w3.org/ns/prov#\"\n");
        rdf.append("         xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n");

        appendProvHeader(rdf, keyInfoUrl(key));

        // Main concept for the key -- lives at baseUri+key+"#concept", an absolute
        // reference (not a bare relative "#concept") so its identity doesn't shift
        // depending on which URL variant (.rdf/.json/plain) fetched this document;
        // distinct from the document ("") that the prov: header above describes
        // (httpRange-14: the concept isn't the response).
        String conceptUri = baseUri + escapeXml(key) + "#concept";
        rdf.append("\n  <rdf:Description rdf:about=\"").append(conceptUri).append("\">\n");
        rdf.append("    <rdf:type rdf:resource=\"http://www.w3.org/2004/02/skos/core#Concept\"/>\n");
        rdf.append("    <skos:prefLabel xml:lang=\"en\">").append(escapeXml(key)).append("</skos:prefLabel>\n");
        appendWikiLinksRDF(rdf, wikiJson);

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
                rdf.append("    <skos:narrower rdf:resource=\"").append(baseUri).append(escapeXml(variant)).append("#concept\"/>\n");
            }
        }

        // Used values as skos:example literals (all of them); wiki-confirmed ones
        // (Taginfo's in_wiki flag -- the closest thing to a codelist a freeform-text
        // key has) additionally get a skos:narrower link to their own
        // /tag/{key}={value}#concept page. Unconfirmed values stay unlinked: noise
        // and typos don't get minted as concepts.
        for (TagValue tv : extractTagValues(valuesJson)) {
            rdf.append("    <skos:example>").append(escapeXml(tv.value())).append("</skos:example>\n");
            if (tv.inWiki()) {
                rdf.append("    <skos:narrower rdf:resource=\"").append(baseUri).append(escapeXml(key))
                    .append("=").append(escapeXml(encodeUriComponent(tv.value()))).append("#concept\"/>\n");
            }
        }

        rdf.append("  </rdf:Description>\n");

        // Add variant concepts (each is its own document; this is a stub description
        // of it from here, matching what that document itself asserts at its #concept)
        for (String variant : variants) {
            if (variant != null && !variant.isEmpty()) {
                rdf.append("\n  <rdf:Description rdf:about=\"").append(baseUri).append(escapeXml(variant)).append("#concept\">\n");
                rdf.append("    <rdf:type rdf:resource=\"http://www.w3.org/2004/02/skos/core#Concept\"/>\n");
                rdf.append("    <skos:prefLabel xml:lang=\"en\">").append(escapeXml(variant)).append("</skos:prefLabel>\n");
                rdf.append("    <skos:broader rdf:resource=\"").append(conceptUri).append("\"/>\n");
                rdf.append("  </rdf:Description>\n");
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
    public String convertToSKOSRDF(String key, String keyInfoJson, String valuesJson, String baseUri, String wikiJson) {
        StringBuilder rdf = new StringBuilder();
        rdf.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        rdf.append("<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"\n");
        rdf.append("         xmlns:skos=\"http://www.w3.org/2004/02/skos/core#\"\n");
        rdf.append("         xmlns:rdfs=\"http://www.w3.org/2000/01/rdf-schema#\"\n");
        rdf.append("         xmlns:osm=\"http://osm.geovocab.org/vocab#\"\n");
        rdf.append("         xmlns:prov=\"http://www.w3.org/ns/prov#\"\n");
        rdf.append("         xmlns:dc=\"http://purl.org/dc/elements/1.1/\"\n");
        rdf.append("         xmlns:foaf=\"http://xmlns.com/foaf/0.1/\">\n");

        appendProvHeader(rdf, keyInfoUrl(key));

        // Main concept for the key -- absolute reference, see comment in the 5-arg
        // convertToSKOSRDF overload above for why not a bare relative "#concept".
        String conceptUri = baseUri + escapeXml(key) + "#concept";
        rdf.append("\n  <rdf:Description rdf:about=\"").append(conceptUri).append("\">\n");
        rdf.append("    <rdf:type rdf:resource=\"http://www.w3.org/2004/02/skos/core#Concept\"/>\n");
        rdf.append("    <rdf:type rdf:resource=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#Property\"/>\n");
        rdf.append("    <skos:prefLabel xml:lang=\"en\">").append(escapeXml(key)).append("</skos:prefLabel>\n");
        rdf.append("    <rdfs:label>").append(escapeXml(key)).append("</rdfs:label>\n");
        appendWikiLinksRDF(rdf, wikiJson);

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

        // Used values as skos:example literals (all of them); wiki-confirmed ones
        // (Taginfo's in_wiki flag -- the closest thing to a codelist a freeform-text
        // key has) additionally get a skos:narrower link to their own
        // /tag/{key}={value}#concept page. Unconfirmed values stay unlinked: noise
        // and typos don't get minted as concepts.
        for (TagValue tv : extractTagValues(valuesJson)) {
            rdf.append("    <skos:example>").append(escapeXml(tv.value())).append("</skos:example>\n");
            if (tv.inWiki()) {
                rdf.append("    <skos:narrower rdf:resource=\"").append(baseUri).append(escapeXml(key))
                    .append("=").append(escapeXml(encodeUriComponent(tv.value()))).append("#concept\"/>\n");
            }
        }

        rdf.append("  </rdf:Description>\n");
        rdf.append("\n</rdf:RDF>\n");
        return rdf.toString();
    }

    /**
     * Convert Taginfo per-value stats to a SKOS Concept for one key=value combination,
     * narrower than the key's own concept at {@code baseUri + key}.
     * @param key The OSM tag key
     * @param value The OSM tag value
     * @param statsJson The Taginfo tag/stats JSON for this key+value
     * @param baseUri The base URI for concepts (e.g., "/tag/" or "http://example.com/tag/")
     */
    public String convertValueToSKOSRDF(String key, String value, String statsJson, String baseUri, String wikiJson) {
        // Only the value is percent-encoded (it's free text and can contain spaces,
        // '&', etc. -- see encodeUriComponent). The key keeps the same plain,
        // XML-escaped-only form the key page itself uses as its #concept identity
        // (escapeXml(key), no percent-encoding), so a skos:broader/narrower link
        // built from here resolves to the exact URI that document asserts about
        // itself, rather than a percent-encoding variant of it.
        String keyPart = escapeXml(key);
        String encValue = encodeUriComponent(value);

        StringBuilder rdf = new StringBuilder();
        rdf.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        rdf.append("<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"\n");
        rdf.append("         xmlns:skos=\"http://www.w3.org/2004/02/skos/core#\"\n");
        rdf.append("         xmlns:rdfs=\"http://www.w3.org/2000/01/rdf-schema#\"\n");
        rdf.append("         xmlns:prov=\"http://www.w3.org/ns/prov#\"\n");
        rdf.append("         xmlns:osm=\"http://osm.geovocab.org/vocab#\">\n");

        appendProvHeader(rdf, tagStatsUrl(key, value));

        // Main concept for this key=value pair -- absolute reference (see comment in
        // the key-page convertToSKOSRDF for why not a bare relative "#concept"),
        // distinct from the document ("") that the prov: header above describes.
        String conceptUri = baseUri + keyPart + "=" + encValue + "#concept";
        rdf.append("\n  <rdf:Description rdf:about=\"").append(conceptUri).append("\">\n");
        rdf.append("    <rdf:type rdf:resource=\"http://www.w3.org/2004/02/skos/core#Concept\"/>\n");
        rdf.append("    <skos:prefLabel xml:lang=\"en\">").append(escapeXml(key)).append("=").append(escapeXml(value)).append("</skos:prefLabel>\n");
        rdf.append("    <skos:broader rdf:resource=\"").append(baseUri).append(keyPart).append("#concept\"/>\n");
        appendWikiLinksRDF(rdf, wikiJson);

        long countAll = extractCount(statsJson, "\"type\":\"all\"");
        long countNodes = extractCount(statsJson, "\"type\":\"nodes\"");
        long countWays = extractCount(statsJson, "\"type\":\"ways\"");
        long countRelations = extractCount(statsJson, "\"type\":\"relations\"");

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

        rdf.append("  </rdf:Description>\n");
        rdf.append("\n</rdf:RDF>\n");
        return rdf.toString();
    }

    /**
     * Convert Taginfo per-value stats to a SKOS Concept JSON-LD for one key=value combination.
     * @param key The OSM tag key
     * @param value The OSM tag value
     * @param statsJson The Taginfo tag/stats JSON for this key+value
     * @param baseUri The base URI for concepts (e.g., "/tag/" or "http://example.com/tag/")
     */
    public String convertValueToSKOSJson(String key, String value, String statsJson, String baseUri, String wikiJson) {
        // See convertValueToSKOSRDF: only the value is percent-encoded; the key stays
        // in the same plain form the key page uses as its own #concept identity.
        String keyPart = escapeJson(key);
        String encValue = encodeUriComponent(value);

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"@context\": {\n");
        json.append("    \"@vocab\": \"http://www.w3.org/2004/02/skos/core#\",\n");
        json.append("    \"osm\": \"http://osm.geovocab.org/vocab#\",\n");
        json.append("    \"prov\": \"http://www.w3.org/ns/prov#\",\n");
        json.append("    \"rdfs\": \"http://www.w3.org/2000/01/rdf-schema#\"\n");
        json.append("  },\n");
        json.append("  \"@graph\": [\n");
        json.append(provGraphNode(tagStatsUrl(key, value))).append(",\n");

        // Main concept for this key=value pair -- absolute @id (see the RDF/XML
        // counterpart for why not a bare relative "#concept"), distinct from the
        // document (the graph node above) that the prov: metadata describes.
        json.append("    {\n");
        json.append("      \"@id\": \"").append(baseUri).append(keyPart).append("=").append(escapeJson(encValue)).append("#concept\",\n");
        json.append("      \"@type\": \"Concept\",\n");
        json.append("      \"prefLabel\": \"").append(escapeJson(key)).append("=").append(escapeJson(value)).append("\",\n");
        json.append("      \"broader\": \"").append(baseUri).append(keyPart).append("#concept\",\n");
        appendWikiLinksJson(json, wikiJson);

        long countAll = extractCount(statsJson, "\"type\":\"all\"");
        long countNodes = extractCount(statsJson, "\"type\":\"nodes\"");
        long countWays = extractCount(statsJson, "\"type\":\"ways\"");
        long countRelations = extractCount(statsJson, "\"type\":\"relations\"");

        json.append("      \"osm:statistics\": {\n");
        if (countAll > 0) json.append("        \"osm:countAll\": ").append(countAll).append(",\n");
        if (countNodes > 0) json.append("        \"osm:countNodes\": ").append(countNodes).append(",\n");
        if (countWays > 0) json.append("        \"osm:countWays\": ").append(countWays).append(",\n");
        if (countRelations > 0) json.append("        \"osm:countRelations\": ").append(countRelations);
        trimTrailingComma(json);
        json.append("\n      }\n");
        json.append("    }\n");
        json.append("  ]\n");

        json.append("}\n");
        return json.toString();
    }

    /**
     * Convert Taginfo JSON to SKOS JSON-LD format using relative URIs
     */
    public String convertToSKOSJson(String key, String keyInfoJson, String valuesJson) {
        return convertToSKOSJson(key, keyInfoJson, valuesJson, "", "/tag/", "");
    }

    /**
     * Convert Taginfo JSON to SKOS JSON-LD format with namespace variants
     */
    public String convertToSKOSJson(String key, String keyInfoJson, String valuesJson, String namespacesJson, String baseUri, String wikiJson) {
        // If key contains colon, it's a namespace variant - don't look for sub-variants
        if (key.contains(":")) {
            return convertToSKOSJson(key, keyInfoJson, valuesJson, baseUri, wikiJson);
        }

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"@context\": {\n");
        json.append("    \"@vocab\": \"http://www.w3.org/2004/02/skos/core#\",\n");
        json.append("    \"osm\": \"http://osm.geovocab.org/vocab#\",\n");
        json.append("    \"prov\": \"http://www.w3.org/ns/prov#\",\n");
        json.append("    \"rdfs\": \"http://www.w3.org/2000/01/rdf-schema#\",\n");
        json.append("    \"dc\": \"http://purl.org/dc/elements/1.1/\"\n");
        json.append("  },\n");
        json.append("  \"@graph\": [\n");
        json.append(provGraphNode(keyInfoUrl(key))).append(",\n");

        // Main concept for the key -- absolute @id, distinct from the document
        // (the graph node above) that the prov: metadata describes.
        json.append("    {\n");
        json.append("      \"@id\": \"").append(baseUri).append(escapeJson(key)).append("#concept\",\n");
        json.append("      \"@type\": \"Concept\",\n");
        json.append("      \"prefLabel\": \"").append(escapeJson(key)).append("\",\n");
        appendWikiLinksJson(json, wikiJson);

        long countAll = extractCount(keyInfoJson, "\"type\":\"all\"");
        long countNodes = extractCount(keyInfoJson, "\"type\":\"nodes\"");
        long countWays = extractCount(keyInfoJson, "\"type\":\"ways\"");
        long countRelations = extractCount(keyInfoJson, "\"type\":\"relations\"");

        json.append("      \"osm:statistics\": {\n");
        if (countAll > 0) {
            json.append("        \"osm:countAll\": ").append(countAll).append(",\n");
        }
        if (countNodes > 0) {
            json.append("        \"osm:countNodes\": ").append(countNodes).append(",\n");
        }
        if (countWays > 0) {
            json.append("        \"osm:countWays\": ").append(countWays).append(",\n");
        }
        if (countRelations > 0) {
            json.append("        \"osm:countRelations\": ").append(countRelations);
        }
        trimTrailingComma(json);
        json.append("\n      },\n");

        // Namespace variants and wiki-confirmed values (Taginfo's in_wiki flag -- the
        // closest thing to a codelist a freeform-text key has) are both narrower
        // concepts: variants get their own key page, values get their own
        // /tag/{key}={value}#concept page. Unconfirmed values stay skos:example-only
        // below, unlinked: noise and typos don't get minted as concepts.
        String[] variants = extractNamespaceVariants(namespacesJson, key);
        TagValue[] tagValues = extractTagValues(valuesJson);

        boolean anyNarrowerValue = false;
        for (TagValue tv : tagValues) {
            if (tv.inWiki()) {
                anyNarrowerValue = true;
                break;
            }
        }

        if (variants.length > 0 || anyNarrowerValue) {
            json.append("      \"narrower\": [\n");
            int count = 0;
            for (String variant : variants) {
                if (variant != null && !variant.isEmpty()) {
                    if (count > 0) json.append(",\n");
                    json.append("        {\n");
                    json.append("          \"@id\": \"").append(baseUri).append(escapeJson(variant)).append("#concept\",\n");
                    json.append("          \"@type\": \"Concept\",\n");
                    json.append("          \"prefLabel\": \"").append(escapeJson(variant)).append("\"\n");
                    json.append("        }");
                    count++;
                }
            }
            for (TagValue tv : tagValues) {
                if (tv.inWiki()) {
                    if (count > 0) json.append(",\n");
                    json.append("        {\n");
                    json.append("          \"@id\": \"").append(baseUri).append(escapeJson(key))
                        .append("=").append(escapeJson(encodeUriComponent(tv.value()))).append("#concept\",\n");
                    json.append("          \"@type\": \"Concept\",\n");
                    json.append("          \"prefLabel\": \"").append(escapeJson(key)).append("=").append(escapeJson(tv.value())).append("\"\n");
                    json.append("        }");
                    count++;
                }
            }
            json.append("\n      ],\n");
        }

        // All used values as skos:example literals too (plain strings, quick to scan)
        if (tagValues.length > 0) {
            json.append("      \"example\": [\n");
            int count = 0;
            for (TagValue tv : tagValues) {
                if (count > 0) json.append(",\n");
                json.append("        \"").append(escapeJson(tv.value())).append("\"");
                count++;
            }
            json.append("\n      ],\n");
        }

        trimTrailingComma(json);
        json.append("\n    }\n");
        json.append("  ]\n");
        json.append("}\n");
        return json.toString();
    }

    /** Removes a trailing comma (ignoring trailing whitespace) if present, no-op otherwise. */
    private static void trimTrailingComma(StringBuilder json) {
        int i = json.length() - 1;
        while (i >= 0 && Character.isWhitespace(json.charAt(i))) {
            i--;
        }
        if (i >= 0 && json.charAt(i) == ',') {
            json.delete(i, i + 1);
        }
    }

    /**
     * Convert Taginfo JSON to SKOS JSON-LD format
     * @param key The OSM tag key
     * @param keyInfoJson The Taginfo key overview JSON
     * @param valuesJson The Taginfo values JSON
     * @param baseUri The base URI for concepts (e.g., "/tag/" or "http://example.com/tag/")
     */
    public String convertToSKOSJson(String key, String keyInfoJson, String valuesJson, String baseUri, String wikiJson) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"@context\": {\n");
        json.append("    \"@vocab\": \"http://www.w3.org/2004/02/skos/core#\",\n");
        json.append("    \"osm\": \"http://osm.geovocab.org/vocab#\",\n");
        json.append("    \"prov\": \"http://www.w3.org/ns/prov#\",\n");
        json.append("    \"rdfs\": \"http://www.w3.org/2000/01/rdf-schema#\",\n");
        json.append("    \"dc\": \"http://purl.org/dc/elements/1.1/\"\n");
        json.append("  },\n");
        json.append("  \"@graph\": [\n");
        json.append(provGraphNode(keyInfoUrl(key))).append(",\n");

        json.append("    {\n");
        json.append("      \"@id\": \"").append(baseUri).append(escapeJson(key)).append("#concept\",\n");
        json.append("      \"@type\": \"Concept\",\n");
        json.append("      \"prefLabel\": \"").append(escapeJson(key)).append("\",\n");
        appendWikiLinksJson(json, wikiJson);

        long countAll = extractCount(keyInfoJson, "\"type\":\"all\"");
        long countNodes = extractCount(keyInfoJson, "\"type\":\"nodes\"");
        long countWays = extractCount(keyInfoJson, "\"type\":\"ways\"");
        long countRelations = extractCount(keyInfoJson, "\"type\":\"relations\"");

        json.append("      \"osm:statistics\": {\n");
        if (countAll > 0) json.append("        \"osm:countAll\": ").append(countAll).append(",\n");
        if (countNodes > 0) json.append("        \"osm:countNodes\": ").append(countNodes).append(",\n");
        if (countWays > 0) json.append("        \"osm:countWays\": ").append(countWays).append(",\n");
        if (countRelations > 0) json.append("        \"osm:countRelations\": ").append(countRelations);
        trimTrailingComma(json);
        json.append("\n      },\n");

        // Wiki-confirmed values (Taginfo's in_wiki flag) are narrower concepts (each
        // has its own /tag/{key}={value}#concept page); all values also get a
        // plain-string skos:example entry for quick scanning.
        TagValue[] tagValues = extractTagValues(valuesJson);
        boolean anyNarrowerValue = false;
        for (TagValue tv : tagValues) {
            if (tv.inWiki()) {
                anyNarrowerValue = true;
                break;
            }
        }

        if (anyNarrowerValue) {
            json.append("      \"narrower\": [\n");
            int count = 0;
            for (TagValue tv : tagValues) {
                if (tv.inWiki()) {
                    if (count > 0) json.append(",\n");
                    json.append("        {\n");
                    json.append("          \"@id\": \"").append(baseUri).append(escapeJson(key))
                        .append("=").append(escapeJson(encodeUriComponent(tv.value()))).append("#concept\",\n");
                    json.append("          \"@type\": \"Concept\",\n");
                    json.append("          \"prefLabel\": \"").append(escapeJson(key)).append("=").append(escapeJson(tv.value())).append("\"\n");
                    json.append("        }");
                    count++;
                }
            }
            json.append("\n      ],\n");
        }

        if (tagValues.length > 0) {
            json.append("      \"example\": [\n");
            int count = 0;
            for (TagValue tv : tagValues) {
                if (count > 0) json.append(",\n");
                json.append("        \"").append(escapeJson(tv.value())).append("\"");
                count++;
            }
            json.append("\n      ],\n");
        }

        trimTrailingComma(json);
        json.append("\n    }\n");
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
     * One value of a key, as reported by Taginfo's {@code /key/values}: the value
     * itself, whether it's a documented/wiki-confirmed value (Taginfo's own
     * {@code in_wiki} flag -- the closest thing OSM tagging has to a codelist, since
     * most keys are otherwise freeform text), and its description when {@code inWiki}
     * (Taginfo already includes this in the same response -- no extra fetch needed).
     */
    private record TagValue(String value, boolean inWiki, String description) {
        boolean hasDescription() {
            return description != null && !description.isEmpty();
        }
    }

    /**
     * Extract values from a Taginfo {@code /key/values} response, each with its
     * {@code in_wiki} flag and description. Caller must have requested
     * {@code sortname=count&sortorder=desc} (see {@link #fetchKeyValues}) for this to
     * return the actually-common values rather than an arbitrary 20.
     */
    private TagValue[] extractTagValues(String json) {
        try {
            java.util.List<TagValue> values = new java.util.ArrayList<>();
            int index = 0;
            while (true) {
                index = json.indexOf("\"value\":", index);
                if (index == -1) {
                    break;
                }
                int nextIndex = json.indexOf("\"value\":", index + 1);
                String entry = json.substring(index, nextIndex == -1 ? json.length() : nextIndex);

                String value = extractJsonStringField(entry, "\"value\":\"");
                if (value != null && !value.isEmpty() && values.size() < 20) {
                    boolean inWiki = entry.contains("\"in_wiki\":true");
                    String description = extractJsonStringField(entry, "\"description\":\"");
                    values.add(new TagValue(value, inWiki, description));
                }
                index = (nextIndex == -1) ? json.length() : nextIndex;
            }
            return values.toArray(new TagValue[0]);
        } catch (Exception e) {
            return new TagValue[0];
        }
    }

    /** A confirmed OSM wiki page: its title (e.g. "Key:building") and English description. */
    private record WikiPage(String title, String description) {
        boolean isPresent() {
            return title != null && !title.isEmpty();
        }
    }

    private static final WikiPage NO_WIKI_PAGE = new WikiPage(null, null);

    /**
     * Extract the English-language entry from a Taginfo {@code wiki_pages} response
     * (a list of per-language pages, one object per language actually written on the
     * OSM wiki). Returns {@link #NO_WIKI_PAGE} if there is no English page -- callers
     * should then omit any wiki link entirely rather than guess one, since an
     * unconfirmed guess can point at a page that doesn't exist.
     */
    private WikiPage extractEnglishWikiPage(String json) {
        try {
            int langIndex = json.indexOf("\"lang\":\"en\"");
            if (langIndex == -1) {
                return NO_WIKI_PAGE;
            }
            // Scope the search to this one language entry: up to the next "lang"
            // field (the next language's entry) or end of string. Fields we want
            // (title, description) come before the nested "image" object in every
            // observed response, so this bounded slice is safe without full JSON
            // parsing / brace matching.
            int nextLangIndex = json.indexOf("\"lang\":\"", langIndex + 1);
            String entry = json.substring(langIndex, nextLangIndex == -1 ? json.length() : nextLangIndex);
            String title = extractJsonStringField(entry, "\"title\":\"");
            String description = extractJsonStringField(entry, "\"description\":\"");
            return (title == null || title.isEmpty()) ? NO_WIKI_PAGE : new WikiPage(title, description);
        } catch (Exception e) {
            return NO_WIKI_PAGE;
        }
    }

    /** Extracts and JSON-unescapes the string value following the given {@code "field":"} pattern. */
    private String extractJsonStringField(String json, String fieldPattern) {
        int start = json.indexOf(fieldPattern);
        if (start == -1) {
            return null;
        }
        start += fieldPattern.length();
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == '"') {
                return sb.toString();
            }
            if (ch == '\\' && i + 1 < json.length()) {
                char next = json.charAt(++i);
                switch (next) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    default: sb.append(next);
                }
            } else {
                sb.append(ch);
            }
        }
        return null; // unterminated string
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
                rdf.append("    <skos:hasConcept rdf:resource=\"").append(baseUri).append(escapeXml(key)).append("#concept\"/>\n");
            }
        }

        rdf.append("  </rdf:Description>\n");

        // Add key concepts (stub descriptions; each key's own page at #concept is authoritative)
        for (String key : keys) {
            if (key != null && !key.isEmpty()) {
                rdf.append("\n  <rdf:Description rdf:about=\"").append(baseUri).append(escapeXml(key)).append("#concept\">\n");
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
                json.append("      \"@id\": \"").append(baseUri).append(escapeJson(keys[i])).append("#concept\",\n");
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

    /**
     * Percent-encodes a string for safe use inside a minted URI/IRI path segment.
     * URLEncoder produces application/x-www-form-urlencoded output (space -&gt; '+'),
     * so '+' is converted back to the RFC 3986 '%20' form.
     */
    private static String encodeUriComponent(String str) {
        return URLEncoder.encode(str, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String keyInfoUrl(String key) {
        return TAGINFO_API_BASE + "/key/overview?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8);
    }

    private static String tagStatsUrl(String key, String value) {
        return TAGINFO_API_BASE + "/tag/stats?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8)
            + "&value=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * Appends the document-level {@code rdf:Description rdf:about=""} block: PROV-O
     * metadata about this response itself, kept separate from the SKOS concept (which
     * lives at the {@code #concept} fragment) per httpRange-14 -- a concept isn't the
     * same thing as the document describing it. Caller's root element must declare
     * {@code xmlns:prov="http://www.w3.org/ns/prov#"}.
     */
    private static void appendProvHeader(StringBuilder rdf, String primarySourceUrl) {
        rdf.append("\n  <rdf:Description rdf:about=\"\">\n");
        rdf.append("    <prov:generatedAtTime rdf:datatype=\"http://www.w3.org/2001/XMLSchema#dateTime\">")
            .append(Instant.now()).append("</prov:generatedAtTime>\n");
        rdf.append("    <prov:wasAttributedTo rdf:resource=\"/index#osmwrap\"/>\n");
        if (primarySourceUrl != null && !primarySourceUrl.isEmpty()) {
            rdf.append("    <prov:hadPrimarySource rdf:resource=\"").append(escapeXml(primarySourceUrl)).append("\"/>\n");
        }
        rdf.append("  </rdf:Description>\n");
    }

    /** JSON-LD counterpart of {@link #appendProvHeader}: a graph node for the document, keyed at "". */
    private static String provGraphNode(String primarySourceUrl) {
        StringBuilder sb = new StringBuilder();
        sb.append("    {\n");
        sb.append("      \"@id\": \"\",\n");
        sb.append("      \"prov:generatedAtTime\": { \"@value\": \"").append(Instant.now())
            .append("\", \"@type\": \"http://www.w3.org/2001/XMLSchema#dateTime\" },\n");
        sb.append("      \"prov:wasAttributedTo\": { \"@id\": \"/index#osmwrap\" }");
        if (primarySourceUrl != null && !primarySourceUrl.isEmpty()) {
            sb.append(",\n      \"prov:hadPrimarySource\": { \"@id\": \"").append(escapeJson(primarySourceUrl)).append("\" }\n");
        } else {
            sb.append("\n");
        }
        sb.append("    }");
        return sb.toString();
    }

    /** MediaWiki page title as a URL slug: spaces to underscores, XML-safe as-is otherwise. */
    private static String wikiUrl(String title) {
        return "https://wiki.openstreetmap.org/wiki/" + escapeXml(title.replace(' ', '_'));
    }

    /**
     * Appends rdfs:seeAlso + skos:definition (RDF/XML) for a confirmed OSM wiki page,
     * or nothing if {@code wikiJson} has no English entry -- never guesses a link.
     */
    private void appendWikiLinksRDF(StringBuilder rdf, String wikiJson) {
        WikiPage wiki = extractEnglishWikiPage(wikiJson);
        if (!wiki.isPresent()) {
            return;
        }
        rdf.append("    <rdfs:seeAlso rdf:resource=\"").append(wikiUrl(wiki.title())).append("\"/>\n");
        if (wiki.description() != null && !wiki.description().isEmpty()) {
            rdf.append("    <skos:definition xml:lang=\"en\">").append(escapeXml(wiki.description())).append("</skos:definition>\n");
        }
    }

    /** JSON-LD counterpart of {@link #appendWikiLinksRDF}. */
    private void appendWikiLinksJson(StringBuilder json, String wikiJson) {
        WikiPage wiki = extractEnglishWikiPage(wikiJson);
        if (!wiki.isPresent()) {
            return;
        }
        json.append("      \"rdfs:seeAlso\": \"").append(escapeJson(wikiUrl(wiki.title()))).append("\",\n");
        if (wiki.description() != null && !wiki.description().isEmpty()) {
            json.append("      \"definition\": \"").append(escapeJson(wiki.description())).append("\",\n");
        }
    }

    private static String escapeXml(String str) {
        if (str == null) {
            return "";
        }
        return str.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String escapeJson(String str) {
        if (str == null) {
            return "";
        }
        return str.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
