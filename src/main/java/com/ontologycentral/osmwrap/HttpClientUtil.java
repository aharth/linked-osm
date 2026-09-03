package com.ontologycentral.osmwrap;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared HTTP client utilities for OpenStreetMap API access.
 *
 * <p>Uses {@code java.net.http.HttpClient} (Java 11+). All upstream HTTP calls
 * go through {@link #get(String)} or {@link #post(String, String)}.
 */
public final class HttpClientUtil {

    private static final HttpClient CLIENT =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

    /** Timeout for responses that may carry large payloads (e.g. relation /full). */
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(120);

    /**
     * Perform an HTTP GET request.
     *
     * @param url the URL to fetch
     * @return the HTTP response with an InputStream body
     * @throws IOException if the request fails
     */
    public static HttpResponse<InputStream> get(String url) throws IOException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(RESPONSE_TIMEOUT)
                .header("User-Agent", BuildInfo.getUserAgent())
                .GET()
                .build();
        try {
            return CLIENT.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP GET interrupted: " + url, e);
        }
    }

    /**
     * Perform an HTTP POST request with form-encoded body.
     *
     * @param url the URL to post to
     * @param formData the form-encoded body (e.g. {@code "data=..."})
     * @return the HTTP response with an InputStream body
     * @throws IOException if the request fails
     */
    public static HttpResponse<InputStream> post(String url, String formData) throws IOException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(RESPONSE_TIMEOUT)
                .header("User-Agent", BuildInfo.getUserAgent())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formData))
                .build();
        try {
            return CLIENT.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP POST interrupted: " + url, e);
        }
    }

    /**
     * Perform an HTTP POST request with an arbitrary body and content type
     * (e.g. forwarding a client's JSON body upstream unmodified).
     *
     * @param url the URL to post to
     * @param body the raw request body
     * @param contentType the {@code Content-Type} header value
     * @return the HTTP response with an InputStream body
     * @throws IOException if the request fails
     */
    public static HttpResponse<InputStream> postRaw(String url, byte[] body, String contentType)
            throws IOException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(RESPONSE_TIMEOUT)
                .header("User-Agent", BuildInfo.getUserAgent())
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        try {
            return CLIENT.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP POST interrupted: " + url, e);
        }
    }

    /**
     * POST the same form data to each URL in order and return the first
     * 200 answer. Non-200 answers and transport errors fall through to the
     * next URL (a different hostname forces a fresh connection, sidestepping
     * a pooled connection pinned to a broken round-robin backend). When
     * every endpoint fails, the last non-200 response is returned or the
     * last exception thrown.
     *
     * @param urls the endpoint URLs, in fallback order
     * @param formData the URL-encoded form body
     * @return the first 200 response, else the last response received
     * @throws IOException when every endpoint fails at the transport level
     */
    public static HttpResponse<InputStream> postWithFallback(final String[] urls,
            final String formData) throws IOException {
        IOException lastEx = null;
        HttpResponse<InputStream> lastResp = null;
        for (String url : urls) {
            try {
                HttpResponse<InputStream> resp = post(url, formData);
                if (resp.statusCode() == 200) {
                    return resp;
                }
                lastResp = resp;
                lastEx = null;
            } catch (IOException e) {
                lastEx = e;
            }
        }
        if (lastResp != null) {
            return lastResp;
        }
        throw lastEx != null ? lastEx : new IOException("no endpoints configured");
    }

    /**
     * Read an InputStream completely and return its content as a UTF-8 String.
     *
     * @param is the InputStream to read
     * @return the content as a String
     * @throws IOException if I/O error occurs
     */
    public static String readToString(final InputStream is) throws IOException {
        return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }

    /**
     * Fetch content from a URL and return as String.
     *
     * @param url the URL to fetch from
     * @return the response body as a String
     * @throws IOException if fetch fails or response code is not 200
     */
    public static String fetchUrl(String url)
            throws IOException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(RESPONSE_TIMEOUT)
                .header("User-Agent", BuildInfo.getUserAgent())
                .GET()
                .build();
        HttpResponse<String> resp;
        try {
            resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP GET interrupted: " + url, e);
        }
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode() + " from " + url);
        }
        return resp.body();
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
     * Fetch multiple nodes in bulk using OSM API.
     * Reduces number of HTTP requests by fetching up to 50 nodes per request.
     *
     * @param nodeIds list of node IDs to fetch
     * @return map of nodeId -&gt; [lon, lat] coordinate pair
     * @throws IOException if fetch fails
     */
    public static Map<String, double[]> fetchNodesBulk(List<String> nodeIds) throws IOException {
        Map<String, double[]> coordinates = new HashMap<>();

        if (nodeIds == null || nodeIds.isEmpty()) {
            return coordinates;
        }

        final int BATCH_SIZE = 50;
        for (int i = 0; i < nodeIds.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, nodeIds.size());
            List<String> batch = nodeIds.subList(i, end);

            String nodeIds_str = String.join(",", batch);
            String url = ApiConstants.OSM_API_BASE + "/nodes?nodes=" + nodeIds_str;

            try {
                String xmlResponse = fetchUrl(url);

                Pattern nodePattern = Pattern.compile(
                        "<node id=['\"]([^'\"]+)['\"][^>]*lat=['\"]([^'\"]+)['\"][^>]*lon=['\"]([^'\"]+)['\"]");
                Matcher nodeMatcher = nodePattern.matcher(xmlResponse);
                while (nodeMatcher.find()) {
                    String nodeId = nodeMatcher.group(1);
                    double lat = Double.parseDouble(nodeMatcher.group(2));
                    double lon = Double.parseDouble(nodeMatcher.group(3));
                    coordinates.put(nodeId, new double[]{lon, lat});
                }
            } catch (IOException e) {
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
     * @return map of wayId -&gt; list of node IDs in the way
     * @throws IOException if fetch fails
     */
    public static Map<String, List<String>> fetchWaysBulk(List<String> wayIds) throws IOException {
        Map<String, List<String>> wayNodes = new HashMap<>();

        if (wayIds == null || wayIds.isEmpty()) {
            return wayNodes;
        }

        final int BATCH_SIZE = 50;
        for (int i = 0; i < wayIds.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, wayIds.size());
            List<String> batch = wayIds.subList(i, end);

            String wayIds_str = String.join(",", batch);
            String url = ApiConstants.OSM_API_BASE + "/ways?ways=" + wayIds_str;

            try {
                String xmlResponse = fetchUrl(url);

                Pattern wayPattern = Pattern.compile("<way id=['\"]([^'\"]+)['\"]");
                Pattern ndPattern = Pattern.compile("<nd ref=['\"]([^'\"]+)['\"]");

                Matcher wayMatcher = wayPattern.matcher(xmlResponse);

                while (wayMatcher.find()) {
                    String wayId = wayMatcher.group(1);
                    int wayStart = wayMatcher.start();
                    int wayEnd = xmlResponse.indexOf("</way>", wayStart);

                    if (wayEnd == -1) {
                        wayEnd = xmlResponse.length();
                    }

                    String wayXml = xmlResponse.substring(wayStart, wayEnd);
                    List<String> nodeRefs = new ArrayList<>();

                    Matcher ndMatcher = ndPattern.matcher(wayXml);
                    while (ndMatcher.find()) {
                        nodeRefs.add(ndMatcher.group(1));
                    }

                    wayNodes.put(wayId, nodeRefs);
                }
            } catch (IOException e) {
                System.err.println("Warning: Failed to fetch way batch: " + e.getMessage());
            }
        }

        return wayNodes;
    }

    /**
     * Maps an upstream I/O failure to the appropriate HTTP status code.
     * A client-side timeout ({@link HttpTimeoutException}) returns 504;
     * all other I/O failures return 500.
     *
     * @param e the exception from a failed upstream call
     * @return 504 for timeouts, 500 otherwise
     */
    public static int errorStatus(final IOException e) {
        return (e instanceof HttpTimeoutException) ? 504 : 500;
    }

    private HttpClientUtil() {
        // Utility class
    }
}
