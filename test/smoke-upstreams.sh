#!/bin/bash
# Smoke tests for the upstreams NOT covered by smoke-rdf.sh: Overpass (all four
# servlets), Nominatim, taginfo, OSM API changeset/geo passthrough, and the
# key-gated tile family (Protomaps, Tracestrack tiles + elevation).
#
# smoke-rdf.sh already does deep RDF/SPARQL validation for /osm/node|way|relation
# and /sparql; this script checks the other upstreams are reachable and return
# well-formed output, at the depth their format allows (rapper for RDF/Turtle,
# jq for JSON/GeoJSON, byte-sanity for binary tiles).
#
# Usage:
#   ./smoke-upstreams.sh [BASE] [BEARER]
#   BASE   defaults to https://osmwrap.ontologycentral.com
#   BEARER optional wrapper API key (Authorization: Bearer <BEARER>), or set
#          OSMWRAP_BEARER. Without one, the gated tile/elevation endpoints are
#          only checked for the correct 402 (no key configured client-side) —
#          they are NOT exercised against their upstream. Pass a key to also
#          validate Protomaps/Tracestrack actually respond (this is what would
#          have caught the "selector manager closed" Tracestrack bug from the
#          server side instead of from a consumer).
#
# apt install raptor2-utils jq

set -uo pipefail

BASE="${1:-https://osmwrap.ontologycentral.com}"
BEARER="${2:-${OSMWRAP_BEARER:-}}"
PASS=0; FAIL=0

ok()   { echo "OK   $*"; PASS=$((PASS+1)); }
fail() { echo "FAIL $*"; FAIL=$((FAIL+1)); }

# Fetch URL into a temp file. Prints the temp-file path and HTTP code, one
# per line, to stdout. All error messages go to stderr. Never fails the
# script on a non-2xx response — callers decide what status is expected.
fetch() {
    local url="$1" accept="${2:-}" method="${3:-GET}" data="${4:-}"
    local tmp
    tmp=$(mktemp /tmp/smoke-upstreams-XXXXXX)
    local curl_args=(-s -o "$tmp" -w "%{http_code}" -X "$method")
    [ -n "$accept" ] && curl_args+=(-H "Accept: $accept")
    [ -n "$BEARER" ] && curl_args+=(-H "Authorization: Bearer $BEARER")
    if [ "$method" = "POST" ]; then
        curl_args+=(-H "Content-Type: application/json" --data "$data")
    fi
    local http_code
    http_code=$(curl "${curl_args[@]}" "$url" 2>/dev/null) || true
    echo "$tmp"
    echo "$http_code"
}

# Validate a local file with rapper (-I sets the base URI for relative-ref resolution)
check_rapper() {
    local label="$1" format="$2" file="$3" base_url="$4"
    local triples
    triples=$(rapper -q -i "$format" -o ntriples -I "$base_url" "$file" 2>/dev/null | wc -l) || \
        { fail "$label: rapper parse failed"; return; }
    if [ "$triples" -gt 0 ]; then
        ok "$label ($triples triples)"
    else
        fail "$label: 0 triples"
    fi
}

check_jq() {
    local label="$1" file="$2" filter="$3"
    local result
    result=$(jq -e "$filter" "$file" 2>/dev/null) || { fail "$label: jq check failed"; return; }
    ok "$label"
}

# GET a URL expecting HTTP 200, then run a validator callback on the body file.
# validator receives the temp file path as $1.
expect_200() {
    local label="$1" url="$2" accept="$3" validator="$4"
    local out code tmp
    out=$(fetch "$url" "$accept")
    tmp=$(echo "$out" | sed -n 1p); code=$(echo "$out" | sed -n 2p)
    if [ "$code" != "200" ]; then
        fail "$label: HTTP $code for $url"
        rm -f "$tmp"
        return
    fi
    "$validator" "$label" "$tmp"
    rm -f "$tmp"
}

# GET/POST a key-gated endpoint. Trust here is server-side (a bearer key OR an
# IP-based exemption — see RateLimitFilter/TracestrackRouting/ProtomapsRouting),
# so whether a request is treated as trusted cannot be predicted purely from
# whether this script was given a BEARER. 402 (correctly gated, untrusted) and
# 200 (trusted; validate the body) are both acceptable outcomes; anything else
# is a real failure.
expect_gated_200() {
    local label="$1" url="$2" method="$3" data="$4" validator="$5"
    local out code tmp
    out=$(fetch "$url" "" "$method" "$data")
    tmp=$(echo "$out" | sed -n 1p); code=$(echo "$out" | sed -n 2p)
    if [ "$code" = "402" ]; then
        ok "$label: 402 (correctly gated, untrusted caller)"
        rm -f "$tmp"
        return
    fi
    if [ "$code" != "200" ]; then
        fail "$label: HTTP $code for $url"
        rm -f "$tmp"
        return
    fi
    "$validator" "$label (trusted caller, 200)" "$tmp"
    rm -f "$tmp"
}

validate_nonempty_binary() {
    local label="$1" file="$2"
    local size
    size=$(wc -c < "$file")
    if [ "$size" -gt 0 ]; then
        ok "$label ($size bytes)"
    else
        fail "$label: empty body"
    fi
}

validate_ttl() { check_rapper "$1" turtle "$2" "$BASE"; }
validate_rdfxml() { check_rapper "$1" rdfxml "$2" "$BASE"; }
validate_geojson() { check_jq "$1" "$2" '.type'; }
validate_json_nonempty() { check_jq "$1" "$2" '. != null'; }

echo "=== osmwrap upstream smoke tests  BASE=$BASE  BEARER=$([ -n "$BEARER" ] && echo set || echo unset) ==="
echo ""

echo "-- Overpass: /overpass/features (bbox+type)"
expect_200 "overpass/features.ttl" \
    "$BASE/overpass/features.ttl?bbox=11.075,49.457,11.077,49.459&type=nwr" "" validate_ttl
expect_200 "overpass/features.json" \
    "$BASE/overpass/features.json?bbox=11.075,49.457,11.077,49.459&type=nwr" "" validate_geojson
echo ""

echo "-- Overpass: /overpass/poi (bbox+filter)"
expect_200 "overpass/poi.ttl" \
    "$BASE/overpass/poi.ttl?bbox=11.075,49.457,11.077,49.459&filter=amenity=cafe" "" validate_ttl
echo ""

echo "-- Overpass: /overpass/around (lon/lat/radius)"
expect_200 "overpass/around.ttl" \
    "$BASE/overpass/around.ttl?lon=11.077&lat=49.458&radius=100" "" validate_ttl
echo ""

echo "-- Overpass: /overpass/{node,way,relation}/{id}"
expect_200 "overpass/relation/147.ttl" "$BASE/overpass/relation/147.ttl" "" validate_ttl
expect_200 "overpass/relation/147.json" "$BASE/overpass/relation/147.json" "" validate_json_nonempty
echo ""

echo "-- /geo/overpass, /geo/osm (GeoJSON passthrough)"
expect_200 "geo/overpass/relation/147.json" "$BASE/geo/overpass/relation/147.json" "" validate_geojson
expect_200 "geo/osm/way/32113829.json" "$BASE/geo/osm/way/32113829.json" "" validate_geojson
echo ""

echo "-- Nominatim: /nominatim/search"
expect_200 "nominatim/search.ttl" "$BASE/nominatim/search.ttl?q=Kaiserburg" "" validate_ttl
expect_200 "nominatim/search.json" "$BASE/nominatim/search.json?q=Kaiserburg" "" validate_json_nonempty
echo ""

echo "-- OSM API: /changeset/{id} (osmwrap's first-ever changeset, id 1)"
# .rdf suffix explicitly, not the bare path: RdfFilter's suffix-less default is
# Turtle (see smoke-rdf.sh's own node/way/relation defaults), so a bare
# /changeset/1 negotiates to Turtle despite ChangesetServlet always setting
# application/rdf+xml itself — RdfFilter renegotiates on top of that.
expect_200 "changeset/1.rdf" "$BASE/changeset/1.rdf" "" validate_rdfxml
echo ""

echo "-- taginfo: /tag/{key}"
expect_200 "tag/amenity.rdf" "$BASE/tag/amenity.rdf" "" validate_rdfxml
expect_200 "tag/amenity.json" "$BASE/tag/amenity.json" "" validate_json_nonempty
echo ""

echo "-- Protomaps: /tile/protomaps/{z}/{x}/{y}.mvt (key-gated)"
expect_gated_200 "tile/protomaps/5/16/10.mvt" \
    "$BASE/tile/protomaps/5/16/10.mvt" GET "" validate_nonempty_binary
echo ""

echo "-- Tracestrack: /tile/tracestrack/* (key-gated, three URL shapes)"
expect_gated_200 "tile/tracestrack raster (en)" \
    "$BASE/tile/tracestrack/en/10/543/349.webp" GET "" validate_nonempty_binary
expect_gated_200 "tile/tracestrack vector (vt/carto)" \
    "$BASE/tile/tracestrack/vt/carto/10/543/349.pbf" GET "" validate_nonempty_binary
expect_gated_200 "tile/tracestrack terrain-rgb" \
    "$BASE/tile/tracestrack/terrain-rgb/10/543/349.webp" GET "" validate_nonempty_binary
echo ""

echo "-- /tile (general endpoint, key-gated) — raster + ttl description"
expect_gated_200 "tile?s=tracestrack raster" \
    "$BASE/tile?s=tracestrack&layer=topo_en&z=10&x=543&y=349" GET "" validate_nonempty_binary
expect_gated_200 "tile?s=tracestrack ttl" \
    "$BASE/tile?s=tracestrack&layer=topo_en&z=10&x=543&y=349&f=ttl" GET "" validate_ttl
echo ""

echo "-- Tracestrack elevation (key-gated, POST)"
expect_gated_200 "tracestrack/elevation" \
    "$BASE/tracestrack/elevation" POST '[{"lat":49.458,"lon":11.077}]' validate_json_nonempty
echo ""

echo "=== $PASS passed, $FAIL failed ==="
[ "$FAIL" -eq 0 ]
