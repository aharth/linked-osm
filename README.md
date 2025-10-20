# Linked OpenStreetMap

OpenStreetMap provides detailed geographic data as a collaborative, worldwide mapping project (https://www.openstreetmap.org/).

The linked-osm wrapper provides OpenStreetMap data as Linked Data (http://en.wikipedia.org/wiki/Linked_Data) using the [NeoGeo vocabulary](http://geovocab.org/).

## Build

To build the project, do:

```bash
$ mvn clean package war:war -Dcheckstyle.skip=true -DskipTests
```

To build with code formatting and style checks:

```bash
$ mvn clean package war:war
```

## Web Application

Deploy the following web application to Apache Tomcat 10+ (or other Jakarta EE compatible servlet container):

```
target/linked-osm-1.0.0-SNAPSHOT.war
```

Requires **Tomcat 10 or higher** for Jakarta EE support.

## API Endpoints

### Data Access

Access to OpenStreetMap nodes, ways, and relations:

- **Nodes**: `/node/{id}` - Individual OSM nodes
- **Ways**: `/way/{id}` - OSM ways (sequences of nodes)
- **Relations**: `/relation/{id}` - OSM relations (logical groupings)

Examples:
- [/node/17807753](http://localhost:8080/linked-osm/node/17807753)
- [/way/34148844](http://localhost:8080/linked-osm/way/34148844)
- [/relation/129836](http://localhost:8080/linked-osm/relation/129836)

### Search

Search for geographic features via Nominatim:

- **Endpoint**: `/search?q={query}`
- **Example**: `/search?q=London`

### Map Data

Get features within a bounding box:

- **Endpoint**: `/map?bbox={west},{south},{east},{north}`
- **Example**: `/map?bbox=-118.241,34.050,-118.240,34.051`

### Points of Interest

Get amenity nodes within a bounding box:

- **Endpoint**: `/poi?bbox={west},{south},{east},{north}`
- **Example**: `/poi?bbox=-118.9448,32.8007,-117.6462,34.8233`

### Geometry Data

Get WKT geometry from LinkedGeoData:

- **Endpoint**: `/geo/{type}/{id}`
- **Example**: `/geo/way/34148844`

## Data Sources

- **OSM API 0.6**: Primary data source at `http://api.openstreetmap.org/api/0.6`
- **Nominatim**: Search functionality via `http://nominatim.openstreetmap.org`
- **LinkedGeoData**: WKT geometry data from `http://linkedgeodata.org/`

## Architecture

The application is built as a Java servlet-based web service with the following components:

- **FeatureServlet**: Handles node, way, and relation requests
- **SearchServlet**: Handles search queries via Nominatim
- **MapServlet**: Handles bounding box queries
- **POIServlet**: Handles points of interest queries
- **GeometryServlet**: Provides geometry data from LinkedGeoData

All responses are transformed from OSM XML to RDF/XML using XSLT templates in the NeoGeo vocabulary.

## Requirements

- **Java 17** or higher
- **Maven 3.6** or higher
- **Servlet container** with Jakarta EE support (Tomcat 10+, Jetty 11+, etc.)

## Development

### Code Formatting

Format code using Spotless:

```bash
$ mvn spotless:apply
```

### Code Quality

Run Checkstyle analysis:

```bash
$ mvn checkstyle:check
```

### Testing

Run tests:

```bash
$ mvn test
```

## Background & Related Work

This project builds on research and tools for extracting and serving geographic data as Linked Data:

- **Administrative Boundaries**: Methodology based on [mySociety's approach to extracting administrative boundaries from OpenStreetMap](http://diy.mysociety.org/2012/06/23/extracting-administrative-boundaries-from-openstreetmap/)
- **MapIt Integration**: Originally designed to work with [MapIt Global](http://global.mapit.mysociety.org/) for boundary data (e.g., [area/29746.kml](http://global.mapit.mysociety.org/area/29746.kml))
- **Linked Data Applications**: Powers semantic web applications like [Urbanopoly venue data](http://swa.cefriel.it/linkeddata/page/urbanopoly/venue577)

## Future Enhancements

Potential extensions based on the project's original vision:

- **KML Export**: Add KML output format alongside RDF/XML (e.g., `/node/123.kml`, `/way/456.kml`)
- **Administrative Boundaries**: Enhanced support for administrative boundary extraction and serving
- **MapIt Integration**: Direct integration with MapIt services for boundary polygon data
- **Extended Vocabularies**: Support for additional Linked Data vocabularies beyond NeoGeo

## License

This project transforms and provides access to OpenStreetMap data, which is available under the [Open Database License](https://opendatacommons.org/licenses/odbl/).