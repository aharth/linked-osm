# Changelog

All notable changes to the OpenStreetMap Linked Data Wrapper project will be documented in this file.

## [2026-07-22] Malformed Accept q-value guard (backport from linked-lod2-by)

- **`AcceptHeader.parse`**: a garbage q-value (`text/html;q=oops`) threw
  `NumberFormatException` mid-stream and turned into a 500 on every
  Accept-parsing endpoint; now falls back to q=1.0. Regression test added to
  `AcceptHeaderTest`. Applied identically in linked-inspire, linked-adv and
  linked-pdok (shared-by-copy).

## [2026-07-02] Rate limiting

### Added

- **`RateLimitFilter`** (Bucket4j): 50 requests per 10 minutes per client IP on all
  upstream-hitting servlets (osm, geo, nominatim/search, overpass, changeset, tag,
  sparql); static pages and `/routes` are unthrottled. Client IP from the first
  `X-Forwarded-For` entry, falling back to `getRemoteAddr()`. Exempt subnets:
  FAU (131.188.0.0/16, 2001:638:a000::/48), Fraunhofer IIS (192.44.12.0/24), and
  local ranges (192.168.0.0/16, fc00::/7, fe80::/10). Requests with a valid API key
  in `Authorization: Bearer <key>` bypass the limit — keys are comma-separated in the
  `OSMWRAP_API_KEYS` env var (fallback: `api-keys` context-param), compared in
  constant time; only the header is accepted, never a query parameter. Over-limit
  requests get 429 via `ErrorServlet`. Documented in the FAQ (`#ratelimit`).
  Mirrors linked-inspire / linked-eurostat. `RateLimitFilterTest` covers the subnet
  exemptions, key parsing, and Bearer-token extraction (8 tests).

## [2026-07-01] `/routes` interface manifest

### Added

- **`RoutesServlet`** at `/routes` (and `/routes.json`): a machine-readable manifest
  of the *deployed* endpoints, each with the representation `formats` it serves and
  the query `params` it accepts — introspected from the servlet context's own
  mappings, so it reflects exactly what is live with nothing hand-maintained to
  drift. Route names keep their path (`nominatim/search`, `overpass/poi`, …).
  `formats` come from the url-pattern suffixes; `params` are declared in
  `ROUTE_PARAMS`, mirroring each servlet's `getParameter` reads (`nominatim/search`
  → `q,limit`; `overpass/around` → `lat,lon,radius,limit`; `overpass/features` →
  `bbox,filter,type`; `overpass/poi` → `bbox,filter,limit`; `sparql` →
  `query,format`; the path-addressed record routes `osm/node`, `overpass/way`,
  `tag`, … take none). Mirrors the sibling wrappers
  (linked-mastr/nuts/lau/lod2-by/netztransparenz/wetterdienst/energieatlas/
  regionalstatistik). CORS-open. `RoutesServletTest` covers the url-pattern →
  logical-route reduction (3 tests).

This is the "option-C" source of truth for the Granergize webapp's compile-time
route-contract check (`gen:routes:osm`) and Data-sources health/contract panel.

## [2026-06-15] CORS

- Add a permissive `CorsFilter` (allows `Accept, Content-Type, Authorization, DPoP`) so a Solid SPA can fetch resources cross-origin; an `OPTIONS` preflight is answered `204`.

## [2026-06-11] Fix: way geometry in /overpass/features GeoJSON

- `GeoJsonConverter.overpassFeaturesToGeoJson` emitted ZERO way features
  from real Overpass `out geom` responses: way `<nd>` elements carry the
  node `ref` BEFORE `lat`/`lon` (`<nd ref=".." lat=".." lon=".."/>`), and
  the position-dependent regex `<nd\s+lat=...` never matched. The nd
  coordinates are now pulled by attribute name (shared `parseNdCoords`
  helper, also used for relation-member geometry, whose `<nd>` has no
  ref). Regression test `GeoJsonConverterTest` uses a real trimmed
  Overpass response (a Nürnberg DPD warehouse way).



## [2026-03-16]
- **PROV restructured**: `prov:hadPrimarySource` on `<>` now points to the versioned OSM API URL (e.g. `.../node/{id}/{version}`), creating a clear chain `<>` → versioned source → `</changeset/c>` → `prov:wasAssociatedWith` agent; feature (`#id`) carries no PROV properties; timestamp and editor appear only on the changeset activity, not duplicated on the source entity
- **`common.xsl`**: shared XSLT module extracted from `node.xsl`, `way.xsl`, `relation.xsl`; contains `local:ttl`, `ttl-prefixes`, `doc-header`, `versioned-source`, and `changeset-activity` named templates; included via `xsl:include`
- **Relative references throughout**: Turtle output (`node.xsl`, `way.xsl`, `relation.xsl`) uses path-absolute tag URIs (`</tag/KEY>`, `</vocab#>`) instead of absolute `https://osmwrap…` URIs; `RdfFilter` passes `.base(siteRoot)` to Jena `RDFWriter` so RDF/XML emits `xml:base` and relativizes subject URIs
- **Reverse-proxy scheme fix**: `base-scheme` context-param in `web.xml` (value `https`) read by `RdfFilter` and `SparqlServlet` before falling back to `req.getScheme()`; ensures correct scheme when Tomcat sits behind Apache without `X-Forwarded-Proto`
- **`test/smoke-rdf.sh`**: RDF smoke test using `rapper` (parse validity) and `roqet` (SPARQL queries); fetches each resource once into a temp file to avoid rate limits; checks no absolute osmwrap URIs in Turtle, `https://` scheme in RDF/XML, prov attribution, geometry, tags, coordinates via both local-file and `/sparql` endpoint queries

## [2026-03-15]
- `MultiViewsFilter` added: content negotiation for extensionless URLs and `/`; TTL variants satisfy `application/rdf+xml` requests via on-demand Jena conversion; explicit `.rdf` URL requests derived from `.ttl` counterpart
- `index.ttl` added as the sole RDF description of the service (`<#osmwrap> a prov:SoftwareAgent ; foaf:page <index.html>`); no static `index.rdf` needed
- **PROV entity-centric pattern extended**: `search.xsl`, `poi.xsl`, `map.xsl` migrated from `prov:Activity` / `#transformation` to direct `prov:hadPrimarySource`, `prov:generatedAtTime`, `prov:wasAttributedTo` on `<>`; `dcat:byteSize` on upstream URI subject; `POIServlet`, `AroundServlet`, `OverpassFeaturesServlet` now pass `upstream-url`; `poi.xsl` and `map.xsl` attribution/license/publisher fixed to hardcoded correct values
- **Overpass features filter**: `buildOverpassFeaturesQuery` accepts optional `filter` param (`key=value` → exact match, `key` → key-exists, blank → no filter); UI adds filter text input alongside type select
- **`/tag/{key}={value}` removed**: tag value URIs return 404; used values now listed as `skos:example` literals on the key resource instead of `skos:narrower` URI references; `fetchTagInfo`, `convertTagValueToSKOS*`, `uriEncodeValue` removed
- **`faq.html` / `bot.html`**: bold and italic removed; `<ul>` with bold lead terms converted to `<dl>`
- **PROV-O fixes** (`node.xsl`, `way.xsl`, `relation.xsl`): `dc:attribution` hardcoded to `© OpenStreetMap contributors`; `dc:license` changed to `<https://opendatacommons.org/licenses/odbl/>` URI; `dc:publisher` URL updated to `https://`; `rdfs:comment @copyright/@attribution` attributes dropped (empty in OSM API responses); `prov:hadPrimarySource` emitted with the actual upstream URL when `$upstream-url` param is set; `foaf:page` `.ttl` link added alongside `.rdf`/`.json`
- **`upstream-url` XSLT parameter**: `FeatureServlet` passes the OSM API URL actually used (plain `/relation/{id}` or `/relation/{id}/full`); `SearchServlet` passes the Nominatim query URL; `OverpassElementServlet` passes the Overpass interpreter URL with percent-encoded QL query — so each RDF document's `prov:hadPrimarySource` points to the exact upstream response
- **`search.xsl`**: `prov:Entity rdf:about` now derived from `$upstream-url` param instead of hardcoded Nominatim base URL
- **`RdfFilter` base URI**: Jena base changed from site root (`https://host/`) to the actual document URL (`https://host/path`) — fixes `<>` in serialised RDF/XML resolving to `/` instead of the request document
- **`ErrorServlet`**: replaced `doGet`/`doPost` with `service()` override; content negotiation via `AcceptHeader.prefers()` — serves HTML only when `text/html` is preferred over `application/json`; defaults to JSON for all other clients (RDF, Turtle, fetch API); `status` field dropped from JSON body (status is in the HTTP response code)
- **PROV entity-centric pattern** (`node.xsl`, `way.xsl`, `relation.xsl`): dropped `prov:Activity` / `#transformation` node; document-level PROV now uses `prov:hadPrimarySource`, `prov:generatedAtTime`, `prov:wasAttributedTo` directly on `<>`; `dcat:byteSize` emitted as a separate triple with the upstream URL as subject (only when both `$upstream-url` and `$upstream-bytes` are set)
- **OSM API bbox endpoint removed**: `MapServlet`, `buildMapUrl`, `--map` CLI flag, and `/osm/map*` web.xml entries deleted; `osmMapToGeoJson` replaced by `overpassFeaturesToGeoJson` (nodes→Point, ways→LineString/Polygon with inline `out geom` coords, relations→Polygon/MultiPolygon via `MultipolygonHandler.buildFromSegments` or MultiLineString for routes)
- **`OverpassFeaturesServlet`**: fixed to use `Listener.MAP` template and `overpassFeaturesToGeoJson`; Overpass query switched from two-pass `out body; >; out skel qt` to `out geom`
- **POI filter**: `buildOverpassPOIQuery` gains a `filter` param (`key=value` → exact match, `key` → key-exists, blank → default amenity/shop/tourism regex); `index.html` POI row adds a filter text input
- **4-column grid fixed**: Overpass bbox controls moved to Overpass column (col 3); OSM API map `<dl>` removed from UI
- **EOX OSM tile layer**: added to `OSM_TILE_LAYERS` as a WMS entry; `switchOsmTileLayer` branches on `layer.type === 'wms'` to use `L.tileLayer.wms`; PNG link uses new `_centerTileWmsUrl` (WMS GetMap for center tile bbox)
- **Button labels**: Pan (viewport navigation), Switch (tile layer), Load bbox (Overpass features/POI/around), Load (feature by ID), Search (Nominatim)
- **JSON error pages**: `ErrorServlet` handles all `<error-page>` codes (400, 404, 406, 413, 500, 502, 503, 504); returns `{"status":N,"error":"..."}` for JSON/RDF/Turtle clients, minimal HTML otherwise; `RdfFilter` restricted to `REQUEST` dispatch so it never intercepts error pages; JS fetch reads response body on non-ok, parses JSON, shows `"Error: 413 — Relation … has … way members"` in status bar
- **Large relation fail-fast**: `FeatureServlet` fetches the lightweight `/relation/{id}` response first, counts way members, and returns HTTP 413 Content Too Large (RFC 9110) if the count exceeds 200, with a message directing the client to `/geo/overpass/relation/{id}.json`; only small relations proceed to `/relation/{id}/full`
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
