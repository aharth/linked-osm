package com.ontologycentral.osmwrap;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Build information loaded from version.properties
 */
public class BuildInfo {
    private static final Logger LOGGER = Logger.getLogger(BuildInfo.class.getName());
    private static final Properties PROPERTIES = new Properties();

    static {
        try (InputStream is = BuildInfo.class.getResourceAsStream("/version.properties")) {
            if (is != null) {
                PROPERTIES.load(is);
            } else {
                LOGGER.warning("version.properties file not found in classpath");
            }
        } catch (IOException e) {
            LOGGER.warning("Failed to load version.properties: " + e.getMessage());
        }
    }

    /**
     * Get the project version
     */
    public static String getVersion() {
        return PROPERTIES.getProperty("project.version", "unknown");
    }

    /**
     * Get the project name
     */
    public static String getName() {
        return PROPERTIES.getProperty("project.name", "linked-osm");
    }

    /**
     * Get the User-Agent string
     */
    public static String getUserAgent() {
        return PROPERTIES.getProperty("project.user.agent", "linked-osm/unknown (+https://osmwrap.ontologycentral.com/bot.html)");
    }

    /**
     * Get the build timestamp
     */
    public static String getBuildTimestamp() {
        return PROPERTIES.getProperty("build.timestamp", "unknown");
    }

    /**
     * Main method for testing
     */
    public static void main(String[] args) {
        System.out.println("Project Name: " + getName());
        System.out.println("Project Version: " + getVersion());
        System.out.println("User Agent: " + getUserAgent());
        System.out.println("Build Timestamp: " + getBuildTimestamp());
    }
}