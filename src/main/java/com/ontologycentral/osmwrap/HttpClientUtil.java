package com.ontologycentral.osmwrap;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Shared HTTP client utilities for OpenStreetMap API access.
 */
public final class HttpClientUtil {

    /**
     * Creates an HTTP connection with standard timeout and User-Agent settings.
     *
     * @param url the URL to connect to
     * @return configured HttpURLConnection
     * @throws IOException if connection creation fails
     */
    public static HttpURLConnection createConnection(String url) throws IOException {
        return createConnection(url, ApiConstants.DEFAULT_CONNECT_TIMEOUT, ApiConstants.DEFAULT_READ_TIMEOUT);
    }

    /**
     * Creates an HTTP connection with custom timeout settings.
     *
     * @param url the URL to connect to
     * @param connectTimeout connection timeout in milliseconds
     * @param readTimeout read timeout in milliseconds
     * @return configured HttpURLConnection
     * @throws IOException if connection creation fails
     */
    public static HttpURLConnection createConnection(String url, int connectTimeout, int readTimeout) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(connectTimeout);
        conn.setReadTimeout(readTimeout);
        conn.setRequestProperty("User-Agent", BuildInfo.getUserAgent());
        return conn;
    }

    /**
     * Copies data from InputStream to OutputStream using a buffer.
     *
     * @param input the source InputStream
     * @param output the destination OutputStream
     * @throws IOException if I/O error occurs
     */
    public static void copyStream(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = input.read(buffer)) != -1) {
            output.write(buffer, 0, bytesRead);
        }
    }

    /**
     * Checks HTTP response code and throws IOException for non-200 responses.
     *
     * @param connection the HTTP connection to check
     * @param context descriptive context for error messages
     * @throws IOException if response code is not 200
     */
    public static void checkResponseCode(HttpURLConnection connection, String context) throws IOException {
        int responseCode = connection.getResponseCode();
        if (responseCode == 404) {
            throw new IOException(context + " not found");
        } else if (responseCode != 200) {
            throw new IOException("HTTP " + responseCode + " from " + context + ": " + connection.getResponseMessage());
        }
    }

    /**
     * Fetch content from a URL and return as String.
     *
     * @param url the URL to fetch from
     * @param connectTimeout connection timeout in milliseconds
     * @param readTimeout read timeout in milliseconds
     * @return the response body as a String
     * @throws IOException if fetch fails or response code is not 200
     */
    public static String fetchUrl(String url, int connectTimeout, int readTimeout) throws IOException {
        HttpURLConnection conn = createConnection(url, connectTimeout, readTimeout);
        checkResponseCode(conn, url);

        try (InputStream is = conn.getInputStream()) {
            // Read the response into a StringBuilder
            StringBuilder sb = new StringBuilder();
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                sb.append(new String(buffer, 0, bytesRead, "UTF-8"));
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Fetch multiple nodes in bulk using OSM API.
     * Reduces number of HTTP requests by fetching up to 50 nodes per request.
     *
     * @param nodeIds list of node IDs to fetch
     * @return map of nodeId -> [lon, lat] coordinate pair
     * @throws IOException if fetch fails
     */
    public static Map<String, double[]> fetchNodesBulk(List<String> nodeIds) throws IOException {
        Map<String, double[]> coordinates = new HashMap<>();

        if (nodeIds == null || nodeIds.isEmpty()) {
            return coordinates;
        }

        // Batch requests in groups of 50 (OSM API limit)
        final int BATCH_SIZE = 50;
        for (int i = 0; i < nodeIds.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, nodeIds.size());
            List<String> batch = nodeIds.subList(i, end);

            String nodeIds_str = String.join(",", batch);
            String url = ApiConstants.OSM_API_BASE + "/nodes?nodes=" + nodeIds_str;

            try {
                String xmlResponse = fetchUrl(url, ApiConstants.DEFAULT_CONNECT_TIMEOUT,
                        ApiConstants.GEOMETRY_READ_TIMEOUT);

                // Parse XML response for node coordinates
                Pattern latPattern = Pattern.compile("lat=['\"]([^'\"]+)['\"]");
                Pattern lonPattern = Pattern.compile("lon=['\"]([^'\"]+)['\"]");
                Pattern nodePattern = Pattern.compile("<node id=['\"]([^'\"]+)['\"][^>]*lat=['\"]([^'\"]+)['\"][^>]*lon=['\"]([^'\"]+)['\"]");

                Matcher nodeMatcher = nodePattern.matcher(xmlResponse);
                while (nodeMatcher.find()) {
                    String nodeId = nodeMatcher.group(1);
                    double lat = Double.parseDouble(nodeMatcher.group(2));
                    double lon = Double.parseDouble(nodeMatcher.group(3));
                    coordinates.put(nodeId, new double[]{lon, lat});
                }
            } catch (IOException e) {
                // Log warning but continue with remaining batches
                System.err.println("Warning: Failed to fetch node batch: " + e.getMessage());
            }
        }

        return coordinates;
    }

    /**
     * Fetch multiple ways in bulk using OSM API.
     * Reduces number of HTTP requests by fetching up to 50 ways per request.
     *
     * @param wayIds list of way IDs to fetch
     * @return map of wayId -> list of node IDs in the way
     * @throws IOException if fetch fails
     */
    public static Map<String, List<String>> fetchWaysBulk(List<String> wayIds) throws IOException {
        Map<String, List<String>> wayNodes = new HashMap<>();

        if (wayIds == null || wayIds.isEmpty()) {
            return wayNodes;
        }

        // Batch requests in groups of 50 (OSM API limit)
        final int BATCH_SIZE = 50;
        for (int i = 0; i < wayIds.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, wayIds.size());
            List<String> batch = wayIds.subList(i, end);

            String wayIds_str = String.join(",", batch);
            String url = ApiConstants.OSM_API_BASE + "/ways?ways=" + wayIds_str;

            try {
                String xmlResponse = fetchUrl(url, ApiConstants.DEFAULT_CONNECT_TIMEOUT,
                        ApiConstants.GEOMETRY_READ_TIMEOUT);

                // Parse XML response for ways and their node references
                Pattern wayPattern = Pattern.compile("<way id=['\"]([^'\"]+)['\"]");
                Pattern ndPattern = Pattern.compile("<nd ref=['\"]([^'\"]+)['\"]");

                Matcher wayMatcher = wayPattern.matcher(xmlResponse);
                int lastWayEnd = 0;

                while (wayMatcher.find()) {
                    String wayId = wayMatcher.group(1);
                    int wayStart = wayMatcher.start();
                    int wayEnd = xmlResponse.indexOf("</way>", wayStart);

                    if (wayEnd == -1) {
                        wayEnd = xmlResponse.length();
                    }

                    // Extract node references for this way
                    String wayXml = xmlResponse.substring(wayStart, wayEnd);
                    List<String> nodeRefs = new ArrayList<>();

                    Matcher ndMatcher = ndPattern.matcher(wayXml);
                    while (ndMatcher.find()) {
                        nodeRefs.add(ndMatcher.group(1));
                    }

                    wayNodes.put(wayId, nodeRefs);
                }
            } catch (IOException e) {
                // Log warning but continue with remaining batches
                System.err.println("Warning: Failed to fetch way batch: " + e.getMessage());
            }
        }

        return wayNodes;
    }

    private HttpClientUtil() {
        // Utility class
    }
}