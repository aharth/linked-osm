# Changelog

All notable changes to the OpenStreetMap Linked Data Wrapper project will be documented in this file.

## [2026-02-26] caching
- `FeatureServlet`: in-memory Caffeine cache of upstream OSM XML keyed by URL (2 GB weight limit, 24 h expiry); at most one upstream fetch per URL at a time — concurrent requests for the same URL join the in-flight `CompletableFuture` and share the result
- `relation.xsl`: fixed XSLT 2.0 multi-valued sequence bug where `relation/@id` on a `/full` response returned all relation IDs space-separated, producing an invalid URI in `prov:used`; ID now passed as `element-id` XSLT parameter from `FeatureServlet`
- Dead code removed from `Listener`: `ISO8601`, `FACTORY`, `TOC` constants; `XMLOutputFactory` init; JSR-107 (`cache-ri-impl`) replaced by Caffeine
- PROV-O: upstream byte size (`dcat:byteSize`) added to `prov:used` entity in all RDF stylesheets, populated from `Content-Length` response header without buffering
- `HttpClientUtil.errorStatus()`: maps `HttpTimeoutException` → 504, other `IOException` → 500; timeouts bumped (connect 15 s, response 120 s); `map.js` client timeout 130 s (slightly above server) so HTTP status codes reach the browser
- `faq.html`: added caching section; timeout section updated to cover 504

## [2026-02-26]
- Code cleanup: `HttpClientUtil.readToString(InputStream)` replaces seven private `readInputStream()` copies across servlets; `AcceptHeader.prefersJson(servletPath, acceptHeader)` replaces four private `isJsonRequested()` copies; `HttpClientUtil.fetchUrl()` timeout parameters removed (were unused)
- Dead code removed: six unused timeout constants from `ApiConstants`; `Listener.GEO` constant and `geo.xsl` loading (no servlet ever retrieved it); `ChangesetServlet` JSON stub (fetched upstream then returned 406 — now returns 406 immediately before any API call); three dead private methods in `MultipolygonHandler` (`fetchFromOSMAPI`, `extractWayCoordinates`, `extractNodeCoordinate`)
- `GeometryOSMServlet`, `GeometryOverpassServlet`: Accept header content negotiation replaced with `AcceptHeader.parse()` + `maxQ()` (was `accept.contains()` string check)


- `RdfFilter`: `Vary: Accept` header added to all responses; `Content-Location` header added for no-extension canonical URIs pointing to the served representation (e.g. `/node/1` → `Content-Location: /node/1.ttl` or `.rdf` or `.json`); `test_endpoints.sh`: light/heavy modes (`--light` default, `--heavy` with 10 s delay); `README.md`: Testing section documents prerequisites and both modes
- `examples.json`: new single source of truth for all example inputs (features, search, map bboxes, around points); `relation/51477` (Germany) added to feature examples
- `index.html`: all five example `<select>` dropdowns now populated dynamically via `fetch('examples.json')` instead of hardcoded `<option>` elements
- `test_endpoints.sh`: new endpoint smoke-test script; reads `examples.json` via `jq`; validates GeoJSON (`jq empty`), RDF/XML (`xmllint`, `rapper`), Turtle (`rapper`), GML (`xmllint`) for every feature; also tests search/map/around endpoints; includes named cases for `relation/51477`, `relation/71525` (both serialisations), and Westminster `around` with `view.html` HTTP 200 check


- GML geometry encoding: `locn:geometry` in `node.xsl` and `way.xsl` now uses inline GML XML literals (`rdf:parseType="Literal"`) instead of WKT typed literals; `gml:Point` for nodes, `gml:LineString`/`gml:Polygon` for ways
- `application/gml+xml` content type for feature URIs: new `node-gml.xsl`, `way-gml.xsl`, `relation-gml.xsl` emit WFS 2.0 FeatureCollections with `osm:node`/`osm:way`/`osm:relation` members and GML geometry; served via `.gml` extension or `Accept: application/gml+xml` header


- `node.xsl`: removed `/geo/osm/node/` and `/geo/overpass/node/` links from `#geo` (point is self-contained); `relation.xsl`: `geom:geometry` property and `#geo` block suppressed when relation has no node coordinates (thematic/organisational relations)
- `AcceptHeader` utility: proper q-value-aware Accept header parsing in all servlets; `RdfFilter` now serves RDF/XML when client explicitly prefers it over Turtle; `FeatureServlet` also accepts `application/json`
- XSLT: `#osmwrap` agent URI changed to `/#osmwrap` for a single canonical URI across all endpoints
- linked-inspire `DispatcherServlet`: WFS response content-type corrected to `application/geo+json`; 406 returned when client rejects GeoJSON
- `index.html`: node/way/relation inputs load `/{type}/{id}` via content negotiation (`Accept: application/geo+json`); query forms use canonical URLs without `.json` suffix; ODbL sentence moved to `faq.html`; limit inputs removed; Santa Monica Third Street added to examples; status area simplified (no font/colour overrides, no bbox display)
- `map.js`: bbox query rectangle overlay (grey dashed); URI always visible during and after fetch (spinner → link/plain text); Leaflet attribution control disabled

## [2026-02-25]
- Fixed Turtle base URI (`RdfFilter`): use actual request URL honouring `X-Forwarded-Proto`/`X-Forwarded-Host`; removed BASE-line stripping so `<#id>` and `</changeset/…>` resolve correctly
- Fixed thread-safety: `Listener` now stores `Templates` (thread-safe) instead of `Transformer`; all servlets create a per-request `Transformer` via `tmpl.newTransformer()`
- Way and relation RDF now fetched via `/full` endpoint so XSLT receives inline node coordinates
- `way.xsl`: added centroid (`geo:lat`/`geo:long`) and WKT (`locn:geometry`) to `#geo` resource from node coordinates; detects closed ways as POLYGON
- `relation.xsl`: added centroid to `#geo` resource from all member nodes in `/full` response
- `map.js`: popup `osm_id` link now goes to `/{type}/{id}` (plain, with separate rdf/json links); GeoJSON-link panel shows feature URL instead of geometry URL for `/geo/osm/…` and `/geo/overpass/…` endpoints
- `view.html`: primary link is `/{type}/{id}` (no `.rdf` suffix); removed redundant `.rdf` link
- `faq.html`: added `#hash-uris` section explaining `#id` (spatial:Feature) and `#geo` (geom:Geometry) hash-URI pattern

## [2026-02-24]
- Embedded map widget on index.html: OSM tile base layer, coloured feature overlays (polygon/line/point), click-to-highlight with popup, bbox display, stale-request cancellation
- Added shared `map.js` and `map.css` (aligned with linked-inspire): `initOsmMap`, `loadGeoJsonUrl`, `getBboxString`, `escHtml`
- index.html: converted from XHTML 1.0 to HTML5; example links load features directly onto embedded map; Search/Map/POI/Around forms gain "Show on map" button
- view.html: refactored from full-screen layout to embedded widget (48em × 27em); uses shared map.js for feature rendering, popup, and highlight
- index.html: renamed "Features" section to "Nodes, ways and relations" to avoid confusion with RDF feature vs. geometry distinction; moved element example list to FAQ

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
