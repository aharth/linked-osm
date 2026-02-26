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

Get geometry data for OSM features in multiple formats from linked geometry sources:

- **Endpoint**: `/geo/osm/{type}/{id}.{format}` - Geometry from OpenStreetMap API
- **Endpoint**: `/geo/overpass/{type}/{id}.{format}` - Geometry from Overpass API (useful for complex geometries)

**Supported formats:**
- `json` (default, GeoJSON) - `application/geo+json`
- `wkt` (Well-Known Text) - `application/wkt`
- `kml` (Keyhole Markup Language) - `application/vnd.google-earth.kml+xml`

**Content Negotiation:**

Specify format using file extension:
```
/geo/osm/node/1.json    # GeoJSON
/geo/osm/node/1.wkt     # WKT format
/geo/osm/node/1.kml     # KML format
```

Or via HTTP Accept header:
```
Accept: application/geo+json                          # GeoJSON
Accept: application/wkt                               # WKT
Accept: application/vnd.google-earth.kml+xml          # KML
```


## Testing

`test_endpoints.sh` runs HTTP smoke-tests against a deployed instance.
It reads example data from `src/main/webapp/examples.json` (single source of truth).

### Prerequisites

```
curl  jq  xmllint (libxml2-utils)  rapper (raptor2-utils)  python3
```

### Light mode (default)

Checks one node, one way, one relation, one search, one map bbox, and the view page.
No Overpass calls, no inter-request delay — completes in a few seconds.

```bash
./test_endpoints.sh                                    # against osmwrap.ontologycentral.com
./test_endpoints.sh http://localhost:8080/linked-osm   # against a local instance
```

### Heavy mode

Full loop over all entries in `examples.json` × all formats (GeoJSON, RDF/XML, Turtle, GML),
plus Overpass `/around` endpoints and named edge-case relations.
Defaults to a 10-second delay between requests to stay within Overpass rate limits.

```bash
./test_endpoints.sh --heavy
./test_endpoints.sh --heavy http://localhost:8080/linked-osm
DELAY=5 ./test_endpoints.sh --heavy                   # override delay
```

### Exit code

`0` when all tests pass, `1` if any fail.
Each test prints `PASS` or `FAIL`; failures include the error output from the tool.

## Background

Linked OpenStreetmap provides OpenStreetMap data as Linked Data using XSLT transformations to convert OSM XML into RDF/XML format.

## Related Work

Geometry data is now provided directly via GeoJSON format endpoints (`.json` format) using the Overpass API, which provides complete geometry information for nodes, ways, and relations.

## Future Enhancements

Potential extensions based on the project's original vision:

- **KML Export**: Add KML output format alongside RDF/XML (e.g., `/node/123.kml`, `/way/456.kml`)
- **Administrative Boundaries**: Enhanced support for administrative boundary extraction and serving
- **Extended Vocabularies**: Support for additional Linked Data vocabularies beyond NeoGeo

## License

This project transforms and provides access to OpenStreetMap data, which is available under the [Open Database License](https://opendatacommons.org/licenses/odbl/).