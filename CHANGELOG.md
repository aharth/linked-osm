# Changelog

All notable changes to the OpenStreetMap Linked Data Wrapper project will be documented in this file.

## [2026-09-03] Restore search/around example presets; document the full URI scheme on index.html

- `search-example-select` and `around-example-select` return to `index.html`,
  gone since the 2026-03-01 UI redesign (`39993f4`, "improved query ui")
  consolidated the layout and dropped them along with the old per-service
  bbox/lon/lat inputs. `examples.json` had kept shipping its `search` and
  `around` arrays the whole time with nothing reading them - dead data until
  now. `search` fills the Nominatim text input directly; `around` (no lon/lat
  input any more, just a radius - the control reads the map's current
  center) pans the map to a small bbox around the example point instead.
- New "Example URIs" section at the bottom of `index.html`, one clickable
  list per endpoint family under its own `<h3>`, grouped in upstream order
  (OSM API, Nominatim, Overpass, Commercial APIs, Wrapper) - mirroring the
  pattern from `linked-pdok`'s `index.jsp`
  (`<h2>`/`<p>`/`<ul><li><a>...</a> &mdash; description</li>`).
  Covers every servlet in `web.xml` that wasn't already reachable through
  the interactive map controls: `/geo/osm/*`, `/geo/overpass/*`,
  `/changeset/*` (OSM's first-ever changeset, id 1), `/tag/*` (SKOS from
  taginfo), `/sparql.html`, and `/routes.json` (the self-describing route
  manifest) - plus three `/tile/protomaps/{z}/{x}/{y}.mvt` examples at
  z5/z10/z15, same point (Kaiserburg, Nuremberg) at each zoom so the
  progression is legible. (Tracestrack examples follow in the next entry,
  alongside the code that serves them.)

## [2026-09-03] Passthrough proxies for Tracestrack's raster, vector, terrain-RGB and elevation APIs

- New `/tile/tracestrack/*` (`TracestrackTileServlet`) covers all three of
  Tracestrack's tile shapes in one servlet, dispatched on the leading path
  segment per their own docs (`tracestrack.com/docs/`):
  `/vt/{name}/{z}/{x}/{y}.pbf` (vector), `/terrain-rgb/{z}/{x}/{y}.webp`
  (terrain-RGB elevation tiles), and `/{mapname}/{z}/{x}/{y}.{ext}` (combined
  raster, `webp`/`png`, optional `?style=` colour variant) - no rendering, no
  format negotiation, upstream bytes streamed back as-is. New
  `/tracestrack/elevation` (`TracestrackElevationServlet`, `POST`) forwards a
  client's JSON coordinate array unmodified via `HttpClientUtil.postRaw()`
  (new: arbitrary body + content-type, alongside the existing form-encoded
  `post()`).
- Trust-gated the same way as `/tile/protomaps` and the Tracestrack Overpass
  routing: `TracestrackRouting.key()` factors the shared
  trusted-request-plus-configured-key check out of `OverpassRouting` so the
  four Tracestrack-backed servlets (now five, counting Overpass) don't each
  duplicate it. No free fallback - 403 for untrusted requests or an
  unconfigured key.
- URL construction (`TracestrackTileServlet.upstreamUrl()`) is a pure static
  method, unit-tested (`TracestrackTileServletTest`, 9 cases: all three tile
  shapes, digit validation on z/x, extension validation per shape, `style`
  query-param URL-encoding, malformed-path rejection).
- Endpoint shapes came from Tracestrack's own docs site
  (`tracestrack.com/docs/`), which 403s to automated fetches same as their
  ToS page - the user read the docs directly and pasted them in. Community
  sources found first (a GPXSee GitHub issue, a MOBAC forum thread) turned
  out to be stale/incomplete (they showed `/topo__/{z}/{x}/{y}.png`, missing
  the language-code map names, the `style` param, and the `@2x`/`@1x`
  density suffix) - not used.
- `index.html`'s Example URIs section gets four Tracestrack links alongside
  the existing Protomaps ones (raster with and without a style variant,
  vector, terrain-RGB) plus a note on the elevation POST shape.

## [2026-09-03] Passthrough tile proxy to Protomaps' hosted vector tile API, trusted callers only

- New `/tile/protomaps/{z}/{x}/{y}.mvt` (`ProtomapsTileServlet`) streams the
  upstream `.mvt` bytes back as-is - no rendering, no rasterization, no
  format negotiation. That rules it out as a source for the `/tile`
  raster-cartography plan (`plans/tile-endpoint-osm-carto.md`), which needs
  PNG output matching the sibling wrapper family's grid; this is scoped
  narrower, just wiring the upstream.
- Gated the same way as the Tracestrack Overpass routing: only requests
  `RateLimitFilter` already marked trusted (valid bearer key, or a
  whitelisted subnet/loopback) get an upstream URL from
  `ProtomapsRouting.tileUrl()`; everyone else gets 403. Unlike Tracestrack
  there is no free fallback - Protomaps' hosted API is entirely key-gated,
  so an untrusted request or an unconfigured key has nothing to route to.
- Protomaps key follows the same secret-handling convention as
  `tracestrack.api.key`: new `protomaps.api.key` Maven property, empty by
  default in `pom.xml`, real value only in `~/.m2/settings.xml` - never
  committed, never an environment variable. An unset key means every
  request gets 403, not a build failure.
- Not yet a full sibling-family `/tile` endpoint (no `/status` layer
  registry, no PNG rendering, no provenance `&f=ttl`) - see the plan doc for
  what that would still need, and why Protomaps' vector-only, maxzoom-15
  basemap product doesn't actually solve the raster-cartography use case
  even with a key in hand.

## [2026-09-02] Route bearer-key holders (and whitelisted IPs) to the paid Tracestrack Overpass API

- `RateLimitFilter`'s existing rate-limit-exemption logic - a valid bearer
  token, or a whitelisted subnet/loopback (FAU, Fraunhofer IIS, local ranges) -
  now also decides which Overpass backend a request uses, recorded as a
  request attribute and read by the new `OverpassRouting` helper. A trusted
  request goes to the paid Tracestrack Overpass endpoint; everyone else keeps
  using the free public mirrors in `ApiConstants.OVERPASS_API_BASES`,
  unchanged.
- Wired into all five Overpass-calling servlets: `POIServlet`, `AroundServlet`,
  `GeometryOverpassServlet`, `OverpassElementServlet`, `OverpassFeaturesServlet`.
- The Tracestrack key follows the same secret-handling convention already
  established for `api.keys` (2026-08-26 rotation): a new `tracestrack.api.key`
  Maven property, empty by default in `pom.xml`, real value only in
  `~/.m2/settings.xml` - never an environment variable, never committed. An
  unset key is a no-op fallback to the free mirrors, not a build failure.
- Also dropped `RateLimitFilter`'s `OSMWRAP_API_KEYS` environment-variable
  lookup entirely (user: "get rid of the unholy env variable code" - env vars
  are a pain to set up per deploy compared to a one-time `settings.xml` edit).
  Keys now come solely from the `api-keys` servlet context-param. **Not yet
  ported to linked-inspire/linked-eurostat**, whose `RateLimitFilter` copies
  still read their own env vars.

## [2026-09-02] User-Agent points at GitHub again, not the bot page

- Reverses the 2026-07-23 change: `project.user.agent` (`pom.xml`) and the
  `BuildInfo` fallback now advertise `https://github.com/aharth/linked-osm`
  again, matching `bot.html`'s (already-correct) example string. Deliberate
  choice this time too — just the opposite one.

## [2026-09-02] `ErrorServlet`: RDF-negotiated error responses (PROV-O + W3C HTTP vocab)

Ported from linked-adv/linked-pdok, which already had this: an RDF client
(Accept: text/turtle, application/rdf+xml, or application/n-triples,
preferred over HTML/JSON) hitting any error path now gets back a proper RDF
document describing the failed HTTP transaction — a `http:Request`/
`http:Response` pair per the W3C HTTP vocabulary, plus the same
`prov:wasAttributedTo <index#osmwrap>`/`prov:generatedAtTime` attribution
every other document in this wrapper carries — instead of always falling
back to JSON regardless of what the client asked for. Written self-
contained (inline Jena `RDFWriter` calls) since this repo has no shared
`RdfFmt`-style helper the way linked-inspire/linked-sdi/linked-cdse do.

## [2026-08-30] Rotated the leaked rate-limit token

`web.xml`'s `api-keys` context-param carried the real rate-limit bypass
token as a literal value — committed, in git history, and about to become a
live exposure the moment this repository is made public. Replaced with
`${api.keys}`, resolved at package time by the war plugin's
`filteringDeploymentDescriptors` (already wired up in `pom.xml`) from
`~/.m2/settings.xml`, which never enters the repository. The same token was
shared by `linked-adv`, `linked-cdse`, `linked-gisco`, `linked-inspire`,
`linked-pdok` and `linked-sdi`; it has been rotated everywhere.

## [2026-08-30] `owl:versionInfo` on the wrapper software agent

`/index` (`<index#osmwrap>`, the single `prov:SoftwareAgent` every served
document's provenance points at) now carries `owl:versionInfo`, built from
git rather than the POM: `${project.version}+${git.commit.id.describe}`,
where the git descriptor is the abbreviated commit id (or
tag-distance-commit once tags exist), with a `-dirty` suffix when built from
an unclean tree. The POM version alone (`1.0.0-SNAPSHOT`) never changes
between commits, so it couldn't tell two builds apart. Filtered in via
`pl.project13.maven:git-commit-id-plugin` (`initialize` phase, merged into
the existing `maven-war-plugin` `<configuration>` for the `webResources`
scoping — this project's `war`-native packaging has no separate jar
`<resources>` copy of `webapp/` to also filter) — the rest of
`src/main/webapp` stays unfiltered. Ported from `linked-gisco`.

## [2026-08-30] `MultiViewsFilter`: a same-named directory must not defer negotiation

`MultiViewsFilter` (Apache `mod_negotiation` emulation for extensionless
URLs, e.g. `/index` → `index.ttl`/`index.jsp`) had a guard,
`if (ctx.getResource(path) != null) { chain.doFilter(...); return; }`,
that also passes through for a same-named **directory** — `getResource()`
returns a URL ending in `/` for those too, not just files. If this wrapper
ever grows a `{base}.ttl` + `{base}/` pair (e.g. a `vocab.ttl` next to a
`vocab/` directory of per-collection files), `/{base}` would defer straight
to the servlet container's own add-a-trailing-slash redirect instead of
negotiating `{base}.ttl` — and that redirect is reverse-proxy-prefix-unaware,
so it leaks the internal Tomcat context path and 404s. Found live in
`linked-gisco` (`/vocab` was exactly this case) and ported here
preventively: the guard now passes through only for an actual file
(`!url.toString().endsWith("/")`). No current collision in this wrapper's
`webapp/` tree, so this closes a latent trap rather than fixing an active
404 — verified via `mvn test -Dcheckstyle.skip=true` (this repo's checkstyle
run currently fails on unrelated pre-existing style violations in
`TaginfoConverter.java`/`UrlBuilder.java`, not in `MultiViewsFilter`).

## [2026-08-24] Java sources indent with spaces, like the rest of the family

- **17 files reindented from tabs to four spaces**, matching the 25 files in this
  project that already used spaces and every sibling wrapper. The project was
  mixed: `GeoJsonConverter`, `TaginfoConverter` and most of the servlets used
  tabs while `SparqlServlet`, `UrlBuilder` and the shared-by-copy classes used
  spaces, so a reader met both conventions in one package.
- **Whitespace only.** `git diff -w` reports no change in any of the 17 files, and
  the suite still passes 80 tests. Leading tabs became four spaces each, which
  preserves the 38 lines that use tabs-for-indent then spaces-for-alignment: the
  alignment spaces sit after the indent either way.
- Text blocks were checked before converting — `GeoJsonConverterTest` contains
  one, where indentation is semantically significant — and its assertions confirm
  the content is unchanged.
- Only `.java` was touched. The XSLT files and `web.xml` still use tabs.

## [2026-08-24] Loopback is exempt from the rate limiter

- **A local smoke run no longer trips the limiter.** Every suite in this family
  makes more than 50 requests, so a run against a local instance used to be
  throttled halfway and report the throttling as breakage.
- **Exempted by CONNECTION, not by claimed IP** — this is the part that matters.
  The limiter keys on `X-Forwarded-For` when present, and that header is written
  by whoever sends the request, so adding `127.0.0.1` to the IP exemption set
  would have let anyone on the internet opt out of rate limiting with one line of
  their own headers. `isLoopbackRequest` instead requires the connection's own
  `getRemoteAddr()` to be loopback **and** no `X-Forwarded-For` or `X-Real-IP` to
  be present at all. Behind the reverse proxy every request carries one, so public
  traffic can never be exempted.
- Loopback stays deliberately OUT of `isExempt`, which is tested against the
  claimed client IP. Applied across all 13 wrappers; the rule is in
  `../linked-family/checklist.md`.

## [2026-07-23] User-Agent points at the bot page, not GitHub

- **`project.user.agent` (`pom.xml`) and the `BuildInfo` fallback now advertise
  `https://osmwrap.ontologycentral.com/bot.html`** instead of the GitHub
  repository — osmwrap keeps its own bot page, and that page, not the source
  repo, is what an admin who sees the user-agent in an access log needs.

## [2026-07-22] Stop coining predicates: dcat:bbox replaces vocab/bbox#

- **`dct:spatial` now uses the W3C DCAT-AP encoding** —
  `[ a dct:Location ; dcat:bbox "POLYGON((W S, E S, E N, W N, W S))"^^geo:wktLiteral ]`
  — replacing the wrapper-coined `bbox:BoundingBox` / `bbox:southWest` /
  `bbox:northEast`. A coined predicate is an absolute IRI in the graph, and
  RDF/XML must write predicates as QNames whose namespace cannot be relative, so
  each one baked this deployment's host into every answer. `vocab/bbox.rdf` is
  deleted; the wrapper now coins no predicate at all.
  Verified on both emitters: the Jena path via a reflection harness, the XSLT via
  Saxon. (`dcat:bbox` not `geo:hasBoundingBox`: the bundled Jena ontology is
  GeoSPARQL 1.0.1, which predates that term.) Applied across linked-inspire,
  linked-gisco, linked-pdok, linked-adv and linked-osm.

## [2026-07-22] SPARQL page brought up to the family standard

- **Static `sparql.html` → `WEB-INF/sparql.jsp` + `src/main/resources/sparql.json`**,
  matching the sibling wrappers. Same URL (`/sparql.html`), so `SparqlServlet`'s
  redirect and `rdfs:seeAlso` are unchanged. Adds examples grouped by `source`,
  the `#query=` deep link and the GET-URL builder for `curl`. `Listener` gained
  `SPARQL_EXAMPLES` (no `ServiceRegistry` here); a missing or malformed file
  degrades to an empty dropdown, never a startup failure.
- **The five old examples were all broken; rewritten.** They queried
  `FROM </map.rdf?bbox=…>` — not a mapped endpoint, the bbox document is
  `/overpass/features` — and matched predicates the wrapper never emits
  (`geo:lat`/`geo:long`, `dc:title`, `rdf:type`, `foaf:nick`). `map.xsl` emits
  `dc:subject` links to the `/tag/{k}={v}` concepts and `locn:geometry` as a
  `geo:wktLiteral`. Six replacements use that vocabulary and add the GeoSPARQL
  demos the page lacked (`geof:metricArea`, `geof:metricLength`, `fn:dimension`).
- Kept from the old page: loading an example preserves the current `FROM`, and
  the `#from=` deep link that swaps only the `FROM`.
- **New "FROM — selecting the data" section** (ported from linked-adv, rewritten
  for the real routes: `/overpass/{features,poi,around}.rdf`,
  `/overpass/{node,way,relation}/{id}.rdf`, `/nominatim/search.rdf`, `/changeset/{id}`).
- **One agent IRI, family-consistent.** `<index#osmwrap>` everywhere, matching
  `ProvUtil` and every sibling (`index#inspirewrap` / `#advwrap` / `#pdokwrap` /
  `#giscowrap`). index.ttl and the five stylesheets used `{root}/#osmwrap`,
  which disagreed with ProvUtil's `{root}/index#osmwrap` — two identities for one
  agent. 12 occurrences now agree; verified base-independent (`/index.ttl`,
  `/index`, `/`).
- **KNOWN, not fixed — SPARQL FROM is broken on the deployment.** The reverse
  proxy does not send `X-Forwarded-Prefix`, so `ProvUtil.effectiveOrigin` falls
  back to the servlet context path and `SparqlServlet`'s `serverBase` becomes
  `…/linked-osm-1.0.0-SNAPSHOT/`. An absolute-path `FROM </overpass/…>` (the
  correct public form) is then rejected as "external"; a relative `FROM
  <overpass/…>` resolves to a doubled path and 404s. No FROM form works. Fix is
  either a proxy config (send the prefix) or `effectiveOrigin`; not a code change
  made here. The new examples were verified instead by running them with `arq`
  over the live `/overpass/features.rdf` document.

## [2026-07-22] index.ttl is host-free

- **Dropped `@base <https://osmwrap.ontologycentral.com/>` from `index.ttl`, and
  changed `<#osmwrap>` to `</#osmwrap>`.** The architecture invariant is that
  served RDF bakes in no host. Unlike the sibling wrappers, the bare fragment
  here was base-SENSITIVE: with the `@base` gone it would have resolved against
  the retrieval URI and moved the agent to `<index.ttl#osmwrap>`. The
  absolute-path form is host-free and preserves the exact IRI, which is also the
  one the stylesheets emit (`common.xsl`, `search.xsl`, `map.xsl`, `poi.xsl`,
  `changeset.xsl` all write `/#osmwrap`). Verified byte-identical N-Triples when
  retrieved as `/index.ttl`, `/index` or `/`.
  Applied alongside linked-inspire, linked-gisco and linked-pdok.
- **KNOWN INCONSISTENCY, not fixed here**: `ProvUtil.java:142` attributes
  documents to `{root}/index#osmwrap` while index.ttl and all five stylesheets
  use `{root}/#osmwrap` — two different agent IRIs in one deployment. Picking a
  winner means touching either the Java or the five XSLs; left for a decision.

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
