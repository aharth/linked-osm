# OSM Data Model: Single ID, Different Geometry Types

## Overview

The OpenStreetMap (OSM) data model uses a unified approach where each geographic feature has a **single unique identifier** that represents both the feature's semantic properties and its spatial geometry. This differs from some GIS systems that separate feature identifiers from geometry identifiers.

## Core Elements and ID Structure

### Element Types

OSM organizes geographic data into six fundamental object types:

1. **Nodes** - Point features with coordinates
2. **Ways** - Linear or polygonal features composed of node sequences
3. **Relations** - Logical groupings of nodes, ways, and other relations
4. **Changesets** - Edit history containers (separate ID namespace)
5. **Users** - Contributors who create and modify OSM data
6. **User IDs** - Numeric identifiers for user accounts (separate from usernames)

### Unified ID System

Each OSM geographic element (node, way, relation) has:
- **Single unique numerical ID** within its element type
- **No separation** between "feature ID" and "geometry ID"
- **Cross-element references** using the same ID scheme

Changesets use a separate ID namespace for tracking edit operations.

### Global ID Numbering

OSM uses **globally unique, auto-incrementing integers** for element IDs:

- **Global Counter**: Each element type (node, way, relation) has a global counter maintained by the OSM database
- **Sequential Assignment**: New elements receive the next available ID (e.g., node 1, node 2, node 3...)
- **Limited Reuse**: While the API's auto-incrementing counter does not reuse IDs, deleted element IDs can technically be reused through manual operations or application errors, though this is rare and discouraged by the community's "Keep the history" principle [1][2]
- **Cross-Database Consistency**: All OSM mirrors and APIs use the same ID numbers
- **Historical Significance**: Lower IDs indicate earlier creation (node #1 was the very first OSM node)

**Examples of ID Progression:**
```
Node #1: The first node ever created - August 2005 (Note: This ID has been reused multiple
         times throughout OSM history, with the node appearing at different global locations [2])
Node #1668797722: Created much later (Burgwächter restaurant, Nuremberg)
Node #10858097177: Even more recent creation (another Burgwächter location)

Way #100: Early way (highway in Germany) - Ways started being used in 2005-2006
Way #23319192: Example of ID reuse - moved from Romania to Germany over time [2]
Relation #147: Early surviving relation (Tigris River multipolygon) - Relations introduced October 2007
```

**Historical Timeline:**
- **Nodes**: Started with OSM's launch in 2005 (node #1 created in August 2005)
- **Ways**: Introduced early in OSM development (2005-2006 era)
- **Relations**: Added later with API v0.5 in October 2007, replacing the earlier "segments" concept

This global numbering system provides identifiers that can be referenced across all OSM services and applications, though IDs should not be considered permanently bound to a single geographic concept due to the possibility of reuse after deletion [3].

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
- **API Access**: Available via `/changeset/{id}` endpoint with RDF and JSON formats
- **PROV-O Integration**: Represented as `prov:Activity` entities with provenance relationships

**Example:**
```
Changeset ID: 87654321
- Contains: Creation/modification of nodes 10858097177, 1668797722 and way 32113829
- User: nuremberg_mapper
- Comment: "Added Burgwächter restaurants and Palas building at Kaiserburg"
- No geometry: Changesets represent edit operations, not geographic features
- Access: /changeset/87654321.rdf or /changeset/87654321.json
```

### Users
- **Purpose**: Track contributors who create and modify OSM data
- **Identification**: String-based usernames (display names) plus numeric user IDs
- **No separate ID space**: Users are identified by their chosen usernames
- **Metadata**: Username, user ID, profile information, contribution history
- **API Access**: Available via `/user/{username}` endpoint with RDF and JSON formats
- **PROV-O Integration**: Represented as `prov:Agent` entities for attribution

**Example:**
```
User: nuremberg_mapper
- User ID: 12345
- Profile: https://www.openstreetmap.org/user/nuremberg_mapper
- Contributions: Multiple changesets, nodes, ways, and relations
- Access: /user/nuremberg_mapper.rdf or /user/nuremberg_mapper.json
```

## Implications for Linked Data and APIs

### Content Negotiation by Format

Since there's no separation between feature and geometry IDs, content negotiation can be handled through format specifications:

**Geographic Elements:**
- `/node/10858097177.rdf` → Returns the **same feature** (Burgwächter restaurant) in RDF/XML with semantic tags and PROV-O metadata
- `/node/10858097177.json` → Returns the **same feature** in GeoJSON with geometry coordinates
- `/way/32113829.rdf` → Returns the **same feature** (Palas building) in RDF/XML format with provenance
- `/way/32113829.json` → Returns the **same feature** in GeoJSON with polygon geometry
- Legacy format without extension defaults to RDF/XML for Linked Data services

**Provenance Entities:**
- `/changeset/87654321.rdf` → Returns changeset metadata in RDF/XML with PROV-O properties
- `/changeset/87654321.json` → Returns changeset metadata in JSON-LD format
- `/user/nuremberg_mapper.rdf` → Returns user/contributor information in RDF/XML as PROV-O Agent
- `/user/nuremberg_mapper.json` → Returns user information in JSON-LD format

### Persistent Identifiers

OSM IDs serve as **persistent identifiers** across different data representations:

- Same ID used in OSM API, Overpass API, and Linked Data services
- Enables consistent referencing across different applications and formats
- Supports data integration and cross-referencing
- **Provenance tracking**: Changeset and user identifiers provide full audit trail
- **PROV-O integration**: Links between elements, activities, and agents enable rich provenance queries

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

1. **Keep the History - OpenStreetMap Wiki**
   - [OSM Wiki: Keep the history](https://wiki.openstreetmap.org/wiki/Keep_the_history)
   - Community guidelines on preserving data history and avoiding problematic ID reuse

2. **Can a way's ID appear again after the way is deleted? - OSM Help**
   - [OSM Help Forum Discussion](https://help.openstreetmap.org/questions/80343/can-a-ways-id-appear-again-after-the-way-is-deleted)
   - Documentation of concrete examples of ID reuse (node #1, way #23319192)

3. **Permanent ID - OpenStreetMap Wiki**
   - [OSM Wiki: Permanent ID](https://wiki.openstreetmap.org/wiki/Permanent_ID)
   - Discussion of OSM ID limitations and the need for truly permanent identifiers

4. **OpenStreetMap Elements Documentation**
   - [OSM Wiki: Elements](https://wiki.openstreetmap.org/wiki/Elements)
   - Official documentation of nodes, ways, and relations

5. **OSM Data Model Technical Specification**
   - [The OSM Data Model](https://dev.overpass-api.de/overpass-doc/en/preface/osm_data_model.html)
   - Detailed technical specification from Overpass API documentation

6. **OSM API Documentation**
   - [API v0.6](https://wiki.openstreetmap.org/wiki/API_v0.6)
   - Official OpenStreetMap API reference

7. **LinkedGeoData Project**
   - [LinkedGeoData: A Core for a Web of Spatial Open Data](http://www.semantic-web-journal.net/content/linkedgeodata-core-web-spatial-open-data)
   - Academic paper on converting OSM data to RDF

8. **GeoSPARQL and Spatial RDF**
   - [GeoSPARQL - A Geographic Query Language for RDF Data](https://www.ogc.org/standards/geosparql)
   - OGC standard for representing spatial data in RDF

9. **OpenStreetMap Data Structure Analysis**
   - [OSM Data Structures and Relationships](https://archive.flossmanuals.net/openstreetmap/the-osm-data-model.html)
   - Comprehensive analysis of OSM's topological data model

## Key Findings

1. **Unified Identity**: OSM's single ID per element simplifies data integration and persistent referencing
2. **Geometry Embedding**: Spatial data is inherent to the element, not stored separately
3. **Topological Integrity**: Shared node references maintain spatial relationships

## Enhanced Provenance Model

The linked-osm implementation extends the basic OSM data model with comprehensive provenance tracking using the PROV-O vocabulary:

### Provenance Entities

**Geographic Elements** (`prov:Entity`):
- Nodes, ways, and relations with enhanced metadata
- Links to generating activities and responsible agents
- Version and timestamp information

**Activities** (`prov:Activity`):
- Changesets as edit operations with temporal bounds
- Associated software agents (editors) and human contributors
- Geographic scope and change descriptions

**Agents** (`prov:Agent`):
- Human contributors (OSM users) with attribution
- Software agents (editing tools) with version information
- Organizational agents (mapping projects, data imports)

### Provenance Relationships

```
Node/Way/Relation --prov:wasGeneratedBy--> Changeset --prov:wasAssociatedWith--> User
                  --prov:wasAttributedTo--> User
                  --prov:hadPrimarySource--> OSM API URL
```

### Enhanced Key Findings

4. **Format Flexibility**: Same ID can serve multiple representation formats (RDF, GeoJSON, XML)
5. **Linked Data Compatibility**: Direct mapping from OSM IDs to RDF URIs enables seamless Linked Data integration
6. **Comprehensive Provenance**: Full audit trail from data creation through transformation
7. **Attribution Support**: Clear links to contributors and data sources
8. **Quality Assessment**: Enables evaluation of data freshness and contributor expertise
9. **Interoperability**: Standard PROV-O vocabulary supports tool integration

This unified approach with enhanced provenance makes OSM particularly well-suited for Linked Data applications where persistent, dereferenceable identifiers and comprehensive data lineage are essential for building trustworthy semantic web applications.