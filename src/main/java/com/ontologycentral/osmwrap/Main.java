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


    public static void main(String[] args) {
        Options options = createOptions();
        CommandLineParser parser = new DefaultParser();

        try {
            CommandLine cmd = parser.parse(options, args);

            if (cmd.hasOption("help") || args.length == 0) {
                printHelp(options);
                return;
            }

            if (cmd.hasOption("version")) {
                printVersion();
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
            } else if (cmd.hasOption("changeset")) {
                String changesetId = cmd.getOptionValue("changeset");
                fetchChangeset(changesetId);
            } else if (cmd.hasOption("tag")) {
                String tagKey = cmd.getOptionValue("tag");
                fetchTagInfo(tagKey);
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

        options.addOption(Option.builder("c")
                .longOpt("changeset")
                .hasArg()
                .argName("ID")
                .desc("Fetch OSM changeset by ID")
                .build());

        options.addOption(Option.builder("t")
                .longOpt("tag")
                .hasArg()
                .argName("KEY")
                .desc("Fetch SKOS vocabulary for OSM tag key (e.g., amenity, building)")
                .build());

        options.addOption(Option.builder("h")
                .longOpt("help")
                .desc("Show this help message")
                .build());

        options.addOption(Option.builder("v")
                .longOpt("version")
                .desc("Show version information")
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
                "  linked-osm --changeset 137\n" +
                "  linked-osm --search \"London\"\n" +
                "  linked-osm --map \"-118.241,34.050,-118.240,34.051\"\n" +
                "  linked-osm --poi \"-118.9448,32.8007,-117.6462,34.8233\"\n" +
                "  linked-osm --tag amenity\n" +
                "  linked-osm --tag name:en\n\n" +
                "For more information, visit: https://github.com/aharth/linked-osm");
    }

    private static void printVersion() {
        System.out.println(BuildInfo.getName() + " " + BuildInfo.getVersion());
        System.out.println("User-Agent: " + BuildInfo.getUserAgent());
        System.out.println("Build timestamp: " + BuildInfo.getBuildTimestamp());
        System.out.println();
        System.out.println("OpenStreetMap Linked Data Command Line Tool");
        System.out.println("Project: https://github.com/aharth/linked-osm");
    }

    private static void fetchOsmFeature(String type, String id) throws IOException {
        String url = UrlBuilder.buildFeatureUrl(type, id);
        logger.info("Fetching " + type + " " + id + " from " + url);

        HttpURLConnection connection = HttpClientUtil.createConnection(url);
        connection.setRequestMethod("GET");

        try {
            HttpClientUtil.checkResponseCode(connection, type + " " + id);
            // TODO: Apply XSLT transformation to convert to RDF/XML
            // For now, just output the raw OSM XML
            try (InputStream input = connection.getInputStream()) {
                HttpClientUtil.copyStream(input, System.out);
            }
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void searchFeatures(String query) throws IOException {
        String url = UrlBuilder.buildSearchUrl(query);
        logger.info("Searching for '" + query + "' via " + url);

        HttpURLConnection connection = HttpClientUtil.createConnection(url, ApiConstants.DEFAULT_CONNECT_TIMEOUT, ApiConstants.SEARCH_READ_TIMEOUT);
        connection.setRequestMethod("GET");

        try {
            HttpClientUtil.checkResponseCode(connection, "Nominatim search");
            // TODO: Apply XSLT transformation to convert to RDF/XML
            // For now, just output the raw Nominatim XML
            try (InputStream input = connection.getInputStream()) {
                HttpClientUtil.copyStream(input, System.out);
            }
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void fetchMapData(String bbox) throws IOException {
        String url = UrlBuilder.buildMapUrl(bbox);
        logger.info("Fetching map data for bbox " + bbox + " from " + url);

        HttpURLConnection connection = HttpClientUtil.createConnection(url);
        connection.setRequestMethod("GET");

        try {
            HttpClientUtil.checkResponseCode(connection, "map data");
            // TODO: Apply XSLT transformation to convert to RDF/XML
            // For now, just output the raw OSM XML
            try (InputStream input = connection.getInputStream()) {
                HttpClientUtil.copyStream(input, System.out);
            }
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void fetchPOIData(String bbox) throws IOException {
        String overpassQuery = UrlBuilder.buildOverpassPOIQuery(bbox);
        logger.info("Fetching POI data for bbox " + bbox + " from Overpass API");

        HttpURLConnection connection = HttpClientUtil.createConnection(ApiConstants.OVERPASS_API_BASE, ApiConstants.DEFAULT_CONNECT_TIMEOUT, ApiConstants.POI_READ_TIMEOUT);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        connection.setDoOutput(true);

        // Send Overpass query as POST data
        String postData = "data=" + java.net.URLEncoder.encode(overpassQuery, "UTF-8");
        try (OutputStream os = connection.getOutputStream()) {
            os.write(postData.getBytes("UTF-8"));
            os.flush();
        }

        try {
            HttpClientUtil.checkResponseCode(connection, "Overpass API");
            // TODO: Apply XSLT transformation to convert to RDF/XML
            // For now, just output the raw Overpass XML
            try (InputStream input = connection.getInputStream()) {
                HttpClientUtil.copyStream(input, System.out);
            }
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void fetchChangeset(String changesetId) throws IOException {
        String url = UrlBuilder.buildChangesetUrl(changesetId);
        logger.info("Fetching changeset " + changesetId + " from " + url);

        HttpURLConnection connection = HttpClientUtil.createConnection(url);

        try {
            HttpClientUtil.checkResponseCode(connection, "changeset " + changesetId);
            try (InputStream input = connection.getInputStream()) {
                HttpClientUtil.copyStream(input, System.out);
            }
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void fetchTagInfo(String tagKey) throws IOException {
        String tagUrl = "https://taginfo.openstreetmap.org/api/4/key/overview?key=" + java.net.URLEncoder.encode(tagKey, "UTF-8");
        logger.info("Fetching tag info for '" + tagKey + "' from " + tagUrl);

        TaginfoConverter converter = new TaginfoConverter();

        try {
            // Fetch key info
            String keyInfo = converter.fetchKeyInfo(tagKey);

            // Fetch key values
            String values = converter.fetchKeyValues(tagKey);

            // For base keys (no colon), fetch namespace variants
            String namespacesJson = "";
            if (!tagKey.contains(":")) {
                namespacesJson = converter.fetchAllKeys();
            }

            // Convert to SKOS JSON-LD (native format from Taginfo API)
            String output = converter.convertToSKOSJson(tagKey, keyInfo, values, namespacesJson, "/tag/");
            System.out.println(output);

        } catch (IOException e) {
            System.err.println("Error fetching tag info: " + e.getMessage());
            System.exit(1);
        } catch (RuntimeException e) {
            System.err.println("Error processing tag: " + e.getMessage());
            System.exit(1);
        }
    }

}