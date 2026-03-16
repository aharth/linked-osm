#!/bin/bash
# Smoke tests for RDF output: relative references, parse validity, SPARQL queries.
# Uses rapper (raptor2-utils) and roqet (rasqal-utils).
#
# Usage:
#   ./smoke-rdf.sh [BASE]
#   BASE defaults to https://osmwrap.ontologycentral.com
#
# apt install raptor2-utils rasqal-utils

set -uo pipefail

BASE="${1:-https://osmwrap.ontologycentral.com}"
SPARQL="$BASE/sparql"
PASS=0; FAIL=0

ok()   { echo "OK   $*"; PASS=$((PASS+1)); }
fail() { echo "FAIL $*"; FAIL=$((FAIL+1)); }

# Fetch URL into a temp file. Prints the temp-file path to stdout on HTTP 200,
# empty string otherwise. All error messages go to stderr.
fetch_once() {
    local url="$1" accept="${2:-}"
    local tmp
    tmp=$(mktemp /tmp/smoke-rdf-XXXXXX)
    local curl_args=(-sf -o "$tmp" -w "%{http_code}")
    [ -n "$accept" ] && curl_args+=(-H "Accept: $accept")
    local http_code
    http_code=$(curl "${curl_args[@]}" "$url" 2>/dev/null) || true
    if [ "$http_code" = "200" ]; then
        echo "$tmp"
    else
        echo "HTTP $http_code for $url" >&2
        rm -f "$tmp"
        echo ""
    fi
}

# Assert Turtle body has no absolute osmwrap subject/predicate URIs
check_no_abs_ttl() {
    local label="$1" file="$2"
    if grep -qE '<https?://osmwrap\.ontologycentral\.com' "$file"; then
        fail "$label: absolute osmwrap URI in Turtle"
        grep -oE '<https?://osmwrap\.ontologycentral\.com[^>]*>' "$file" | sort -u | head -5
    else
        ok "$label: no absolute osmwrap URIs in Turtle"
    fi
}

# Assert RDF/XML body uses https:// for osmwrap namespace URIs
check_rdfxml_scheme() {
    local label="$1" file="$2"
    if grep -q 'http://osmwrap\.ontologycentral\.com' "$file"; then
        fail "$label: RDF/XML uses http:// for osmwrap (should be https://)"
    else
        ok "$label: RDF/XML uses https:// scheme"
    fi
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

# Run SPARQL query against a local Turtle file (roqet -D requires a URL)
roqet_doc() {
    local label="$1" file="$2" base_url="$3" query="$4"
    local result
    result=$(roqet -q -D "file://$file" -e "$query" 2>/dev/null) || true
    if [ -n "$result" ]; then
        ok "$label"
        echo "     $(echo "$result" | head -2)"
    else
        fail "$label: empty result"
    fi
}

# Run SPARQL query against the /sparql endpoint (FROM clauses use relative URIs)
roqet_sparql() {
    local label="$1" query="$2"
    local result
    result=$(roqet -q -p "$SPARQL" -e "$query" 2>/dev/null) || true
    if [ -n "$result" ]; then
        ok "$label"
        echo "     $(echo "$result" | head -2)"
    else
        fail "$label: empty result"
    fi
}

# Fetch both TTL and RDF/XML for a resource, run all checks, clean up.
test_resource() {
    local type="$1" id="$2" url="$3"

    echo "-- $type $id"
    local TTL RDF
    TTL=$(fetch_once "$url.ttl" "text/turtle")
    RDF=$(fetch_once "$url.rdf" "application/rdf+xml")

    if [ -z "$TTL" ]; then
        fail "$type Turtle: fetch failed"
    else
        check_rapper       "$type Turtle valid"  turtle "$TTL" "$url.ttl"
        check_no_abs_ttl   "$type no abs URIs"          "$TTL"
        grep -q '</#osmwrap>' "$TTL" \
            && ok  "$type: </#osmwrap> relative in Turtle" \
            || fail "$type: </#osmwrap> missing or absolute in Turtle"
    fi

    if [ -z "$RDF" ]; then
        fail "$type RDF/XML: fetch failed"
    else
        check_rapper       "$type RDF/XML valid" rdfxml "$RDF" "$url.rdf"
        check_rdfxml_scheme "$type RDF/XML https"       "$RDF"
    fi

    # Per-type SPARQL-over-local-file checks
    if [ -n "$TTL" ]; then
        case "$type" in
            node)
                roqet_doc "$type: is spatial:Feature" "$TTL" "$url.ttl" \
                    'PREFIX spatial: <http://geovocab.org/spatial#>
                     SELECT ?n WHERE { ?n a spatial:Feature } LIMIT 1'
                roqet_doc "$type: has lat/long" "$TTL" "$url.ttl" \
                    'PREFIX geo: <http://www.w3.org/2003/01/geo/wgs84_pos#>
                     SELECT ?lat ?lon WHERE { ?n geo:lat ?lat ; geo:long ?lon } LIMIT 1'
                roqet_doc "$type: prov:wasAttributedTo" "$TTL" "$url.ttl" \
                    'PREFIX prov: <http://www.w3.org/ns/prov#>
                     SELECT ?agent WHERE { ?s prov:wasAttributedTo ?agent } LIMIT 1'
                roqet_doc "$type: has tag" "$TTL" "$url.ttl" \
                    'SELECT ?k ?v WHERE { ?n ?k ?v . FILTER(CONTAINS(STR(?k), "/tag/")) } LIMIT 1'
                ;;
            way)
                roqet_doc "$type: has geometry" "$TTL" "$url.ttl" \
                    'PREFIX geom: <http://geovocab.org/geometry#>
                     SELECT ?g WHERE { ?w geom:geometry ?g } LIMIT 1'
                ;;
            relation)
                roqet_doc "$type: rdfs:label" "$TTL" "$url.ttl" \
                    'PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
                     SELECT ?lbl WHERE { ?r rdfs:label ?lbl } LIMIT 1'
                ;;
        esac
    fi

    [ -n "$TTL" ] && rm -f "$TTL"
    [ -n "$RDF" ] && rm -f "$RDF"
    echo ""
}

echo "=== osmwrap RDF smoke tests  BASE=$BASE ==="
echo ""

test_resource node     11980635629 "$BASE/osm/node/11980635629"
test_resource way      100          "$BASE/osm/way/100"
test_resource relation 147          "$BASE/osm/relation/147"

# ── SPARQL endpoint queries (FROM uses relative URIs resolved by server BASE) ──
echo "-- SPARQL endpoint $SPARQL"

roqet_sparql "SPARQL: node types" \
    'PREFIX spatial: <http://geovocab.org/spatial#>
     SELECT ?n
     FROM </osm/node/11980635629>
     WHERE { ?n a spatial:Feature } LIMIT 3'

roqet_sparql "SPARQL: node coordinates" \
    'PREFIX geo: <http://www.w3.org/2003/01/geo/wgs84_pos#>
     SELECT ?n ?lat ?lon
     FROM </osm/node/11980635629>
     WHERE { ?n geo:lat ?lat ; geo:long ?lon } LIMIT 3'

roqet_sparql "SPARQL: way geometry" \
    'PREFIX geom: <http://geovocab.org/geometry#>
     SELECT ?w ?g
     FROM </osm/way/100>
     WHERE { ?w geom:geometry ?g } LIMIT 3'

roqet_sparql "SPARQL: prov attribution" \
    'PREFIX prov: <http://www.w3.org/ns/prov#>
     SELECT ?doc ?agent
     FROM </osm/node/11980635629>
     WHERE { ?doc prov:wasAttributedTo ?agent } LIMIT 3'

roqet_sparql "SPARQL: relation label" \
    'PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
     SELECT ?r ?lbl
     FROM </osm/relation/147>
     WHERE { ?r rdfs:label ?lbl } LIMIT 3'

echo ""
echo "=== $PASS passed, $FAIL failed ==="
[ "$FAIL" -eq 0 ]
