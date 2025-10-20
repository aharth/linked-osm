package com.ontologycentral.osmwrap;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.logging.Logger;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

/**
 * Command-line interface for Linked OpenStreetMap data access.
 *
 * Provides access to OSM nodes, ways, and relations as RDF/XML via command line.
 */
public class Main {
    private static final Logger logger = Logger.getLogger(Main.class.getName());

    public static final String OSM_API_BASE = "https://api.openstreetmap.org/api/0.6";
    public static final String NOMINATIM_API_BASE = "https://nominatim.openstreetmap.org";

    public static void main(String[] args) {
        Options options = createOptions();
        CommandLineParser parser = new DefaultParser();

        try {
            CommandLine cmd = parser.parse(options, args);

            if (cmd.hasOption("help") || args.length == 0) {
                printHelp(options);
                return;
            }

            if (cmd.hasOption("node")) {
                String nodeId = cmd.getOptionValue("node");
                fetchOsmFeature("node", nodeId);
            } else if (cmd.hasOption("way")) {
                String wayId = cmd.getOptionValue("way");
                fetchOsmFeature("way", wayId);
            } else if (cmd.hasOption("relation")) {
                String relationId = cmd.getOptionValue("relation");
                fetchOsmFeature("relation", relationId);
            } else if (cmd.hasOption("search")) {
                String query = cmd.getOptionValue("search");
                searchFeatures(query);
            } else if (cmd.hasOption("map")) {
                String bbox = cmd.getOptionValue("map");
                fetchMapData(bbox);
            } else if (cmd.hasOption("poi")) {
                String bbox = cmd.getOptionValue("poi");
                fetchPOIData(bbox);
            } else {
                System.err.println("No valid operation specified. Use --help for usage information.");
                System.exit(1);
            }

        } catch (ParseException e) {
            System.err.println("Error parsing command line: " + e.getMessage());
            printHelp(options);
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            logger.severe("Unexpected error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static Options createOptions() {
        Options options = new Options();

        options.addOption(Option.builder("n")
                .longOpt("node")
                .hasArg()
                .argName("ID")
                .desc("Fetch OSM node by ID")
                .build());

        options.addOption(Option.builder("w")
                .longOpt("way")
                .hasArg()
                .argName("ID")
                .desc("Fetch OSM way by ID")
                .build());

        options.addOption(Option.builder("r")
                .longOpt("relation")
                .hasArg()
                .argName("ID")
                .desc("Fetch OSM relation by ID")
                .build());

        options.addOption(Option.builder("s")
                .longOpt("search")
                .hasArg()
                .argName("QUERY")
                .desc("Search for features using Nominatim")
                .build());

        options.addOption(Option.builder("m")
                .longOpt("map")
                .hasArg()
                .argName("BBOX")
                .desc("Fetch map data for bounding box (west,south,east,north)")
                .build());

        options.addOption(Option.builder("p")
                .longOpt("poi")
                .hasArg()
                .argName("BBOX")
                .desc("Fetch points of interest (amenities) for bounding box (west,south,east,north)")
                .build());

        options.addOption(Option.builder("h")
                .longOpt("help")
                .desc("Show this help message")
                .build());

        return options;
    }

    private static void printHelp(Options options) {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp("linked-osm",
                "Command-line tool for accessing OpenStreetMap data as Linked Data\n\n",
                options,
                "\nExamples:\n" +
                "  linked-osm --node 17807753\n" +
                "  linked-osm --way 34148844\n" +
                "  linked-osm --relation 129836\n" +
                "  linked-osm --search \"London\"\n" +
                "  linked-osm --map \"-118.241,34.050,-118.240,34.051\"\n" +
                "  linked-osm --poi \"-118.9448,32.8007,-117.6462,34.8233\"\n\n" +
                "For more information, visit: https://github.com/your-repo/linked-osm");
    }

    private static void fetchOsmFeature(String type, String id) throws IOException {
        String url = OSM_API_BASE + "/" + type + "/" + id;
        logger.info("Fetching " + type + " " + id + " from " + url);

        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("User-Agent", "LinkedOSM/1.0");

        int responseCode = connection.getResponseCode();
        if (responseCode == 200) {
            // TODO: Apply XSLT transformation to convert to RDF/XML
            // For now, just output the raw OSM XML
            try (InputStream input = connection.getInputStream()) {
                copyStream(input, System.out);
            }
        } else if (responseCode == 404) {
            System.err.println("Error: " + type + " " + id + " not found");
            System.exit(1);
        } else {
            System.err.println("Error: HTTP " + responseCode + " from OSM API");
            System.exit(1);
        }
    }

    private static void searchFeatures(String query) throws IOException {
        String encodedQuery = java.net.URLEncoder.encode(query, "UTF-8");
        String url = NOMINATIM_API_BASE + "/search?q=" + encodedQuery + "&format=xml";
        logger.info("Searching for '" + query + "' via " + url);

        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("User-Agent", "LinkedOSM/1.0");

        int responseCode = connection.getResponseCode();
        if (responseCode == 200) {
            // TODO: Apply XSLT transformation to convert to RDF/XML
            // For now, just output the raw Nominatim XML
            try (InputStream input = connection.getInputStream()) {
                copyStream(input, System.out);
            }
        } else {
            System.err.println("Error: HTTP " + responseCode + " from Nominatim API");
            System.exit(1);
        }
    }

    private static void fetchMapData(String bbox) throws IOException {
        String url = OSM_API_BASE + "/map?bbox=" + bbox;
        logger.info("Fetching map data for bbox " + bbox + " from " + url);

        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("User-Agent", "LinkedOSM/1.0");

        int responseCode = connection.getResponseCode();
        if (responseCode == 200) {
            // TODO: Apply XSLT transformation to convert to RDF/XML
            // For now, just output the raw OSM XML
            try (InputStream input = connection.getInputStream()) {
                copyStream(input, System.out);
            }
        } else {
            System.err.println("Error: HTTP " + responseCode + " from OSM API");
            System.exit(1);
        }
    }

    private static void fetchPOIData(String bbox) throws IOException {
        // Convert bbox from "west,south,east,north" to "south,west,north,east" for Overpass API
        String[] coords = bbox.split(",");
        if (coords.length != 4) {
            System.err.println("Error: Invalid bbox format. Use: west,south,east,north");
            System.exit(1);
        }
        String overpassBbox = coords[1] + "," + coords[0] + "," + coords[3] + "," + coords[2]; // south,west,north,east

        // Overpass API query for nodes with amenity tags in bounding box
        String overpassQuery = "[out:xml][timeout:25];\n" +
                              "(\n" +
                              "  node[amenity](" + overpassBbox + ");\n" +
                              ");\n" +
                              "out meta;";

        String url = "https://overpass-api.de/api/interpreter";
        logger.info("Fetching POI data for bbox " + bbox + " from Overpass API");

        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        connection.setRequestProperty("User-Agent", "LinkedOSM/1.0");
        connection.setDoOutput(true);
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(30000);

        // Send Overpass query as POST data
        String postData = "data=" + java.net.URLEncoder.encode(overpassQuery, "UTF-8");
        try (OutputStream os = connection.getOutputStream()) {
            os.write(postData.getBytes("UTF-8"));
            os.flush();
        }

        int responseCode = connection.getResponseCode();
        if (responseCode == 200) {
            // TODO: Apply XSLT transformation to convert to RDF/XML
            // For now, just output the raw Overpass XML
            try (InputStream input = connection.getInputStream()) {
                copyStream(input, System.out);
            }
        } else {
            System.err.println("Error: HTTP " + responseCode + " from Overpass API");
            System.exit(1);
        }
    }

    private static void copyStream(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = input.read(buffer)) != -1) {
            output.write(buffer, 0, bytesRead);
        }
    }
}