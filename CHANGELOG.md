# Changelog

All notable changes to the OpenStreetMap Linked Data Wrapper project will be documented in this file.

## [2026-02-24]
- Embedded map widget on index.html: OSM tile base layer, coloured feature overlays (polygon/line/point), click-to-highlight with popup, bbox display, stale-request cancellation
- Added shared `map.js` and `map.css` (aligned with linked-inspire): `initOsmMap`, `loadGeoJsonUrl`, `getBboxString`, `escHtml`
- index.html: converted from XHTML 1.0 to HTML5; example links load features directly onto embedded map; Search/Map/POI/Around forms gain "Show on map" button
- view.html: refactored from full-screen layout to embedded widget (48em × 27em); uses shared map.js for feature rendering, popup, and highlight

## [2026-02-16]
- Added GeoJSON output for `/search`, `/poi`, `/around`, `/map` endpoints via `.json` suffix or Accept header
- Map GeoJSON includes ways as LineStrings/Polygons (not just nodes), with tag properties
- view.html: clickable feature popups with links to RDF/JSON/OSM, highlight on click, bbox rectangle overlay
- Improved upstream error reporting: servlets now pass through actual error body text

## [2026-02-15]
- Added W3C PROV provenance to search/poi/map stylesheets, blank node result wrappers with rdfs:seeAlso, encode-for-uri for whitespace in URIs, XSLT 2.0
- Added `/around` endpoint for radius-based spatial search via Overpass API (lon, lat, optional radius in meters)

## [2025-11-25]
- Implemented proper multipolygon handling with outer/inner rings for GeoJSON, WKT, and KML output (Ring, MultipolygonGeometry, MultipolygonHandler classes)
- Added `/tag/{key}` endpoints for SKOS vocabulary representations of OSM tags with 2-layer hierarchy and namespace variant filtering (1000+ usage threshold)

## [2025-11-12]
- Split geometry endpoints into separate servlets: `/geo/overpass/*` (simplified, fast) and `/geo/osm/*` (complete, detailed)
- RDF features now link both geometry sources; both servlets support GeoJSON, WKT, and KML formats

## [2025-10-28]
- Added foaf:page links from hash URI resources to .rdf and .json document representations, and documented URI patterns in index.html

## [2025-10-22]
- Refactor HTTP client code to eliminate duplication between CLI and webapp

## [2025-10-21]
- Major refactoring, current version of JDK and libraries
- Added CLI tool for command-line access to OSM data
- Replaced deprecated MapQuest XAPI with Overpass API for POI functionality
- Added PROV-O provenance tracking with changeset/user attribution via blank node FOAF agents

## [2025-10-20]
- Migrated from Google App Engine to standard Maven build system
- Updated from javax.servlet to Jakarta EE

## [2012-2013]
- Added GeoJSON geometry support via Overpass API integration
- POI functionality using MapQuest XAPI

## [2009-2011]
- Initial version with core OSM API integration
- Node, Way, Relation, and Search functionality
- XSLT transformations to RDF/XML using NeoGeo vocabulary
