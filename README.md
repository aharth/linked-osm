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

Access to OpenStreetMap nodes, ways, and relations in multiple formats:

- **RDF/XML format**: `/node/{id}.rdf`, `/way/{id}.rdf`, `/relation/{id}.rdf` - Linked Data representation using NeoGeo vocabulary
- **GeoJSON format**: `/node/{id}.json`, `/way/{id}.json`, `/relation/{id}.json` - Geometry data optimized for web mapping (Leaflet, etc.)
- **Legacy format**: `/node/{id}`, `/way/{id}`, `/relation/{id}` - Defaults to RDF/XML


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

GeoJSON geometry is now available directly via the `.json` format endpoints above.

Legacy geometry endpoint (deprecated):
- **Endpoint**: `/geo/{type}/{id}` - WKT geometry (use `.json` format instead)


## Background

Linked OpenStreetmap provides OpenStreetMap data as Linked Data using XSLT transformations to convert OSM XML into RDF/XML format.

## Related Work

We initially attempted to provide geometry files via [MapIt Global](http://global.mapit.mysociety.org/), however there is a mapping between OSM ids and their id scheme missing. We now provide geometries in WKT format from [LinkedGeoData](http://linkedgeodata.org/) (however, relations do not seem to be supported by LinkedGeoData).

## Future Enhancements

Potential extensions based on the project's original vision:

- **KML Export**: Add KML output format alongside RDF/XML (e.g., `/node/123.kml`, `/way/456.kml`)
- **Administrative Boundaries**: Enhanced support for administrative boundary extraction and serving
- **Extended Vocabularies**: Support for additional Linked Data vocabularies beyond NeoGeo

## License

This project transforms and provides access to OpenStreetMap data, which is available under the [Open Database License](https://opendatacommons.org/licenses/odbl/).