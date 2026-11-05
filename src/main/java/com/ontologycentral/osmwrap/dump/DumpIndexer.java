package com.ontologycentral.osmwrap.dump;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Builds {@code blocks.idx}, {@code incomplete.idx} and {@code MANIFEST.pending} for a dump
 * directory in one streaming pass over {@code data.osm.pbf}. Refuses a file that is not
 * sorted by type then ID or has no locations on ways. Holds every node and way ID in memory
 * (8 bytes each: ~125 MB for Bavaria) to decide relation completeness.
 */
public final class DumpIndexer {

    private static final Logger LOG = Logger.getLogger(DumpIndexer.class.getName());

    private DumpIndexer() {}

    /** Counts reported back to the caller (and written to MANIFEST.pending). */
    public record Result(int blocks, long nodes, long ways, long relations, int incompleteWays,
            int incompleteRelations) {}

    /**
     * @param dir    the dump directory holding {@link DumpFiles#DATA}
     * @param region the region name, e.g. {@code bayern}
     * @param source the downloaded extract's file name, e.g. {@code bayern-261103.osm.pbf}
     */
    public static Result index(Path dir, String region, String source) throws IOException {
        Path data = dir.resolve(DumpFiles.DATA);
        LongList nodeIds = new LongList(1 << 20);
        LongList wayIds = new LongList(1 << 20);
        LongList badWays = new LongList();
        LongList relIds = new LongList(1 << 16);
        LongList badRels = new LongList();
        // (relation, member relation) pairs, checked once every relation ID is known
        LongList relRelPairs = new LongList();

        byte[] types = new byte[1024];
        LongList minIds = new LongList(1024);
        LongList maxIds = new LongList(1024);
        LongList offsets = new LongList(1024);
        PbfBlock.Header header = null;
        int dataBlocks = 0;

        try (PbfFile pbf = new PbfFile(data)) {
            long off = 0;
            while (off < pbf.size()) {
                PbfFile.Blob blob = pbf.read(off);
                if ("OSMHeader".equals(blob.type())) {
                    header = PbfBlock.header(blob.data());
                    checkHeader(header, data);
                } else if ("OSMData".equals(blob.type())) {
                    if (header == null) {
                        throw new IOException(data + ": data block before the OSMHeader");
                    }
                    dataBlocks++;
                    PbfBlock b = PbfBlock.decode(blob.data());
                    for (byte t = PbfBlock.NODE; t <= PbfBlock.RELATION; t++) {
                        long[] ids = ids(b, t);
                        if (ids.length == 0) {
                            continue;
                        }
                        for (int j = 1; j < ids.length; j++) {
                            if (ids[j] <= ids[j - 1]) {
                                throw new IOException(data + ": block at " + off + " not in ascending ID order");
                            }
                        }
                        int i = minIds.size();
                        if (i == types.length) {
                            types = Arrays.copyOf(types, types.length * 2);
                        }
                        types[i] = t;
                        minIds.add(ids[0]);
                        maxIds.add(ids[ids.length - 1]);
                        offsets.add(off);
                        if (i > 0 && (types[i - 1] > t || (types[i - 1] == t && maxIds.get(i - 1) >= ids[0]))) {
                            throw new IOException(data + ": block at " + off + " out of (type, id) order");
                        }
                    }
                    for (PbfBlock.Node n : b.nodes) {
                        nodeIds.add(n.id());
                    }
                    for (PbfBlock.Way w : b.ways) {
                        wayIds.add(w.id());
                        if (!w.hasLocations()) {
                            badWays.add(w.id());
                        }
                    }
                    for (PbfBlock.Relation r : b.relations) {
                        relIds.add(r.id());
                        boolean complete = true;
                        for (int m = 0; m < r.memberIds().length && complete; m++) {
                            long mid = r.memberIds()[m];
                            switch (r.memberTypes()[m]) {
                                case PbfBlock.NODE -> complete = contains(nodeIds, mid);
                                case PbfBlock.WAY -> complete = contains(wayIds, mid) && !contains(badWays, mid);
                                case PbfBlock.RELATION -> {
                                    relRelPairs.add(r.id());
                                    relRelPairs.add(mid);
                                }
                                default -> complete = false;
                            }
                        }
                        if (!complete) {
                            badRels.add(r.id());
                        }
                    }
                } // other blob types: skipped, as the spec says
                off = blob.next();
            }
        }
        if (header == null || dataBlocks == 0) {
            throw new IOException(data + ": no OSMHeader or no data blocks");
        }

        long[] relIdArr = relIds.toArray();
        LongList moreBad = new LongList();
        for (int i = 0; i < relRelPairs.size(); i += 2) {
            if (Arrays.binarySearch(relIdArr, relRelPairs.get(i + 1)) < 0) {
                moreBad.add(relRelPairs.get(i));
            }
        }
        long[] incompleteRels = union(badRels.toArray(), moreBad.toArray());
        long[] incompleteWays = badWays.toArray();

        DumpFiles.writeBlocks(dir.resolve(DumpFiles.BLOCKS), Arrays.copyOf(types, minIds.size()),
                minIds, maxIds, offsets);
        DumpFiles.writeIncomplete(dir.resolve(DumpFiles.INCOMPLETE), incompleteWays, incompleteRels);

        Result res = new Result(minIds.size(), nodeIds.size(), wayIds.size(), relIds.size(),
                incompleteWays.length, incompleteRels.length);
        Map<String, String> m = new LinkedHashMap<>();
        m.put("format", DumpFiles.FORMAT);
        m.put("region", region);
        m.put("built", Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString());
        m.put("source", source);
        m.put("replication_timestamp", header.replicationTimestamp() == null ? "" : header.replicationTimestamp());
        m.put("replication_sequence", Long.toString(header.replicationSequence()));
        m.put("blocks", Integer.toString(res.blocks()));
        m.put("nodes", Long.toString(res.nodes()));
        m.put("ways", Long.toString(res.ways()));
        m.put("relations", Long.toString(res.relations()));
        m.put("incomplete_ways", Integer.toString(res.incompleteWays()));
        m.put("incomplete_relations", Integer.toString(res.incompleteRelations()));
        DumpFiles.writeManifest(dir.resolve(DumpFiles.MANIFEST_PENDING), m);
        LOG.info(region + ": " + res);
        return res;
    }

    static void checkHeader(PbfBlock.Header h, Path data) throws IOException {
        for (String f : h.requiredFeatures()) {
            if (!PbfBlock.SUPPORTED_REQUIRED.contains(f)) {
                throw new IOException(data + ": unsupported required feature " + f);
            }
        }
        if (!h.sortedTypeThenId()) {
            throw new IOException(data + ": not Sort.Type_then_ID (run osmium sort)");
        }
        if (!h.locationsOnWays()) {
            throw new IOException(data + ": no LocationsOnWays (run osmium add-locations-to-ways)");
        }
    }

    private static long[] ids(PbfBlock b, byte type) {
        return switch (type) {
            case PbfBlock.NODE -> Arrays.stream(b.nodes).mapToLong(PbfBlock.Node::id).toArray();
            case PbfBlock.WAY -> Arrays.stream(b.ways).mapToLong(PbfBlock.Way::id).toArray();
            default -> Arrays.stream(b.relations).mapToLong(PbfBlock.Relation::id).toArray();
        };
    }

    /** Binary search in a list that was filled in ascending order. */
    private static boolean contains(LongList sorted, long id) {
        int lo = 0;
        int hi = sorted.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long v = sorted.get(mid);
            if (v < id) {
                lo = mid + 1;
            } else if (v > id) {
                hi = mid - 1;
            } else {
                return true;
            }
        }
        return false;
    }

    private static long[] union(long[] a, long[] b) {
        long[] all = new long[a.length + b.length];
        System.arraycopy(a, 0, all, 0, a.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        return Arrays.stream(all).sorted().distinct().toArray();
    }
}
