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

## Tag Information

`/tag/{key}` (e.g. `/tag/oneway`) returns a SKOS description of the tag key with usage statistics from [taginfo.openstreetmap.org](https://taginfo.openstreetmap.org/).
`/tag/{key}={value}` (e.g. `/tag/tower:construction=All_India_radio_tower`) returns a SKOS description of that specific key–value combination with its own usage statistics and a `skos:broader` link back to the key.
Keys with a colon (e.g. `/tag/tower:construction`) are namespace variants of a base key (e.g. `/tag/tower`); the base key lists them as `skos:narrower` concepts.
OSM tags are free-form key–value pairs: some keys act as flags whose presence alone carries meaning (e.g. `oneway=yes`, where the value is essentially boolean), some have a controlled vocabulary of values (e.g. `amenity=cafe`, `natural=water`), some carry free text (e.g. `name=…`, `description=…`), and some use a colon-namespaced key for language or role variants (e.g. `name:en=…`, `tower:construction=…`).
Tag descriptions link to the corresponding [OpenStreetMap wiki](https://wiki.openstreetmap.org/) page.

## OSM Element ID Stability

OpenStreetMap element IDs (node, way, relation) are **not stable over time**: elements can be deleted and their numeric IDs reassigned to new features, or split/merged operations produce new IDs.
See the [OSM wiki on element IDs](https://wiki.openstreetmap.org/wiki/Elements) and the [Linked Data best practices note on URI stability](https://www.w3.org/TR/cooluris/) for background.

