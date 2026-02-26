#!/usr/bin/env bash
# test_endpoints.sh — endpoint smoke-tests for linked-osm / osmwrap
#
# Usage:
#   ./test_endpoints.sh [--light|--heavy] [BASE_URL]
#
# Modes:
#   --light  (default) — small fixed set of core checks; no Overpass calls; no delay
#   --heavy  — full loop over all examples in examples.json, all formats,
#              Overpass /around endpoints; DELAY defaults to 10 s between requests
#
# BASE_URL defaults to https://osmwrap.ontologycentral.com
# Override inter-request delay: DELAY=5 ./test_endpoints.sh --heavy

set -uo pipefail

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------
MODE="light"
BASE="https://osmwrap.ontologycentral.com"

for arg in "$@"; do
  case "$arg" in
    --heavy) MODE="heavy" ;;
    --light) MODE="light" ;;
    http*)   BASE="$arg"  ;;
  esac
done

if [ "$MODE" = "heavy" ]; then
  DELAY="${DELAY:-10}"
else
  DELAY="${DELAY:-0}"
fi

EXAMPLES="$(dirname "$0")/src/main/webapp/examples.json"

pass=0
fail=0

# ---------------------------------------------------------------------------
# Helper
# ---------------------------------------------------------------------------
run() {
  local label="$1"; shift
  local tmp rc
  tmp=$(mktemp)
  "$@" >/dev/null 2>"$tmp"
  rc=$?
  if [ $rc -eq 0 ]; then
    printf 'PASS %s\n' "$label"
    pass=$((pass + 1))
  else
    printf 'FAIL %s\n' "$label"
    [ -s "$tmp" ] && sed 's/^/     /' "$tmp"
    fail=$((fail + 1))
  fi
  rm -f "$tmp"
  [ "$DELAY" -gt 0 ] 2>/dev/null && sleep "$DELAY"
}

# ---------------------------------------------------------------------------
# LIGHT mode — core sanity checks only (no Overpass, no delay)
# ---------------------------------------------------------------------------
if [ "$MODE" = "light" ]; then
  echo "Mode: light (BASE=$BASE)"
  echo ""

  # One node: GeoJSON + RDF/XML
  run "node/1 GeoJSON jq" \
    bash -c "curl -fsSL '$BASE/node/1.json' | jq empty"
  run "node/1 RDF/XML rapper" \
    bash -c "curl -fsSL -H 'Accept: application/rdf+xml' '$BASE/node/1' \
      | rapper -q -i rdfxml -I '$BASE/node/1' - >/dev/null"

  # One way: GeoJSON + RDF/XML
  run "way/100 GeoJSON jq" \
    bash -c "curl -fsSL '$BASE/way/100.json' | jq empty"
  run "way/100 RDF/XML rapper" \
    bash -c "curl -fsSL -H 'Accept: application/rdf+xml' '$BASE/way/100' \
      | rapper -q -i rdfxml -I '$BASE/way/100' - >/dev/null"

  # One relation: GeoJSON + RDF/XML (Tigris River — small multipolygon)
  run "relation/147 GeoJSON jq" \
    bash -c "curl -fsSL '$BASE/relation/147.json' | jq empty"
  run "relation/147 RDF/XML rapper" \
    bash -c "curl -fsSL -H 'Accept: application/rdf+xml' '$BASE/relation/147' \
      | rapper -q -i rdfxml -I '$BASE/relation/147' - >/dev/null"

  # Search (Nominatim — no Overpass)
  run "search 'Kaiserburg' GeoJSON jq" \
    bash -c "curl -fsSL '$BASE/search.json?q=Kaiserburg' | jq empty"

  # Map bbox (OSM API — no Overpass)
  run "map 'Kaiserburg' GeoJSON jq" \
    bash -c "curl -fsSL '$BASE/map.json?bbox=11.075,49.457,11.077,49.459' | jq empty"

  # Around (Overpass — small radius to minimise response size)
  run "around Westminster GeoJSON jq" \
    bash -c "curl -fsSL '$BASE/around.json?lon=-0.127&lat=51.501&radius=30' | jq empty"

  # View wrapper page
  run "view.html HTTP 200" \
    curl -fsSL -o /dev/null "$BASE/view.html"

# ---------------------------------------------------------------------------
# HEAVY mode — full suite, all formats, all examples, Overpass included
# ---------------------------------------------------------------------------
else
  echo "Mode: heavy (BASE=$BASE, DELAY=${DELAY}s)"
  echo ""

  # Feature loop: all examples × 5 formats
  while IFS= read -r entry; do
    type=$(printf '%s'  "$entry" | jq -r '.type')
    id=$(printf '%s'    "$entry" | jq -r '.id')
    label=$(printf '%s' "$entry" | jq -r '.label')
    tag="$type/$id ($label)"

    run "$tag GeoJSON jq" \
      bash -c "curl -fsSL '$BASE/$type/$id.json' | jq empty"

    run "$tag RDF/XML xmllint" \
      bash -c "curl -fsSL -H 'Accept: application/rdf+xml' '$BASE/$type/$id' | xmllint --noout -"

    run "$tag RDF/XML rapper" \
      bash -c "curl -fsSL -H 'Accept: application/rdf+xml' '$BASE/$type/$id' \
        | rapper -q -i rdfxml -I '$BASE/$type/$id' - >/dev/null"

    run "$tag Turtle rapper" \
      bash -c "curl -fsSL -H 'Accept: text/turtle' '$BASE/$type/$id' \
        | rapper -q -i turtle -I '$BASE/$type/$id' - >/dev/null"

    run "$tag GML xmllint" \
      bash -c "curl -fsSL '$BASE/$type/$id.gml' | xmllint --noout -"

  done < <(jq -c '.features[]' "$EXAMPLES")

  # Search
  while IFS= read -r entry; do
    q=$(printf '%s' "$entry" | jq -r '.q')
    enc=$(python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))" "$q")

    run "search '$q' GeoJSON jq" \
      bash -c "curl -fsSL '$BASE/search.json?q=$enc' | jq empty"

    run "search '$q' RDF/XML rapper" \
      bash -c "curl -fsSL -H 'Accept: application/rdf+xml' '$BASE/search?q=$enc' \
        | rapper -q -i rdfxml -I '$BASE/search' - >/dev/null"

  done < <(jq -c '.search[]' "$EXAMPLES")

  # Map (bounding box)
  while IFS= read -r entry; do
    bbox=$(printf '%s'  "$entry" | jq -r '.bbox')
    label=$(printf '%s' "$entry" | jq -r '.label')
    enc=$(python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))" "$bbox")

    run "map '$label' GeoJSON jq" \
      bash -c "curl -fsSL '$BASE/map.json?bbox=$enc' | jq empty"

    run "map '$label' RDF/XML rapper" \
      bash -c "curl -fsSL -H 'Accept: application/rdf+xml' '$BASE/map?bbox=$enc' \
        | rapper -q -i rdfxml -I '$BASE/map' - >/dev/null"

  done < <(jq -c '.map[]' "$EXAMPLES")

  # Around (Overpass — long delay needed)
  while IFS= read -r entry; do
    lon=$(printf '%s'    "$entry" | jq -r '.lon')
    lat=$(printf '%s'    "$entry" | jq -r '.lat')
    radius=$(printf '%s' "$entry" | jq -r '.radius')
    label=$(printf '%s'  "$entry" | jq -r '.label')
    qs="lon=${lon}&lat=${lat}&radius=${radius}"

    run "around '$label' GeoJSON jq" \
      bash -c "curl -fsSL '$BASE/around.json?$qs' | jq empty"

    run "around '$label' RDF/XML rapper" \
      bash -c "curl -fsSL -H 'Accept: application/rdf+xml' '$BASE/around?$qs' \
        | rapper -q -i rdfxml -I '$BASE/around' - >/dev/null"

  done < <(jq -c '.around[]' "$EXAMPLES")

  # Named special cases
  run "relation/51477 RDF/XML rapper" \
    bash -c "curl -fsSL -H 'Accept: application/rdf+xml' '$BASE/relation/51477' \
      | rapper -q -i rdfxml -I '$BASE/relation/51477' - >/dev/null"

  run "relation/71525 RDF/XML rapper" \
    bash -c "curl -fsSL -H 'Accept: application/rdf+xml' '$BASE/relation/71525' \
      | rapper -q -i rdfxml -I '$BASE/relation/71525' - >/dev/null"

  run "relation/71525 Turtle rapper" \
    bash -c "curl -fsSL -H 'Accept: text/turtle' '$BASE/relation/71525' \
      | rapper -q -i turtle -I '$BASE/relation/71525' - >/dev/null"

  run "around Westminster GeoJSON jq" \
    bash -c "curl -fsSL '$BASE/around.json?lon=-0.127&lat=51.501&radius=30' | jq empty"

  run "around Westminster RDF/XML rapper" \
    bash -c "curl -fsSL -H 'Accept: application/rdf+xml' '$BASE/around?lon=-0.127&lat=51.501&radius=30' \
      | rapper -q -i rdfxml -I '$BASE/around' - >/dev/null"

  run "view.html around Westminster HTTP 200" \
    curl -fsSL -o /dev/null \
      "$BASE/view.html?uri=/around%3Flon%3D-0.127%26lat%3D51.501%26radius%3D30"

fi

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
echo ""
echo "Results: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
