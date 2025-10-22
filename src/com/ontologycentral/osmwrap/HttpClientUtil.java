package com.ontologycentral.osmwrap;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

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

    private HttpClientUtil() {
        // Utility class
    }
}