package com.ontologycentral.osmwrap.dump;

import java.io.IOException;
import java.nio.file.Path;

/**
 * {@code java -jar linked-osm-…-jar-with-dependencies.jar dump <command> …}, the build side
 * of the dumps (run by {@code refresh.sh} on lithium):
 *
 * <pre>
 *   dump index DIR REGION SOURCE   blocks.idx, incomplete.idx, MANIFEST.pending for DIR/data.osm.pbf
 *   dump check DIR REGION          the webapp's own completeness test; exit 1 with the reason
 *   dump get DIR TYPE ID           the element as OSM API XML, as the webapp would serve it
 * </pre>
 *
 * Exit codes: 0 ok, 1 failed (or, for get, not served from this dump), 2 usage.
 */
public final class DumpTool {

    private DumpTool() {}

    public static int run(String[] args) {
        if (args.length == 0) {
            return usage();
        }
        try {
            switch (args[0]) {
                case "index": {
                    if (args.length != 4) {
                        return usage();
                    }
                    DumpIndexer.Result r = DumpIndexer.index(Path.of(args[1]), args[2], args[3]);
                    System.out.println(r);
                    return 0;
                }
                case "check": {
                    if (args.length != 3) {
                        return usage();
                    }
                    String problem = DumpRegion.check(Path.of(args[1]), args[2]);
                    if (problem != null) {
                        System.err.println("check failed: " + problem);
                        return 1;
                    }
                    System.out.println("ok");
                    return 0;
                }
                case "get": {
                    if (args.length != 4) {
                        return usage();
                    }
                    Path dir = Path.of(args[1]);
                    String region = DumpFiles.readManifest(dir.resolve(DumpFiles.MANIFEST)).get("region");
                    try (DumpRegion r = DumpRegion.open(region, dir, 64L << 20)) {
                        byte[] xml = r.osmXml(args[2], Long.parseLong(args[3]));
                        if (xml == null) {
                            System.err.println(args[2] + "/" + args[3] + ": not served from this dump");
                            return 1;
                        }
                        System.out.write(xml);
                        System.out.println();
                        System.out.flush();
                        return 0;
                    }
                }
                default:
                    return usage();
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("dump " + args[0] + " failed: " + e);
            return 1;
        }
    }

    private static int usage() {
        System.err.println("usage: dump index DIR REGION SOURCE | dump check DIR REGION | dump get DIR TYPE ID");
        return 2;
    }
}
