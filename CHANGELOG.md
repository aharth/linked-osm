# Changelog

All notable changes to the OpenStreetMap Linked Data Wrapper project will be documented in this file.

## [2026-03-15]
- **OSM API bbox endpoint removed**: `MapServlet`, `buildMapUrl`, `--map` CLI flag, and `/osm/map*` web.xml entries deleted; `osmMapToGeoJson` replaced by `overpassFeaturesToGeoJson` (nodes→Point, ways→LineString/Polygon with inline `out geom` coords, relations→Polygon/MultiPolygon via `MultipolygonHandler.buildFromSegments` or MultiLineString for routes)
- **`OverpassFeaturesServlet`**: fixed to use `Listener.MAP` template and `overpassFeaturesToGeoJson`; Overpass query switched from two-pass `out body; >; out skel qt` to `out geom`
- **POI filter**: `buildOverpassPOIQuery` gains a `filter` param (`key=value` → exact match, `key` → key-exists, blank → default amenity/shop/tourism regex); `index.html` POI row adds a filter text input
- **4-column grid fixed**: Overpass bbox controls moved to Overpass column (col 3); OSM API map `<dl>` removed from UI
- **EOX OSM tile layer**: added to `OSM_TILE_LAYERS` as a WMS entry; `switchOsmTileLayer` branches on `layer.type === 'wms'` to use `L.tileLayer.wms`; PNG link uses new `_centerTileWmsUrl` (WMS GetMap for center tile bbox)
- **Button labels**: Pan (viewport navigation), Switch (tile layer), Load bbox (Overpass features/POI/around), Load (feature by ID), Search (Nominatim)
- **`faq.html`**: URI paths corrected throughout (`/osm/node/`, `/nominatim/search`, `/overpass/poi` etc.); example link sections removed; questions reordered into groups (data model, URI patterns, geometry building, infrastructure)
- **Turtle-native XSLT output**: `node.xsl`, `way.xsl`, `relation.xsl` now emit Turtle (`xsl:output method="text"`) instead of RDF/XML; all OSM tag keys emitted as full URIs `<https://osmwrap.ontologycentral.com/tag/{key}>` — colons in keys (e.g. `addr:street`, `name:en`) are valid in URI angle-bracket notation and require no workaround
- **Blank node workaround removed**: the `osm:tag [ osm:key "..." ; osm:value "..." ]` pattern for colon-containing keys is gone
- **`RdfFilter` updated**: detects `text/turtle` servlet output; passes through for Turtle clients, converts Turtle→RDF/XML via Jena for RDF/XML clients; `.rdf` suffix now forces RDF/XML (analogous to `.ttl` forcing Turtle)
- **FeatureServlet / OverpassElementServlet**: content type changed from `application/rdf+xml` to `text/turtle`
- **`/tag/{key}={value}`**: `TagServlet` now routes `key=value` paths to a new `handleTagValueRequest`; `TaginfoConverter` fetches Taginfo `/api/4/tag/overview?key=K&value=V` and emits a SKOS description with `skos:broader /tag/{key}` and `rdfs:seeAlso` to the OSM wiki `Tag:` page
- **`TaginfoConverter` URI encoding**: tag values in `rdf:resource`/`rdf:about` now percent-encoded via `uriEncodeValue()` — fixes 500 on keys with space-containing values (e.g. `communication:microwave=emergency services`)
- **`RdfFilter` Jena base**: changed from full request URL to site root (`https://host/`) — fixes 500 when Jena relativised URIs with `:` in the first path segment
- **Overpass XML link**: feature panel Overpass column now links to Overpass API XML instead of OSM API XML
- **Overpass example dropdown**: Overpass Feature-by-ID form now has an example select populated from `examples.json`; OSM example select renamed `osm-example-select`
- **FAQ**: two new entries — OSM vs Overpass bbox differences (area limit, relations skipped); Overpass `>>` recursive member resolution for single relations

## [2026-03-14] (2)
- **RDF/GeoJSON URI alignment**: tag key URIs changed from `http://wiki.openstreetmap.org/wiki/Key:{key}` to local `/tag/{key}` (served by `TagServlet`) in both GeoJSON properties and RDF output; `map-common.js` `Key:` prefix stripping removed
- **XSLT namespace overhaul** (node.xsl, way.xsl, relation.xsl): removed `xmlns="http://osm.geovocab.org/vocab#"` default namespace; added `xmlns:osm="https://osmwrap.ontologycentral.com/vocab#"` and `xmlns:osmt="https://osmwrap.ontologycentral.com/tag/"`; generic tag template now emits `<osmt:{key}>` for simple keys and `<osm:tag osm:key="..." osm:value="..."/>` for keys containing `:` (previously dropped)
- **way.xsl / relation.xsl tag coverage**: added generic tag template — previously all tags except `name:en`, `wikipedia`, `wikidata` were silently dropped; now all tags are emitted
- **`rdf:type` + `dcterms:identifier`**: each `spatial:Feature` now asserts `rdf:type osm:Node/Way/Relation` and `dcterms:identifier` (numeric OSM ID)
- **`source-prefix` XSLT parameter**: `foaf:page`, `rdf:about`, and `prov:used` URIs in all three XSLT stylesheets now use `{$source-prefix}` (default `/osm`); `FeatureServlet` passes `/osm`, `OverpassElementServlet` passes `/overpass` — fixing broken `foaf:page` links that previously pointed to `/node/` instead of `/osm/node/` or `/overpass/node/`
- **`osmFeatureToGeoJson` sourcePrefix**: `GeoJsonConverter.osmFeatureToGeoJson` takes a new `sourcePrefix` arg; `FeatureServlet` passes `"/osm"` so GeoJSON `"id"` field is `/osm/{type}/{id}#id`
- **`TaginfoConverter` linked data**: single-key `/tag/{key}` response now emits `rdfs:label`, `rdfs:seeAlso` (OSM wiki URL), and `rdf:type rdf:Property`
- **Overpass Feature-by-ID form**: `index.html` Overpass column (si=2) now has node/way/relation ID inputs that call `loadGeoJsonUrl('map-main', '/overpass/{type}/{id}', 2)`

## [2026-03-14]
- **4-column layout**: `map.css` grid changed to `32rem 1fr 1fr 1fr`; one column per source — map, Nominatim (si=0), OSM API (si=1), Overpass (si=2); `map.js` state arrays per-si (`featureLayers`, `featurePanels`, `fetchEpochBySi`, `controllerBySi`, `loadedFeaturesBySi`, `loadedUrlBySi`, `currentIdxBySi`); `loadGeoJsonUrl`, `_setFetchState`, `_updateSource`, `_renderFeatures`, `_navFeature`, `_renderFeaturePanel` all take explicit `si` argument
- **Source-prefixed URL mappings**: `FeatureServlet` moved to `/osm/node/*`, `/osm/way/*`, `/osm/relation/*`; `SearchServlet` to `/nominatim/search`; `MapServlet` to `/osm/map`; `POIServlet` to `/overpass/poi`; `AroundServlet` to `/overpass/around`; new `OverpassFeaturesServlet` at `/overpass/features` with `bbox` and `type` (node/way/relation/nwr) parameters
- **Cancel / Clear buttons**: per-si `AbortController` stored in `controllerBySi[si]`; Cancel button shown during fetch via `setStatusHtml`; Clear button shown after load; `cancelLoad(mapId, si)` and `clearLoad(mapId, si)` functions
- **Overpass attribution**: `GeoJsonConverter.overpassNodesToGeoJson` and `osmMapToGeoJson` extract `<note>` text from Overpass XML and emit it as `"attribution"` on the FeatureCollection; `loadGeoJsonUrl` uses `geojson.attribution || _attributionFor(url)`
- **GeoJSON-LD**: all FeatureCollections and single-Feature responses include `"@context":"https://geojson.org/geojson-ld/geojson-context.jsonld"`; OSM tag keys emitted as `http://wiki.openstreetmap.org/wiki/Key:{key}` URIs; `wikidata` values (`Q\d+`/`P\d+`) expanded to `https://www.wikidata.org/wiki/…`; `wikipedia` values expanded to `https://{lang}.wikipedia.org/wiki/…`; `propsToHtml` in `map-common.js` strips `Key:` prefix for display labels
- **Bbox area display**: zoom status line now shows deg² alongside km²/m² (useful for OSM API /map hard limit of 0.25 deg²)
- **Overpass element endpoints**: new `OverpassElementServlet` at `/overpass/node/*`, `/overpass/way/*`, `/overpass/relation/*`; queries Overpass QL (`node(ID); out body;` etc.); GeoJSON via `osmMapToGeoJson`; RDF via existing node/way/relation XSLT after stripping Overpass-specific `<note>` and `<meta>` elements (which would otherwise produce invalid RDF/XML and cause a 500 via `RdfFilter`); feature panel for si=2 links to `/overpass/{type}/{id}` instead of `/osm/{type}/{id}`
- **examples.json**: "Third Street, Santa Monica" label order corrected; Overpass `around` examples added

## [2026-03-10]
- `map.js`: bbox rectangle rendered in a dedicated Leaflet pane (z-index 450) so it appears above feature layers and is clickable; clicking the frame zooms the map to the query bbox; area of current map view displayed alongside zoom level (e.g. `zoom: 14 · 3.2 km²`)

## [2026-03-02]
- Bidirectional wiring of ID inputs ↔ feature panel: selecting a feature on the map (by click or prev/next navigation) now updates the node/way/relation text inputs in `geo-form` (linked-osm) and the GML `fid-input` field (linked-inspire); example dropdowns fill the relevant text input on change instead of triggering a load directly, with their redundant Load buttons removed

## [2026-02-28]
- Suffix + content-negotiation fix for all servlets: `/search.rdf`, `/search.ttl`, `/map.ttl`, `/poi.rdf`, `/poi.ttl`, `/around.rdf`, `/around.ttl` URL patterns added to `web.xml`; `AcceptHeader.prefersJson()` and `RdfFilter` honour `.ttl` suffix (force Turtle regardless of Accept header); `FeatureServlet` handles `.ttl` extension for `/node/*`, `/way/*`, `/relation/*`; `SearchServlet`, `POIServlet`, `AroundServlet` emit `X-Upstream-Source` response header; `map.js` reads the header to show a clickable `Source XML` / `OSM XML` upstream link, and Turtle links now point to `.ttl` URLs directly
- `index.html`: restructured to linked-inspire UI pattern — unified Location section (preset select, place-name search, bbox input) above the map; nodes/ways/relations lookup moved into right column below feature panel; per-service bbox/lon/lat inputs replaced by single Load buttons that read the current map bbox/center

## [2026-02-26]
- Local SPARQL processor.
- `FeatureServlet`: in-memory Caffeine cache of upstream OSM XML keyed by URL (2 GB weight limit, 24 h expiry); at most one upstream fetch per URL at a time — concurrent requests for the same URL join the in-flight `CompletableFuture` and share the result
- `relation.xsl`: fixed XSLT 2.0 multi-valued sequence bug where `relation/@id` on a `/full` response returned all relation IDs space-separated, producing an invalid URI in `prov:used`; ID now passed as `element-id` XSLT parameter from `FeatureServlet`
- Dead code removed from `Listener`: `ISO8601`, `FACTORY`, `TOC` constants; `XMLOutputFactory` init; JSR-107 (`cache-ri-impl`) replaced by Caffeine
- PROV-O: upstream byte size (`dcat:byteSize`) added to `prov:used` entity in all RDF stylesheets, populated from `Content-Length` response header without buffering
- `HttpClientUtil.errorStatus()`: maps `HttpTimeoutException` → 504, other `IOException` → 500; timeouts bumped (connect 15 s, response 120 s); `map.js` client timeout 130 s (slightly above server) so HTTP status codes reach the browser
- `faq.html`: added caching section; timeout section updated to cover 504
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
