package com.ontologycentral.osmwrap.dump;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;

/**
 * The dumps this webapp serves: every region in {@code dumps.json} marked
 * {@code "integrated": true}, read from {@code <dumps-dir>/osm/<region>/dump}. Opened once
 * at startup; a rebuilt dump goes live on the next reload. A region whose dump is missing or
 * fails {@link DumpRegion#check} is logged and skipped: here the dump is only an accelerator
 * in front of the OSM API, which answers everything it doesn't.
 */
public final class DumpStore implements Closeable {

    /** ServletContext attribute holding the store (absent when dumps are off). */
    public static final String ATTR = "osmwrap.dumps";

    /** linked-osm's fixed subdirectory of the family's dumps root. */
    public static final String SUBDIR = "osm";

    /** Decoded-block cache per region. */
    private static final long CACHE_BYTES = 256L * 1024 * 1024;

    /** A dump answer: which region, and the OSM API-shaped XML. */
    public record Hit(DumpRegion region, byte[] xml) {}

    private final List<DumpRegion> regions;

    private DumpStore(List<DumpRegion> regions) {
        this.regions = List.copyOf(regions);
    }

    /** {@code dumps.json} from the classpath: region name to its entry. */
    public static Map<String, JsonObject> registry() throws IOException {
        try (InputStream in = DumpStore.class.getResourceAsStream("/dumps.json")) {
            if (in == null) {
                return Map.of();
            }
            JsonObject root = Json.createReader(in).readObject();
            Map<String, JsonObject> out = new LinkedHashMap<>();
            for (Map.Entry<String, JsonValue> e : root.getJsonObject("regions").entrySet()) {
                out.put(e.getKey(), e.getValue().asJsonObject());
            }
            return out;
        } catch (RuntimeException e) {
            throw new IOException("dumps.json: " + e.getMessage(), e);
        }
    }

    /** The live dump directory of a region: {@code <dumpsDir>/osm/<region>/dump}. */
    public static Path root(Path dumpsDir, String region) {
        return dumpsDir.resolve(SUBDIR).resolve(region).resolve("dump");
    }

    /** Opens every integrated region that passes its check; never throws for a bad region. */
    public static DumpStore open(Path dumpsDir, Consumer<String> log) throws IOException {
        List<DumpRegion> opened = new ArrayList<>();
        for (Map.Entry<String, JsonObject> e : registry().entrySet()) {
            String region = e.getKey();
            if (!e.getValue().getBoolean("integrated", false)) {
                continue;
            }
            Path dir = root(dumpsDir, region);
            String problem = DumpRegion.check(dir, region);
            if (problem != null) {
                log.accept("dump " + region + " skipped: " + problem);
                continue;
            }
            try {
                DumpRegion r = DumpRegion.open(region, dir.toRealPath(), CACHE_BYTES);
                opened.add(r);
                log.accept("dump " + region + " open: " + r.dir() + ", replication "
                        + r.manifest().get("replication_timestamp") + ", " + r.manifest().get("blocks") + " blocks");
            } catch (IOException ex) {
                log.accept("dump " + region + " skipped: " + ex.getMessage());
            }
        }
        return new DumpStore(opened);
    }

    /** A store over regions already opened (tests). */
    static DumpStore of(List<DumpRegion> regions) {
        return new DumpStore(regions);
    }

    public List<DumpRegion> regions() {
        return regions;
    }

    /**
     * The element from the first region that has it complete, or null. A dump that fails to
     * read is reported to {@code log} and treated as a miss, so the caller goes live.
     */
    public Hit lookup(String type, String id, Consumer<String> log) {
        long n;
        try {
            n = Long.parseLong(id);
        } catch (NumberFormatException e) {
            return null;
        }
        if (n <= 0) {
            return null;
        }
        for (DumpRegion r : regions) {
            try {
                byte[] xml = r.osmXml(type, n);
                if (xml != null) {
                    return new Hit(r, xml);
                }
            } catch (IOException | RuntimeException e) {
                log.accept("dump " + r.name() + " failed on " + type + "/" + id + ": " + e);
            }
        }
        return null;
    }

    @Override
    public void close() throws IOException {
        for (DumpRegion r : regions) {
            r.close();
        }
    }
}
