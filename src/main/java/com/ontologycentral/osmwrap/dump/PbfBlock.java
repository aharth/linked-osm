package com.ontologycentral.osmwrap.dump;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One decoded {@code OSMData} block ({@code PrimitiveBlock}): its nodes, ways and relations,
 * each list in file order. In a {@code Sort.Type_then_ID} file that is ascending ID order,
 * which {@link #node}/{@link #way}/{@link #relation} rely on for binary search.
 *
 * <p>Coordinates are kept as integers in units of 1e-7 degrees (the OSM API's precision).
 * Ways carry their node locations when the file has {@code LocationsOnWays}; a location
 * osmium could not resolve is outside ±90/±180 (see {@link #valid}).
 */
public final class PbfBlock {

    /** Element metadata; {@code changeset}/{@code uid} 0 and {@code user} null when absent. */
    public record Info(int version, String timestamp, long changeset, long uid, String user) {
        static final Info NONE = new Info(0, null, 0, 0, null);
    }

    /** Tags as alternating keys and values. */
    public record Node(long id, int lat7, int lon7, Info info, String[] tags) {}

    public record Way(long id, Info info, String[] tags, long[] refs, int[] lat7, int[] lon7) {

        /** True when the file gave this way a valid location for every node. */
        public boolean hasLocations() {
            if (lat7 == null || lat7.length != refs.length) {
                return false;
            }
            for (int i = 0; i < refs.length; i++) {
                if (!valid(lat7[i], lon7[i])) {
                    return false;
                }
            }
            return true;
        }
    }

    /** Member types: 0 node, 1 way, 2 relation (the {@code MemberType} enum). */
    public record Relation(long id, Info info, String[] tags, byte[] memberTypes, long[] memberIds,
            String[] roles) {}

    public static final byte NODE = 0;
    public static final byte WAY = 1;
    public static final byte RELATION = 2;

    final Node[] nodes;
    final Way[] ways;
    final Relation[] relations;

    private PbfBlock(Node[] nodes, Way[] ways, Relation[] relations) {
        this.nodes = nodes;
        this.ways = ways;
        this.relations = relations;
    }

    public static boolean valid(int lat7, int lon7) {
        return lat7 >= -900_000_000 && lat7 <= 900_000_000 && lon7 >= -1_800_000_000 && lon7 <= 1_800_000_000;
    }

    public static String typeName(byte type) {
        return switch (type) {
            case NODE -> "node";
            case WAY -> "way";
            case RELATION -> "relation";
            default -> throw new IllegalArgumentException("element type " + type);
        };
    }

    public static byte typeCode(String name) {
        return switch (name) {
            case "node" -> NODE;
            case "way" -> WAY;
            case "relation" -> RELATION;
            default -> throw new IllegalArgumentException("element type " + name);
        };
    }

    public Node node(long id) {
        int lo = 0;
        int hi = nodes.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long m = nodes[mid].id;
            if (m < id) {
                lo = mid + 1;
            } else if (m > id) {
                hi = mid - 1;
            } else {
                return nodes[mid];
            }
        }
        return null;
    }

    public Way way(long id) {
        int lo = 0;
        int hi = ways.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long m = ways[mid].id;
            if (m < id) {
                lo = mid + 1;
            } else if (m > id) {
                hi = mid - 1;
            } else {
                return ways[mid];
            }
        }
        return null;
    }

    public Relation relation(long id) {
        int lo = 0;
        int hi = relations.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long m = relations[mid].id;
            if (m < id) {
                lo = mid + 1;
            } else if (m > id) {
                hi = mid - 1;
            } else {
                return relations[mid];
            }
        }
        return null;
    }

    public List<Node> nodes() {
        return Arrays.asList(nodes);
    }

    public List<Way> ways() {
        return Arrays.asList(ways);
    }

    public List<Relation> relations() {
        return Arrays.asList(relations);
    }

    // ------------------------------------------------------------------ decoding

    /** Decodes one inflated {@code OSMData} payload. */
    public static PbfBlock decode(byte[] data) {
        ProtoReader r = new ProtoReader(data);
        String[] strings = new String[0];
        List<ProtoReader> groups = new ArrayList<>();
        long granularity = 100;
        long latOffset = 0;
        long lonOffset = 0;
        long dateGranularity = 1000;
        while (r.hasMore()) {
            switch (r.next()) {
                case 1 -> strings = stringTable(r.message());
                case 2 -> groups.add(r.message());
                case 17 -> granularity = r.varint();
                case 18 -> dateGranularity = r.varint();
                case 19 -> latOffset = r.varint();
                case 20 -> lonOffset = r.varint();
                default -> r.skip();
            }
        }
        Scale s = new Scale(granularity, latOffset, lonOffset, dateGranularity, strings);
        List<Node> nodes = new ArrayList<>();
        List<Way> ways = new ArrayList<>();
        List<Relation> relations = new ArrayList<>();
        for (ProtoReader g : groups) {
            while (g.hasMore()) {
                switch (g.next()) {
                    case 1 -> nodes.add(node(g.message(), s));
                    case 2 -> dense(g.message(), s, nodes);
                    case 3 -> ways.add(way(g.message(), s));
                    case 4 -> relations.add(relation(g.message(), s));
                    default -> g.skip();
                }
            }
        }
        return new PbfBlock(nodes.toArray(new Node[0]), ways.toArray(new Way[0]),
                relations.toArray(new Relation[0]));
    }

    /** Per-block scaling and the string table. */
    private record Scale(long granularity, long latOffset, long lonOffset, long dateGranularity,
            String[] strings) {

        /** Nanodegrees to 1e-7 degrees. */
        int lat7(long raw) {
            return (int) Math.floorDiv(latOffset + granularity * raw, 100L);
        }

        int lon7(long raw) {
            return (int) Math.floorDiv(lonOffset + granularity * raw, 100L);
        }

        String timestamp(long raw) {
            return raw == 0 ? null : Instant.ofEpochMilli(raw * dateGranularity).toString();
        }

        String string(long sid) {
            return sid >= 0 && sid < strings.length ? strings[(int) sid] : "";
        }
    }

    private static String[] stringTable(ProtoReader r) {
        List<String> out = new ArrayList<>();
        while (r.hasMore()) {
            if (r.next() == 1) {
                out.add(r.string());
            } else {
                r.skip();
            }
        }
        return out.toArray(new String[0]);
    }

    private static String[] tags(LongList keys, LongList vals, Scale s) {
        int n = Math.min(keys.size(), vals.size());
        String[] tags = new String[2 * n];
        for (int i = 0; i < n; i++) {
            tags[2 * i] = s.string(keys.get(i));
            tags[2 * i + 1] = s.string(vals.get(i));
        }
        return tags;
    }

    private static Info info(ProtoReader r, Scale s) {
        int version = 0;
        long timestamp = 0;
        long changeset = 0;
        long uid = 0;
        long userSid = -1;
        while (r.hasMore()) {
            switch (r.next()) {
                case 1 -> version = (int) r.varint();
                case 2 -> timestamp = r.varint();
                case 3 -> changeset = r.varint();
                case 4 -> uid = r.varint();
                case 5 -> userSid = r.varint();
                default -> r.skip();
            }
        }
        String user = userSid > 0 ? s.string(userSid) : null;
        return new Info(version, s.timestamp(timestamp), changeset, uid, user == null || user.isEmpty() ? null : user);
    }

    private static Node node(ProtoReader r, Scale s) {
        long id = 0;
        long lat = 0;
        long lon = 0;
        LongList keys = new LongList();
        LongList vals = new LongList();
        Info info = Info.NONE;
        while (r.hasMore()) {
            switch (r.next()) {
                case 1 -> id = r.sint();
                case 2 -> r.repeated(keys, false);
                case 3 -> r.repeated(vals, false);
                case 4 -> info = info(r.message(), s);
                case 8 -> lat = r.sint();
                case 9 -> lon = r.sint();
                default -> r.skip();
            }
        }
        return new Node(id, s.lat7(lat), s.lon7(lon), info, tags(keys, vals, s));
    }

    private static void dense(ProtoReader r, Scale s, List<Node> out) {
        LongList ids = new LongList(8192);
        LongList lats = new LongList(8192);
        LongList lons = new LongList(8192);
        LongList kv = new LongList(8192);
        LongList version = new LongList();
        LongList timestamp = new LongList();
        LongList changeset = new LongList();
        LongList uid = new LongList();
        LongList userSid = new LongList();
        while (r.hasMore()) {
            switch (r.next()) {
                case 1 -> r.repeated(ids, true);
                case 5 -> {
                    ProtoReader d = r.message();
                    while (d.hasMore()) {
                        switch (d.next()) {
                            case 1 -> d.repeated(version, false);
                            case 2 -> d.repeated(timestamp, true);
                            case 3 -> d.repeated(changeset, true);
                            case 4 -> d.repeated(uid, true);
                            case 5 -> d.repeated(userSid, true);
                            default -> d.skip();
                        }
                    }
                }
                case 8 -> r.repeated(lats, true);
                case 9 -> r.repeated(lons, true);
                case 10 -> r.repeated(kv, false);
                default -> r.skip();
            }
        }
        ids.undelta();
        lats.undelta();
        lons.undelta();
        timestamp.undelta();
        changeset.undelta();
        uid.undelta();
        userSid.undelta();
        int n = ids.size();
        if (lats.size() != n || lons.size() != n) {
            throw new IllegalStateException("dense nodes: " + n + " ids but " + lats.size() + " lats");
        }
        boolean hasInfo = version.size() == n;
        int k = 0;
        for (int i = 0; i < n; i++) {
            String[] tags;
            if (kv.size() == 0) {
                tags = new String[0];
            } else {
                List<String> t = new ArrayList<>();
                while (k < kv.size() && kv.get(k) != 0) {
                    t.add(s.string(kv.get(k)));
                    t.add(s.string(kv.get(k + 1)));
                    k += 2;
                }
                k++; // the 0 that ends this node's run
                tags = t.toArray(new String[0]);
            }
            Info info = Info.NONE;
            if (hasInfo) {
                long sid = userSid.size() == n ? userSid.get(i) : -1;
                String user = sid > 0 ? s.string(sid) : null;
                info = new Info((int) version.get(i),
                        timestamp.size() == n ? s.timestamp(timestamp.get(i)) : null,
                        changeset.size() == n ? changeset.get(i) : 0,
                        uid.size() == n ? uid.get(i) : 0,
                        user == null || user.isEmpty() ? null : user);
            }
            out.add(new Node(ids.get(i), s.lat7(lats.get(i)), s.lon7(lons.get(i)), info, tags));
        }
    }

    private static Way way(ProtoReader r, Scale s) {
        long id = 0;
        LongList keys = new LongList();
        LongList vals = new LongList();
        LongList refs = new LongList();
        LongList lats = new LongList();
        LongList lons = new LongList();
        Info info = Info.NONE;
        while (r.hasMore()) {
            switch (r.next()) {
                case 1 -> id = r.varint();
                case 2 -> r.repeated(keys, false);
                case 3 -> r.repeated(vals, false);
                case 4 -> info = info(r.message(), s);
                case 8 -> r.repeated(refs, true);
                case 9 -> r.repeated(lats, true);
                case 10 -> r.repeated(lons, true);
                default -> r.skip();
            }
        }
        refs.undelta();
        int[] lat7 = null;
        int[] lon7 = null;
        if (lats.size() > 0) {
            lats.undelta();
            lons.undelta();
            int n = Math.min(lats.size(), lons.size());
            lat7 = new int[n];
            lon7 = new int[n];
            for (int i = 0; i < n; i++) {
                lat7[i] = s.lat7(lats.get(i));
                lon7[i] = s.lon7(lons.get(i));
            }
        }
        return new Way(id, info, tags(keys, vals, s), refs.toArray(), lat7, lon7);
    }

    private static Relation relation(ProtoReader r, Scale s) {
        long id = 0;
        LongList keys = new LongList();
        LongList vals = new LongList();
        LongList roles = new LongList();
        LongList memids = new LongList();
        LongList types = new LongList();
        Info info = Info.NONE;
        while (r.hasMore()) {
            switch (r.next()) {
                case 1 -> id = r.varint();
                case 2 -> r.repeated(keys, false);
                case 3 -> r.repeated(vals, false);
                case 4 -> info = info(r.message(), s);
                case 8 -> r.repeated(roles, false);
                case 9 -> r.repeated(memids, true);
                case 10 -> r.repeated(types, false);
                default -> r.skip();
            }
        }
        memids.undelta();
        int n = memids.size();
        if (types.size() != n || roles.size() != n) {
            throw new IllegalStateException("relation " + id + ": " + n + " members, "
                    + types.size() + " types, " + roles.size() + " roles");
        }
        byte[] t = new byte[n];
        String[] role = new String[n];
        for (int i = 0; i < n; i++) {
            t[i] = (byte) types.get(i);
            role[i] = s.string(roles.get(i));
        }
        return new Relation(id, info, tags(keys, vals, s), t, memids.toArray(), role);
    }

    // ------------------------------------------------------------------ header

    /** The {@code OSMHeader} block: features and replication state. */
    public record Header(List<String> requiredFeatures, List<String> optionalFeatures,
            String replicationTimestamp, long replicationSequence, String replicationBaseUrl) {

        public boolean sortedTypeThenId() {
            return optionalFeatures.contains("Sort.Type_then_ID");
        }

        public boolean locationsOnWays() {
            return optionalFeatures.contains("LocationsOnWays");
        }
    }

    /** Features this reader understands; any other required feature is refused. */
    static final List<String> SUPPORTED_REQUIRED = List.of("OsmSchema-V0.6", "DenseNodes");

    public static Header header(byte[] data) {
        ProtoReader r = new ProtoReader(data);
        List<String> required = new ArrayList<>();
        List<String> optional = new ArrayList<>();
        String ts = null;
        long seq = -1;
        String base = null;
        while (r.hasMore()) {
            switch (r.next()) {
                case 4 -> required.add(r.string());
                case 5 -> optional.add(r.string());
                case 32 -> {
                    long sec = r.varint();
                    ts = sec > 0 ? Instant.ofEpochSecond(sec).toString() : null;
                }
                case 33 -> seq = r.varint();
                case 34 -> base = r.string();
                default -> r.skip();
            }
        }
        return new Header(List.copyOf(required), List.copyOf(optional), ts, seq, base);
    }
}
