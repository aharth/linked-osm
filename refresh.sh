#!/usr/bin/env bash
#
# refresh.sh — (re)build one linked-osm dump region on lithium.
#
# Follows the family build contract (linked-gebaeudemodell's
# plans/family-build-serve-split.md, "Building on lithium"; same shape as linked-adv's
# refresh.sh). Plan: plans/pbf-store-bavaria.md.
#
#   ./refresh.sh <region>      e.g. bayern
#   ./refresh.sh --list        every region in src/main/resources/dumps.json
#
#   env  DUMPS_ROOT  required, absolute: the family's dumps root (lithium /home/claude/dumps)
#        BUILD_ROOT  required, absolute: build-only input, never copied (/home/claude/build)
#        JAVA_OPTS   optional, for the index/check jar (default -Xmx2g)
#
# Steps:
#   1. the extract: dumps.json's "file" names it, and it lies in $BUILD_ROOT/osm/<region>/
#      (downloaded by hand once, e.g. from Geofabrik; no download code, no directory scan).
#      It must be sorted by type then ID (Geofabrik's are).
#   2. osmium add-locations-to-ways --keep-member-nodes writes data.osm.pbf (zlib blocks)
#      into a fresh $DUMPS_ROOT/osm/<region>/dump-<stamp>/
#   3. the CLI jar's `dump index` writes blocks.idx, incomplete.idx and MANIFEST.pending;
#      MANIFEST.pending is renamed to MANIFEST (MANIFEST last)
#   4. `dump check`, the webapp's own completeness test
#   5. flip the relative `dump` link to dump-<stamp>
#   6. delete the region's older dump-*/ directories
#   7. print "done: …; copy to tc.ontologycentral.com and reload"
#
# Exit: 0 built and flipped; 1 the build or the check failed, live link unchanged;
#       2 usage or configuration error. The jar comes from `mvn package` (never built here).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REGISTRY="$HERE/src/main/resources/dumps.json"
usage() { echo "usage: DUMPS_ROOT=/abs BUILD_ROOT=/abs ./refresh.sh <region> | --list" >&2; exit 2; }

[ "$#" -eq 1 ] || usage
command -v jq >/dev/null || { echo "refresh.sh: jq not found" >&2; exit 2; }
if [ "$1" = "--list" ]; then
  jq -r '.regions | keys[]' "$REGISTRY"
  exit 0
fi
REGION="$1"
case "$REGION" in *[!a-z0-9-]*|"") usage ;; esac
log() { echo "[refresh osm/$REGION] $*"; }
fail() { echo "[refresh osm/$REGION] ERROR: $1" >&2; exit "${2:-1}"; }

for v in DUMPS_ROOT BUILD_ROOT; do
  [ -n "${!v:-}" ] || fail "$v is not set (absolute path required, see the header)" 2
  case "${!v}" in /*) ;; *) fail "$v must be absolute, got '${!v}'" 2 ;; esac
done
JAVA_OPTS="${JAVA_OPTS:--Xmx2g}"
command -v osmium >/dev/null || fail "osmium not found (apt install osmium-tool)" 2
JAR="$(ls -1t "$HERE"/target/linked-osm-*-jar-with-dependencies.jar 2>/dev/null | head -1 || true)"
[ -n "$JAR" ] || fail "no CLI jar in target/ — run mvn package first" 2

FILE="$(jq -r --arg r "$REGION" '.regions[$r].file // empty' "$REGISTRY")"
[ -n "$FILE" ] || fail "region '$REGION' not in dumps.json, or it has no \"file\"" 2
case "$FILE" in */*) fail "dumps.json file must be a bare file name, got '$FILE'" 2 ;; esac
INPUT="$BUILD_ROOT/osm/$REGION/$FILE"
[ -f "$INPUT" ] || fail "$INPUT not found — download it by hand (dumps.json's \"source\")" 2

REGION_DIR="$DUMPS_ROOT/osm/$REGION"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="$REGION_DIR/dump-$STAMP"
mkdir -p "$REGION_DIR"
mkdir "$OUT"
# A dump-<stamp>/ without MANIFEST is a failed build: remove it. Always returns 0, so the
# trap never turns a successful run's exit status into 1.
cleanup() {
  if [ -n "${OUT:-}" ] && [ -d "$OUT" ] && [ ! -f "$OUT/MANIFEST" ]; then rm -rf "$OUT"; fi
  return 0
}
trap 'cleanup' EXIT

log "input $INPUT ($(du -h "$INPUT" | cut -f1))"
osmium fileinfo -g header.option.sorting "$INPUT" | grep -qx 'Type_then_ID' \
  || fail "$INPUT is not sorted by type then ID (osmium sort first)"

log "add-locations-to-ways -> $OUT/data.osm.pbf"
osmium add-locations-to-ways --keep-member-nodes -f pbf,pbf_compression=zlib \
  -o "$OUT/data.osm.pbf" "$INPUT" || fail "osmium add-locations-to-ways failed"

log "index"
# shellcheck disable=SC2086
java $JAVA_OPTS -jar "$JAR" dump index "$OUT" "$REGION" "$FILE" || fail "dump index failed"
mv "$OUT/MANIFEST.pending" "$OUT/MANIFEST"

log "check"
# shellcheck disable=SC2086
if ! java $JAVA_OPTS -jar "$JAR" dump check "$OUT" "$REGION"; then
  rm -f "$OUT/MANIFEST"
  fail "dump check failed; live link unchanged"
fi

ln -sfn "dump-$STAMP" "$REGION_DIR/dump.tmp"
mv -T "$REGION_DIR/dump.tmp" "$REGION_DIR/dump"
log "dump -> dump-$STAMP"

for old in "$REGION_DIR"/dump-*/; do
  old="${old%/}"
  [ "$(basename "$old")" = "dump-$STAMP" ] && continue
  log "removing $(basename "$old")"
  rm -rf "$old"
done

log "$(grep -E '^(nodes|ways|relations|incomplete_relations|replication_timestamp)=' "$OUT/MANIFEST" | tr '\n' ' ')"
echo "done: osm/$REGION -> dump-$STAMP; copy to tc.ontologycentral.com and reload"
exit 0
