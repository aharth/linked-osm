# Linked OpenStreetMap

OpenStreetMap provides detailed geographic data as a collaborative, worldwide mapping project (https://www.openstreetmap.org/).

The linked-osm wrapper provides OpenStreetMap data as Linked Data (http://en.wikipedia.org/wiki/Linked_Data) using the [NeoGeo vocabulary](http://geovocab.org/).

## Build

To build the project, do:

```bash
$ mvn clean package -Dcheckstyle.skip=true -DskipTests
```

To build with code formatting and style checks:

```bash
$ mvn clean package
```

## Web Application

Deploy the following web application to Apache Tomcat 10+ (or other Jakarta EE compatible servlet container):

```
target/linked-osm-1.0.0-SNAPSHOT.war
```

Requires **Tomcat 10 or higher** for Jakarta EE support.

## API Endpoints

### Feature data — nodes, ways, relations, changesets

Rich semantic descriptions using NeoGeo vocabulary and PROV-O provenance:

| Extension | Format | Content-Type |
|-----------|--------|-------------|
| `.rdf` | RDF/XML | `application/rdf+xml` |
| (none, `Accept: text/turtle`) | Turtle | `text/turtle` |
| `.json` | GeoJSON Feature | `application/geo+json` |
| `.gml` | WFS 2.0 GML | `application/gml+xml` |

Examples: `/node/{id}.rdf`, `/way/{id}.json`, `/relation/{id}.gml`, `/changeset/{id}.rdf`

The no-extension canonical URI (e.g. `/node/1`) serves the format matching the `Accept` header and includes a `Content-Location` header pointing to the extension URL for the representation returned.

### Geometry data

Bare coordinate geometry for map rendering, with no semantic enrichment:

- `/geo/osm/{type}/{id}.{format}` — from OSM API 0.6
- `/geo/overpass/{type}/{id}.{format}` — from Overpass API (preferred for complex multipolygon relations)

Supported formats: `json` (GeoJSON), `wkt` (Well-Known Text), `kml` (KML)

### Search

Nominatim place search; returns RDF/XML or GeoJSON:

- `/search?q={query}`, `/search.json?q={query}`

### Map data

OSM API map data for a bounding box; returns RDF/XML or GeoJSON:

- `/map?bbox={W,S,E,N}`, `/map.json?bbox={W,S,E,N}`

### Points of interest

Overpass amenity nodes in a bounding box; returns RDF/XML or GeoJSON:

- `/poi?bbox={W,S,E,N}`, `/poi.json?bbox={W,S,E,N}`

### Around

Overpass nodes within a radius of a point; returns RDF/XML or GeoJSON:

- `/around?lon={lon}&lat={lat}&radius={m}`, `/around.json?lon={lon}&lat={lat}&radius={m}`

### Tag

Tag statistics from [taginfo.openstreetmap.org](https://taginfo.openstreetmap.org/); returns RDF/XML or JSON-LD:

- `/tag/{key}`


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

## License

This project transforms and provides access to OpenStreetMap data, which is available under the [Open Database License](https://opendatacommons.org/licenses/odbl/).