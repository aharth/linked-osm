# OSM Data Model: Single ID, Different Geometry Types

## Overview

The OpenStreetMap (OSM) data model uses a unified approach where each geographic feature has a **single unique identifier** that represents both the feature's semantic properties and its spatial geometry. This differs from some GIS systems that separate feature identifiers from geometry identifiers.

## Core Elements and ID Structure

### Element Types

OSM organizes geographic data into four fundamental object types:

1. **Nodes** - Point features with coordinates
2. **Ways** - Linear or polygonal features composed of node sequences
3. **Relations** - Logical groupings of nodes, ways, and other relations
4. **Changesets** - Edit history containers (separate ID namespace)

### Unified ID System

Each OSM geographic element (node, way, relation) has:
- **Single unique numerical ID** within its element type
- **No separation** between "feature ID" and "geometry ID"
- **Cross-element references** using the same ID scheme

Changesets use a separate ID namespace for tracking edit operations.

## Geometry Representation by Element Type

### Nodes
- **Feature**: Point with semantic tags (amenity=restaurant, name="Burgwächter")
- **Geometry**: Single coordinate pair (latitude, longitude)
- **Single entity**: ID represents both the feature AND its point geometry

**Examples:**
```
Node ID: 10858097177
- Feature: Burgwächter restaurant in Nuremberg with amenity and name tags
- Geometry: Point at specific coordinates in Nuremberg

Node ID: 1668797722
- Feature: Another Burgwächter restaurant location in Nuremberg
- Geometry: Point at different coordinates, same city
```

### Ways
- **Feature**: Linear/polygon feature with semantic tags (building=yes, historic=castle)
- **Geometry**: Ordered sequence of node references → resolves to coordinate sequence
- **Single entity**: ID represents both the feature AND its linear/polygon geometry

**Example:**
```
Way ID: 32113829
- Feature: Palas building at Kaiserburg Nuremberg with building and historic tags
- Geometry: Polygon formed by sequence of nodes defining the building footprint
```

### Relations
- **Feature**: Logical grouping with semantic tags (type=boundary, landuse=forest)
- **Geometry**: Complex - may or may not have interpretable geometry depending on type
- **Single entity**: ID represents the logical relationship (may have derived geometry)

**Examples:**
```
Relation ID: 71525
- Feature: Paris administrative boundary with admin_level and name tags
- Geometry: Multipolygon derived from member ways defining city limits

Relation ID: 13986332
- Feature: Sebalder Reichswald forest with landuse and natural tags
- Geometry: Multipolygon derived from member ways defining forest boundaries
```

### Changesets
- **Purpose**: Track groups of related edits made by users
- **Scope**: Container for multiple element modifications in a single editing session
- **Separate ID space**: Changesets have their own numerical ID sequence, independent of element IDs
- **Metadata**: Include timestamp, user, comment, and bounding box of changes

**Example:**
```
Changeset ID: 87654321
- Contains: Creation/modification of nodes 10858097177, 1668797722 and way 32113829
- User: nuremberg_mapper
- Comment: "Added Burgwächter restaurants and Palas building at Kaiserburg"
- No geometry: Changesets represent edit operations, not geographic features
```

## Implications for Linked Data and APIs

### Content Negotiation by Format

Since there's no separation between feature and geometry IDs, content negotiation can be handled through format specifications:

- `/node/10858097177.rdf` → Returns the **same feature** (Burgwächter restaurant) in RDF/XML with semantic tags
- `/node/10858097177.json` → Returns the **same feature** in GeoJSON with geometry coordinates
- `/way/32113829.rdf` → Returns the **same feature** (Palas building) in RDF/XML format
- `/way/32113829.json` → Returns the **same feature** in GeoJSON with polygon geometry
- Legacy format without extension defaults to RDF/XML for Linked Data services

### Persistent Identifiers

OSM IDs serve as **persistent identifiers** across different data representations:

- Same ID used in OSM API, Overpass API, and Linked Data services
- Enables consistent referencing across different applications and formats
- Supports data integration and cross-referencing

## Comparison with Other Systems

### Traditional GIS Approach
Many GIS systems separate:
- **Feature Table**: `feature_id`, attributes
- **Geometry Table**: `geometry_id`, spatial_data
- **Relationship**: `feature_id` → `geometry_id`

### OSM Approach
OSM unifies:
- **Single ID**: Represents complete geographic entity
- **Embedded Geometry**: Coordinates embedded within element structure
- **Topological Relationships**: Shared node references create topology

## References

1. **OpenStreetMap Elements Documentation**
   - [OSM Wiki: Elements](https://wiki.openstreetmap.org/wiki/Elements)
   - Official documentation of nodes, ways, and relations

2. **OSM Data Model Technical Specification**
   - [The OSM Data Model](https://dev.overpass-api.de/overpass-doc/en/preface/osm_data_model.html)
   - Detailed technical specification from Overpass API documentation

3. **OSM API Documentation**
   - [API v0.6](https://wiki.openstreetmap.org/wiki/API_v0.6)
   - Official OpenStreetMap API reference

4. **LinkedGeoData Project**
   - [LinkedGeoData: A Core for a Web of Spatial Open Data](http://www.semantic-web-journal.net/content/linkedgeodata-core-web-spatial-open-data)
   - Academic paper on converting OSM data to RDF

5. **GeoSPARQL and Spatial RDF**
   - [GeoSPARQL - A Geographic Query Language for RDF Data](https://www.ogc.org/standards/geosparql)
   - OGC standard for representing spatial data in RDF

6. **OpenStreetMap Data Structure Analysis**
   - [OSM Data Structures and Relationships](https://archive.flossmanuals.net/openstreetmap/the-osm-data-model.html)
   - Comprehensive analysis of OSM's topological data model

## Key Findings

1. **Unified Identity**: OSM's single ID per element simplifies data integration and persistent referencing
2. **Geometry Embedding**: Spatial data is inherent to the element, not stored separately
3. **Topological Integrity**: Shared node references maintain spatial relationships
4. **Format Flexibility**: Same ID can serve multiple representation formats (RDF, GeoJSON, XML)
5. **Linked Data Compatibility**: Direct mapping from OSM IDs to RDF URIs enables seamless Linked Data integration

This unified approach makes OSM particularly well-suited for Linked Data applications where persistent, dereferenceable identifiers are essential for building the semantic web of geographic information.