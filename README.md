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


### Search

Search for geographic features via Nominatim:

- **Endpoint**: `/search?q={query}`

### Map Data

Get features within a bounding box:

- **Endpoint**: `/map?bbox={west},{south},{east},{north}`

### Points of Interest

Get amenity nodes within a bounding box:

- **Endpoint**: `/poi?bbox={west},{south},{east},{north}`

### Geometry Data

Get WKT geometry from LinkedGeoData:

- **Endpoint**: `/geo/{type}/{id}`


## Background

Linked OpenStreetmap provides OpenStreetMap data as Linked Data using XSLT transformations to convert OSM XML into RDF/XML format.

## Future Enhancements

Potential extensions based on the project's original vision:

- **KML Export**: Add KML output format alongside RDF/XML (e.g., `/node/123.kml`, `/way/456.kml`)
- **Administrative Boundaries**: Enhanced support for administrative boundary extraction and serving
- **Extended Vocabularies**: Support for additional Linked Data vocabularies beyond NeoGeo

## License

This project transforms and provides access to OpenStreetMap data, which is available under the [Open Database License](https://opendatacommons.org/licenses/odbl/).