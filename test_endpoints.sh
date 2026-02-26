#!/usr/bin/env bash
# test_endpoints.sh — endpoint smoke-tests for linked-osm / osmwrap
# Usage: ./test_endpoints.sh [BASE_URL]
# Default BASE_URL: https://osmwrap.ontologycentral.com

set -uo pipefail

BASE="${1:-https://osmwrap.ontologycentral.com}"
EXAMPLES="$(dirname "$0")/src/main/webapp/examples.json"
DELAY="${DELAY:-1}"   # seconds between requests; set DELAY=0 to disable

pass=0
fail=0

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
# Feature loop: node, way, relation
# ---------------------------------------------------------------------------
while IFS= read -r entry; do
  type=$(printf '%s' "$entry" | jq -r '.type')
  id=$(printf '%s'   "$entry" | jq -r '.id')
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

# ---------------------------------------------------------------------------
# Search
# ---------------------------------------------------------------------------
while IFS= read -r entry; do
  q=$(printf '%s' "$entry" | jq -r '.q')
  enc=$(python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))" "$q")

  run "search '$q' GeoJSON jq" \
    bash -c "curl -fsSL '$BASE/search.json?q=$enc' | jq empty"

  run "search '$q' RDF/XML rapper" \
    bash -c "curl -fsSL -H 'Accept: application/rdf+xml' '$BASE/search?q=$enc' \
      | rapper -q -i rdfxml -I '$BASE/search' - >/dev/null"

done < <(jq -c '.search[]' "$EXAMPLES")

# ---------------------------------------------------------------------------
# Map (bounding box)
# ---------------------------------------------------------------------------
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

# ---------------------------------------------------------------------------
# Around
# ---------------------------------------------------------------------------
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

# ---------------------------------------------------------------------------
# Specific named test cases
# ---------------------------------------------------------------------------

# relation/51477 — large relation, explicit RDF/XML parse
run "relation/51477 RDF/XML rapper" \
  bash -c "curl -fsSL -H 'Accept: application/rdf+xml' '$BASE/relation/51477' \
    | rapper -q -i rdfxml -I '$BASE/relation/51477' - >/dev/null"

# relation/71525 — both serialisations
run "relation/71525 RDF/XML rapper" \
  bash -c "curl -fsSL -H 'Accept: application/rdf+xml' '$BASE/relation/71525' \
    | rapper -q -i rdfxml -I '$BASE/relation/71525' - >/dev/null"

run "relation/71525 Turtle rapper" \
  bash -c "curl -fsSL -H 'Accept: text/turtle' '$BASE/relation/71525' \
    | rapper -q -i turtle -I '$BASE/relation/71525' - >/dev/null"

# /around Westminster — GeoJSON + RDF; also the view.html wrapper (HTTP 200 only)
run "around Westminster GeoJSON jq" \
  bash -c "curl -fsSL '$BASE/around.json?lon=-0.127&lat=51.501&radius=30' | jq empty"

run "around Westminster RDF/XML rapper" \
  bash -c "curl -fsSL -H 'Accept: application/rdf+xml' '$BASE/around?lon=-0.127&lat=51.501&radius=30' \
    | rapper -q -i rdfxml -I '$BASE/around' - >/dev/null"

run "view.html around Westminster HTTP 200" \
  curl -fsSL -o /dev/null \
    "$BASE/view.html?uri=/around%3Flon%3D-0.127%26lat%3D51.501%26radius%3D30"

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
echo ""
echo "Results: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
