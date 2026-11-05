package com.ontologycentral.osmwrap.dump;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The files of one dump directory ({@code $DUMPS_ROOT/osm/<region>/dump-<stamp>/}) and
 * their formats. All integers big-endian, like linked-adv's {@code DumpPack}.
 *
 * <ul>
 *   <li>{@code data.osm.pbf}: the region's extract after {@code osmium add-locations-to-ways
 *       --keep-member-nodes}, zlib blocks, {@code Sort.Type_then_ID}.
 *   <li>{@code blocks.idx}: magic {@code OSMBLK01}, {@code u64 count}, then {@code count}
 *       32-byte entries {@code (u8 type, 7 bytes padding, i64 minId, i64 maxId, i64 offset)}
 *       in file order, which is also (type, id) order. {@code offset} is the block's
 *       BlobHeader length prefix. A block holding several types has one entry per type.
 *   <li>{@code incomplete.idx}: magic {@code OSMINC01}, {@code u64 ways}, {@code u64
 *       relations}, then that many sorted way IDs and relation IDs. A way is incomplete when a
 *       node location is missing, a relation when any member is not in the file (or is an
 *       incomplete way). Incomplete elements are never served from the dump.
 *   <li>{@code MANIFEST}: {@code key=value} lines, written last ({@link #MANIFEST_KEYS}).
 * </ul>
 */
public final class DumpFiles {

    public static final String DATA = "data.osm.pbf";
    public static final String BLOCKS = "blocks.idx";
    public static final String INCOMPLETE = "incomplete.idx";
    public static final String MANIFEST = "MANIFEST";
    public static final String MANIFEST_PENDING = "MANIFEST.pending";

    /** The format this code reads and writes ({@code format=} in MANIFEST). */
    public static final String FORMAT = "1";

    /** Keys every MANIFEST carries. */
    public static final String[] MANIFEST_KEYS = {
        "format", "region", "built", "source", "replication_timestamp", "replication_sequence",
        "blocks", "nodes", "ways", "relations", "incomplete_ways", "incomplete_relations",
    };

    static final byte[] BLOCKS_MAGIC = "OSMBLK01".getBytes(StandardCharsets.US_ASCII);
    static final byte[] INCOMPLETE_MAGIC = "OSMINC01".getBytes(StandardCharsets.US_ASCII);

    private DumpFiles() {}

    /** The block index, held in four parallel arrays (a few MB even for the planet). */
    public record Blocks(byte[] types, long[] minIds, long[] maxIds, long[] offsets) {

        public int size() {
            return types.length;
        }

        /** Index of the block holding (type, id), or -1. */
        public int find(byte type, long id) {
            int lo = 0;
            int hi = types.length - 1;
            int found = -1;
            // first entry with (types[i], maxIds[i]) >= (type, id)
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (types[mid] < type || (types[mid] == type && maxIds[mid] < id)) {
                    lo = mid + 1;
                } else {
                    found = mid;
                    hi = mid - 1;
                }
            }
            return found >= 0 && types[found] == type && minIds[found] <= id ? found : -1;
        }
    }

    /** Two sorted ID arrays. */
    public record Incomplete(long[] ways, long[] relations) {

        public boolean way(long id) {
            return Arrays.binarySearch(ways, id) >= 0;
        }

        public boolean relation(long id) {
            return Arrays.binarySearch(relations, id) >= 0;
        }
    }

    static void writeBlocks(Path p, byte[] types, LongList minIds, LongList maxIds, LongList offsets)
            throws IOException {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(p)))) {
            out.write(BLOCKS_MAGIC);
            out.writeLong(minIds.size());
            for (int i = 0; i < minIds.size(); i++) {
                out.writeByte(types[i]);
                out.write(new byte[7]);
                out.writeLong(minIds.get(i));
                out.writeLong(maxIds.get(i));
                out.writeLong(offsets.get(i));
            }
        }
    }

    public static Blocks readBlocks(Path p) throws IOException {
        try (DataInputStream in = open(p, BLOCKS_MAGIC)) {
            int n = count(in.readLong(), p);
            byte[] types = new byte[n];
            long[] min = new long[n];
            long[] max = new long[n];
            long[] off = new long[n];
            for (int i = 0; i < n; i++) {
                types[i] = in.readByte();
                in.skipNBytes(7);
                min[i] = in.readLong();
                max[i] = in.readLong();
                off[i] = in.readLong();
                if (i > 0 && (types[i] < types[i - 1] || (types[i] == types[i - 1] && min[i] <= max[i - 1]))) {
                    throw new IOException(p + ": entry " + i + " out of (type, id) order");
                }
            }
            return new Blocks(types, min, max, off);
        }
    }

    static void writeIncomplete(Path p, long[] ways, long[] relations) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(p)))) {
            out.write(INCOMPLETE_MAGIC);
            out.writeLong(ways.length);
            out.writeLong(relations.length);
            for (long id : ways) {
                out.writeLong(id);
            }
            for (long id : relations) {
                out.writeLong(id);
            }
        }
    }

    public static Incomplete readIncomplete(Path p) throws IOException {
        try (DataInputStream in = open(p, INCOMPLETE_MAGIC)) {
            int nw = count(in.readLong(), p);
            int nr = count(in.readLong(), p);
            long[] ways = new long[nw];
            long[] rels = new long[nr];
            for (int i = 0; i < nw; i++) {
                ways[i] = in.readLong();
            }
            for (int i = 0; i < nr; i++) {
                rels[i] = in.readLong();
            }
            return new Incomplete(ways, rels);
        }
    }

    static void writeManifest(Path p, Map<String, String> m) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        Files.writeString(p, sb.toString(), StandardCharsets.UTF_8);
    }

    /** {@code key=value} lines; blank lines and {@code #} comments ignored. */
    public static Map<String, String> readManifest(Path p) throws IOException {
        Map<String, String> m = new LinkedHashMap<>();
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                throw new IOException(p + ": not key=value: " + line);
            }
            m.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        return m;
    }

    private static DataInputStream open(Path p, byte[] magic) throws IOException {
        InputStream raw = new java.io.BufferedInputStream(Files.newInputStream(p));
        DataInputStream in = new DataInputStream(raw);
        byte[] m = in.readNBytes(magic.length);
        if (!Arrays.equals(m, magic)) {
            in.close();
            throw new IOException(p + ": not a " + new String(magic, StandardCharsets.US_ASCII) + " file");
        }
        return in;
    }

    private static int count(long n, Path p) throws IOException {
        if (n < 0 || n > Integer.MAX_VALUE - 8) {
            throw new IOException(p + ": bad count " + n);
        }
        return (int) n;
    }
}
