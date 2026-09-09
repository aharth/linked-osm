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

# Run SPARQL query against a local Turtle file (roqet -D requires a URL).
# -F turtle is required: raptor's content-based format guesser recognizes
# "@prefix"-style Turtle but not the "BASE"/"PREFIX" (no @) prologue style
# some osmwrap Turtle responses use, and these fetched files have no
# extension for guessing to fall back on either.
roqet_doc() {
    local label="$1" file="$2" base_url="$3" query="$4"
    local result
    result=$(roqet -q -F turtle -D "file://$file" -e "$query" 2>/dev/null) || true
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
        # The wrapper agent lives at /index#osmwrap (family convention: "the wrapper's
        # URI lives in index"), not a bare /#osmwrap — stale check updated 2026-09-09.
        grep -q '</index#osmwrap>' "$TTL" \
            && ok  "$type: </index#osmwrap> relative in Turtle" \
            || fail "$type: </index#osmwrap> missing or absolute in Turtle"
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

# Fetch /tag/{key} and /tag/{key}={value}: SKOS concepts served by TagServlet,
# minted as dc:subject on /overpass/features output (map.xsl). key=value pages
# were added 2026-09-09 to fix /tag/{key}={value} 404s (was a plain 404 by
# design; Taginfo's per-value /tag/stats endpoint made it worth building).
#
# httpRange-14 split (added 2026-09-09): the SKOS concept lives at an absolute
# {url}#concept, not the bare document URL -- the bare "" document instead
# carries prov:generatedAtTime/wasAttributedTo/hadPrimarySource about the
# response itself. #concept is an absolute reference (baseUri+key+"#concept"),
# not a bare relative "#concept", so the concept's identity is the same
# whether fetched as .rdf, .json, or content-negotiated plain -- only the
# document-level prov triples differ per URL variant.
test_tag() {
    local key="$1" value="$2"
    local url="$BASE/tag/$key"
    local vurl="$BASE/tag/$key=$value"

    echo "-- tag key $key"
    local RDF JSON
    RDF=$(fetch_once "$url.rdf" "application/rdf+xml")
    JSON=$(fetch_once "$url.json")
    if [ -z "$RDF" ]; then
        fail "tag key RDF/XML: fetch failed"
    else
        check_rapper "tag key RDF/XML valid" rdfxml "$RDF" "$url.rdf"
        grep -q '<skos:Concept' "$RDF" \
            && ok  "tag key: is skos:Concept" \
            || fail "tag key: not marked as skos:Concept"
        grep -q 'prov:generatedAtTime' "$RDF" \
            && ok  "tag key: document has prov:generatedAtTime" \
            || fail "tag key: document missing prov:generatedAtTime"
    fi
    if [ -z "$JSON" ]; then
        fail "tag key JSON-LD: fetch failed"
    else
        ok "tag key JSON-LD: fetch ok"
    fi
    [ -n "$RDF" ] && rm -f "$RDF"
    [ -n "$JSON" ] && rm -f "$JSON"
    echo ""

    echo "-- tag value $key=$value"
    local VTTL VRDF
    VTTL=$(fetch_once "$vurl" "text/turtle")
    VRDF=$(fetch_once "$vurl.rdf" "application/rdf+xml")

    if [ -z "$VTTL" ]; then
        fail "tag value Turtle: fetch failed"
    else
        check_rapper "tag value Turtle valid" turtle "$VTTL" "$vurl"
        roqet_doc "tag value: document has prov:wasAttributedTo" "$VTTL" "$vurl" \
            "PREFIX prov: <http://www.w3.org/ns/prov#>
             SELECT ?a WHERE { <$vurl> prov:wasAttributedTo ?a } LIMIT 1"
        roqet_doc "tag value: skos:broader points at key #concept" "$VTTL" "$vurl" \
            "PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
             SELECT ?b WHERE { <$vurl#concept> skos:broader ?b } LIMIT 1"
    fi

    if [ -z "$VRDF" ]; then
        fail "tag value RDF/XML: fetch failed"
    else
        check_rapper "tag value RDF/XML valid" rdfxml "$VRDF" "$vurl.rdf"
    fi

    [ -n "$VTTL" ] && rm -f "$VTTL"
    [ -n "$VRDF" ] && rm -f "$VRDF"
    echo ""
}

echo "=== osmwrap RDF smoke tests  BASE=$BASE ==="
echo ""

test_resource node     11980635629 "$BASE/osm/node/11980635629"
test_resource way      100          "$BASE/osm/way/100"
test_resource relation 147          "$BASE/osm/relation/147"

test_tag tracktype grade1

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

# /tag schema: key concepts and their key=value narrower concepts (added 2026-09-09
# alongside the key=value fix, as worked examples of the SKOS shape for consumers).
roqet_sparql "SPARQL: tag key concepts" \
    'PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
     SELECT ?concept ?label
     FROM </tag/tracktype>
     WHERE { ?concept a skos:Concept ; skos:prefLabel ?label } LIMIT 3'

roqet_sparql "SPARQL: tag value narrower than key" \
    'PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
     PREFIX osm: <http://osm.geovocab.org/vocab#>
     SELECT ?value ?broader ?count
     FROM </tag/tracktype=grade1>
     WHERE { ?value skos:broader ?broader ; osm:countAll ?count } LIMIT 3'

echo ""
echo "=== $PASS passed, $FAIL failed ==="
[ "$FAIL" -eq 0 ]
