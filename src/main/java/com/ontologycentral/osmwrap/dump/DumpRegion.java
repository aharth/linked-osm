package com.ontologycentral.osmwrap.dump;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * One opened dump: its PBF, block index, incomplete set and MANIFEST, plus a cache of decoded
 * blocks. {@link #osmXml} answers with the bytes an OSM API 0.6 response for the element would
 * have ({@code /node/{id}}, {@code /way/{id}/full}, {@code /relation/{id}/full}), so
 * {@code OsmDocument.parse} and everything after it run unchanged.
 */
public final class DumpRegion implements Closeable {

    private final String name;
    private final Path dir;
    private final Map<String, String> manifest;
    private final PbfFile pbf;
    private final DumpFiles.Blocks blocks;
    private final DumpFiles.Incomplete incomplete;
    private final Cache<Integer, PbfBlock> cache;
    private final String generator;

    private DumpRegion(String name, Path dir, Map<String, String> manifest, PbfFile pbf,
            DumpFiles.Blocks blocks, DumpFiles.Incomplete incomplete, long cacheBytes) {
        this.name = name;
        this.dir = dir;
        this.manifest = manifest;
        this.pbf = pbf;
        this.blocks = blocks;
        this.incomplete = incomplete;
        // Weight: a decoded block is a few times its inflated size; weigh by entry count
        // as a proxy (~8000 per block), at ~400 bytes per element.
        this.cache = Caffeine.newBuilder()
                .maximumWeight(Math.max(1, cacheBytes / 400))
                .weigher((Integer k, PbfBlock b) -> b.nodes.length + b.ways.length + b.relations.length + 1)
                .build();
        String ts = manifest.getOrDefault("replication_timestamp", "");
        this.generator = "Linked OSM dump " + name + " (" + manifest.get("source")
                + (ts.isEmpty() ? "" : ", replication " + ts) + ")";
    }

    /**
     * Checks a dump directory the way the webapp will use it (the {@code dump check} CLI and
     * {@code refresh.sh} run exactly this). Returns null when complete, otherwise the reason.
     */
    public static String check(Path dir, String region) {
        try {
            Path mf = dir.resolve(DumpFiles.MANIFEST);
            if (!Files.isRegularFile(mf)) {
                return "no " + DumpFiles.MANIFEST + " in " + dir;
            }
            Map<String, String> m = DumpFiles.readManifest(mf);
            for (String k : DumpFiles.MANIFEST_KEYS) {
                if (!m.containsKey(k)) {
                    return "MANIFEST lacks " + k;
                }
            }
            if (!DumpFiles.FORMAT.equals(m.get("format"))) {
                return "MANIFEST format " + m.get("format") + ", this code reads " + DumpFiles.FORMAT;
            }
            if (!region.equals(m.get("region"))) {
                return "MANIFEST region " + m.get("region") + ", expected " + region;
            }
            if (parse(m.get("blocks")) <= 0) {
                return "MANIFEST blocks " + m.get("blocks");
            }
            for (String f : new String[] {DumpFiles.DATA, DumpFiles.BLOCKS, DumpFiles.INCOMPLETE}) {
                if (!Files.isRegularFile(dir.resolve(f))) {
                    return "no " + f + " in " + dir;
                }
            }
            try (DumpRegion r = open(region, dir, 1L << 20)) {
                if (r.blocks.size() != parse(m.get("blocks"))) {
                    return DumpFiles.BLOCKS + " has " + r.blocks.size() + " entries, MANIFEST says " + m.get("blocks");
                }
                PbfBlock first = r.block(0);
                if (first.nodes.length + first.ways.length + first.relations.length == 0) {
                    return "first data block is empty";
                }
            }
            return null;
        } catch (IOException | RuntimeException e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private static long parse(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Opens a dump directory (already {@link #check checked}). */
    public static DumpRegion open(String region, Path dir, long cacheBytes) throws IOException {
        Map<String, String> m = DumpFiles.readManifest(dir.resolve(DumpFiles.MANIFEST));
        DumpFiles.Blocks blocks = DumpFiles.readBlocks(dir.resolve(DumpFiles.BLOCKS));
        DumpFiles.Incomplete inc = DumpFiles.readIncomplete(dir.resolve(DumpFiles.INCOMPLETE));
        PbfFile pbf = new PbfFile(dir.resolve(DumpFiles.DATA));
        return new DumpRegion(region, dir, m, pbf, blocks, inc, cacheBytes);
    }

    public String name() {
        return name;
    }

    public Path dir() {
        return dir;
    }

    public Map<String, String> manifest() {
        return manifest;
    }

    // ------------------------------------------------------------------ lookup

    PbfBlock block(int i) {
        return cache.get(i, k -> {
            try {
                PbfFile.Blob blob = pbf.read(blocks.offsets()[k]);
                if (!"OSMData".equals(blob.type())) {
                    throw new IOException("block index entry " + k + " points at a " + blob.type() + " blob");
                }
                return PbfBlock.decode(blob.data());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    public PbfBlock.Node node(long id) throws IOException {
        int i = blocks.find(PbfBlock.NODE, id);
        return i < 0 ? null : unchecked(() -> block(i).node(id));
    }

    public PbfBlock.Way way(long id) throws IOException {
        int i = blocks.find(PbfBlock.WAY, id);
        return i < 0 ? null : unchecked(() -> block(i).way(id));
    }

    public PbfBlock.Relation relation(long id) throws IOException {
        int i = blocks.find(PbfBlock.RELATION, id);
        return i < 0 ? null : unchecked(() -> block(i).relation(id));
    }

    private interface Lookup<T> {
        T get();
    }

    private static <T> T unchecked(Lookup<T> f) throws IOException {
        try {
            return f.get();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /**
     * The element as OSM API XML, or null when this dump can't serve it completely: not in
     * the file (outside the region, or an untagged node dropped by add-locations-to-ways), an
     * incomplete way or relation, or a member that fails to resolve.
     *
     * @param type {@code node}, {@code way} or {@code relation}
     */
    public byte[] osmXml(String type, long id) throws IOException {
        switch (type) {
            case "node": {
                PbfBlock.Node n = node(id);
                if (n == null) {
                    return null;
                }
                return write(w -> writeNode(w, n));
            }
            case "way": {
                if (incomplete.way(id)) {
                    return null;
                }
                PbfBlock.Way way = way(id);
                if (way == null || !way.hasLocations()) {
                    return null;
                }
                return write(w -> {
                    writeWayNodes(w, way, new java.util.HashSet<>());
                    writeWay(w, way);
                });
            }
            case "relation": {
                if (incomplete.relation(id)) {
                    return null;
                }
                PbfBlock.Relation rel = relation(id);
                if (rel == null) {
                    return null;
                }
                Map<Long, PbfBlock.Node> nodes = new LinkedHashMap<>();
                Map<Long, PbfBlock.Way> ways = new LinkedHashMap<>();
                Map<Long, PbfBlock.Relation> rels = new LinkedHashMap<>();
                for (int m = 0; m < rel.memberIds().length; m++) {
                    long mid = rel.memberIds()[m];
                    switch (rel.memberTypes()[m]) {
                        case PbfBlock.NODE -> {
                            if (!nodes.containsKey(mid)) {
                                PbfBlock.Node n = node(mid);
                                if (n == null) {
                                    return null;
                                }
                                nodes.put(mid, n);
                            }
                        }
                        case PbfBlock.WAY -> {
                            if (!ways.containsKey(mid)) {
                                PbfBlock.Way way = way(mid);
                                if (way == null || !way.hasLocations()) {
                                    return null;
                                }
                                ways.put(mid, way);
                            }
                        }
                        case PbfBlock.RELATION -> {
                            if (mid != id && !rels.containsKey(mid)) {
                                PbfBlock.Relation r = relation(mid);
                                if (r == null) {
                                    return null;
                                }
                                rels.put(mid, r);
                            }
                        }
                        default -> {
                            return null;
                        }
                    }
                }
                return write(w -> {
                    // OSM API /full order: nodes, ways, relations (member relations, then the
                    // relation itself somewhere among them; here last).
                    java.util.Set<Long> written = new java.util.HashSet<>();
                    for (PbfBlock.Node n : nodes.values()) {
                        writeNode(w, n);
                        written.add(n.id());
                    }
                    for (PbfBlock.Way way : ways.values()) {
                        writeWayNodes(w, way, written);
                    }
                    for (PbfBlock.Way way : ways.values()) {
                        writeWay(w, way);
                    }
                    for (PbfBlock.Relation r : rels.values()) {
                        writeRelation(w, r);
                    }
                    writeRelation(w, rel);
                });
            }
            default:
                throw new IllegalArgumentException("element type " + type);
        }
    }

    // ------------------------------------------------------------------ XML

    private interface Body {
        void write(XMLStreamWriter w) throws XMLStreamException;
    }

    private static final XMLOutputFactory XOF = XMLOutputFactory.newFactory();

    private byte[] write(Body body) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(4096);
        try {
            XMLStreamWriter w = XOF.createXMLStreamWriter(bos, "UTF-8");
            w.writeStartDocument("UTF-8", "1.0");
            w.writeStartElement("osm");
            w.writeAttribute("version", "0.6");
            w.writeAttribute("generator", generator);
            body.write(w);
            w.writeEndElement();
            w.writeEndDocument();
            w.close();
        } catch (XMLStreamException e) {
            throw new IOException(e.getMessage(), e);
        }
        return bos.toByteArray();
    }

    /** A coordinate in 1e-7 degrees as the OSM API prints it: exactly seven decimals. */
    static String coord(int v7) {
        return BigDecimal.valueOf(v7, 7).toPlainString();
    }

    private static void writeInfo(XMLStreamWriter w, long id, PbfBlock.Info info) throws XMLStreamException {
        w.writeAttribute("id", Long.toString(id));
        w.writeAttribute("visible", "true");
        if (info.version() > 0) {
            w.writeAttribute("version", Integer.toString(info.version()));
        }
        if (info.changeset() > 0) {
            w.writeAttribute("changeset", Long.toString(info.changeset()));
        }
        if (info.timestamp() != null) {
            w.writeAttribute("timestamp", info.timestamp());
        }
        if (info.user() != null) {
            w.writeAttribute("user", info.user());
        }
        if (info.uid() > 0) {
            w.writeAttribute("uid", Long.toString(info.uid()));
        }
    }

    private static void writeTags(XMLStreamWriter w, String[] tags) throws XMLStreamException {
        for (int i = 0; i + 1 < tags.length; i += 2) {
            w.writeEmptyElement("tag");
            w.writeAttribute("k", tags[i]);
            w.writeAttribute("v", tags[i + 1]);
        }
    }

    private static void writeNode(XMLStreamWriter w, PbfBlock.Node n) throws XMLStreamException {
        if (n.tags().length == 0) {
            w.writeEmptyElement("node");
        } else {
            w.writeStartElement("node");
        }
        writeInfo(w, n.id(), n.info());
        w.writeAttribute("lat", coord(n.lat7()));
        w.writeAttribute("lon", coord(n.lon7()));
        if (n.tags().length > 0) {
            writeTags(w, n.tags());
            w.writeEndElement();
        }
    }

    /** The way's nodes from its own locations; only coordinates, as the dump has no more. */
    private static void writeWayNodes(XMLStreamWriter w, PbfBlock.Way way, java.util.Set<Long> written)
            throws XMLStreamException {
        long[] refs = way.refs();
        for (int i = 0; i < refs.length; i++) {
            if (written.add(refs[i])) {
                w.writeEmptyElement("node");
                w.writeAttribute("id", Long.toString(refs[i]));
                w.writeAttribute("visible", "true");
                w.writeAttribute("lat", coord(way.lat7()[i]));
                w.writeAttribute("lon", coord(way.lon7()[i]));
            }
        }
    }

    private static void writeWay(XMLStreamWriter w, PbfBlock.Way way) throws XMLStreamException {
        w.writeStartElement("way");
        writeInfo(w, way.id(), way.info());
        for (long ref : way.refs()) {
            w.writeEmptyElement("nd");
            w.writeAttribute("ref", Long.toString(ref));
        }
        writeTags(w, way.tags());
        w.writeEndElement();
    }

    private static void writeRelation(XMLStreamWriter w, PbfBlock.Relation r) throws XMLStreamException {
        w.writeStartElement("relation");
        writeInfo(w, r.id(), r.info());
        for (int m = 0; m < r.memberIds().length; m++) {
            w.writeEmptyElement("member");
            w.writeAttribute("type", PbfBlock.typeName(r.memberTypes()[m]));
            w.writeAttribute("ref", Long.toString(r.memberIds()[m]));
            w.writeAttribute("role", r.roles()[m]);
        }
        writeTags(w, r.tags());
        w.writeEndElement();
    }

    @Override
    public void close() throws IOException {
        pbf.close();
    }

    @Override
    public String toString() {
        return name + " (" + dir + ", " + blocks.size() + " blocks)";
    }
}
