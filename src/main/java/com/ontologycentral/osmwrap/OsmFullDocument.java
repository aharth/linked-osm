package com.ontologycentral.osmwrap;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;

/**
 * A single-pass StAX parse of an OSM API {@code /relation/{id}/full} response.
 *
 * <p>The {@code /full} document can be tens of megabytes (relation 51477 is 34 MB); the old
 * approach read it into one Java string and then ran several whole-document regex scans plus
 * a Saxon DOM build over it. This class instead streams the bytes once with
 * {@link XMLStreamReader} and keeps only what the wrapper actually needs:
 *
 * <ul>
 *   <li>{@link #nodes()}: node id → {@code [lon, lat]} for every {@code <node>} that has
 *       coordinates. A node can be referenced by a {@code <nd>}/{@code <member>} that appears
 *       before the node's own definition, so lookups must wait until the pass has finished;
 *       that is why coordinates are collected into a map rather than resolved on the fly.</li>
 *   <li>{@link #ways()}: way id → ordered node refs for every {@code <way>}.</li>
 *   <li>{@link #relations()}: every {@code <relation>} (members + tags), in document order.
 *       The primary relation is not necessarily first or last: member relations are listed
 *       too.</li>
 *   <li>{@link #strippedXml()}: the same document with every {@code <node>} and {@code <way>}
 *       subtree removed. This is what the relation XSLT stylesheets are given, so Saxon only
 *       ever builds a tree of the relation elements themselves.</li>
 * </ul>
 *
 * Peak memory is proportional to the number of distinct nodes and node refs, not to the raw
 * markup size.
 */
public final class OsmFullDocument {

    /** One {@code <member>} of a relation. */
    public record Member(String type, String ref, String role) {}

    /** One {@code <relation>} element: its attributes, members and tags. */
    public static final class Relation {
        private final String id;
        private final Map<String, String> attributes = new LinkedHashMap<>();
        private final List<Member> members = new ArrayList<>();
        private final List<String[]> tags = new ArrayList<>();

        Relation(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        /** Element attributes ({@code version}, {@code changeset}, {@code timestamp}, ...). */
        public Map<String, String> attributes() {
            return Collections.unmodifiableMap(attributes);
        }

        public List<Member> members() {
            return Collections.unmodifiableList(members);
        }

        /** Tags as {@code {k, v}} pairs, in document order. */
        public List<String[]> tags() {
            return Collections.unmodifiableList(tags);
        }

        public String tag(String key) {
            for (String[] kv : tags) {
                if (kv[0].equals(key)) return kv[1];
            }
            return null;
        }
    }

    private final Map<String, double[]> nodes;
    private final Map<String, List<String>> ways;
    private final Map<String, Relation> relations;
    private final String strippedXml;
    private final double[] centroid;

    private OsmFullDocument(Map<String, double[]> nodes, Map<String, List<String>> ways,
            Map<String, Relation> relations, String strippedXml, double[] centroid) {
        this.nodes = nodes;
        this.ways = ways;
        this.relations = relations;
        this.strippedXml = strippedXml;
        this.centroid = centroid;
    }

    /** Node id → {@code [lon, lat]}. */
    public Map<String, double[]> nodes() {
        return Collections.unmodifiableMap(nodes);
    }

    /** Way id → node refs in order. */
    public Map<String, List<String>> ways() {
        return Collections.unmodifiableMap(ways);
    }

    /** Relation id → relation, in document order. */
    public Map<String, Relation> relations() {
        return Collections.unmodifiableMap(relations);
    }

    public Relation relation(String id) {
        return relations.get(id);
    }

    /** The document with all {@code <node>} and {@code <way>} subtrees removed. */
    public String strippedXml() {
        return strippedXml;
    }

    /**
     * Mean {@code [lon, lat]} over every node in the document, or {@code null} if there are
     * none. Matches what {@code relation.xsl} used to compute as
     * {@code sum(//node/@lat) div count(//node)}.
     */
    public double[] centroid() {
        return centroid;
    }

    /**
     * Parse a {@code /full} response. The stream is read exactly once and is not closed.
     *
     * @throws IOException if the XML is not well-formed
     */
    public static OsmFullDocument parse(InputStream in) throws IOException {
        XMLInputFactory inf = XMLInputFactory.newFactory();
        inf.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        inf.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        inf.setProperty(XMLInputFactory.IS_COALESCING, Boolean.TRUE);

        Map<String, double[]> nodes = new HashMap<>();
        Map<String, List<String>> ways = new HashMap<>();
        Map<String, Relation> relations = new LinkedHashMap<>();
        StringWriter sw = new StringWriter();
        double sumLon = 0;
        double sumLat = 0;

        try {
            XMLStreamReader r = inf.createXMLStreamReader(in);
            XMLStreamWriter w = XMLOutputFactory.newFactory().createXMLStreamWriter(sw);
            w.writeStartDocument("UTF-8", "1.0");
            // Stack of relations currently open (a <relation> only ever nests inside <osm>,
            // but a stack keeps the copy logic independent of that assumption).
            List<Relation> open = new ArrayList<>();
            int depth = 0;

            while (r.hasNext()) {
                int ev = r.next();
                switch (ev) {
                    case XMLStreamConstants.START_ELEMENT: {
                        String name = r.getLocalName();
                        if (depth == 1 && "node".equals(name)) {
                            String id = r.getAttributeValue(null, "id");
                            String lat = r.getAttributeValue(null, "lat");
                            String lon = r.getAttributeValue(null, "lon");
                            if (id != null && lat != null && lon != null) {
                                try {
                                    double[] c = {Double.parseDouble(lon), Double.parseDouble(lat)};
                                    nodes.put(id, c);
                                    sumLon += c[0];
                                    sumLat += c[1];
                                } catch (NumberFormatException e) {
                                    // skip node with unparseable coordinates
                                }
                            }
                            skipSubtree(r, null);
                            continue;
                        }
                        if (depth == 1 && "way".equals(name)) {
                            String id = r.getAttributeValue(null, "id");
                            List<String> refs = new ArrayList<>();
                            skipSubtree(r, refs);
                            if (id != null) ways.put(id, refs);
                            continue;
                        }
                        depth++;
                        copyStartElement(r, w);
                        Relation current = open.isEmpty() ? null : open.get(open.size() - 1);
                        if ("relation".equals(name)) {
                            Relation rel = new Relation(r.getAttributeValue(null, "id"));
                            for (int i = 0; i < r.getAttributeCount(); i++) {
                                rel.attributes.put(r.getAttributeLocalName(i), r.getAttributeValue(i));
                            }
                            if (rel.id != null) relations.put(rel.id, rel);
                            open.add(rel);
                        } else if (current != null && "member".equals(name)) {
                            current.members.add(new Member(
                                    r.getAttributeValue(null, "type"),
                                    r.getAttributeValue(null, "ref"),
                                    r.getAttributeValue(null, "role")));
                        } else if (current != null && "tag".equals(name)) {
                            String k = r.getAttributeValue(null, "k");
                            String v = r.getAttributeValue(null, "v");
                            if (k != null) current.tags.add(new String[]{k, v == null ? "" : v});
                        }
                        break;
                    }
                    case XMLStreamConstants.END_ELEMENT:
                        depth--;
                        if ("relation".equals(r.getLocalName()) && !open.isEmpty()) {
                            open.remove(open.size() - 1);
                        }
                        w.writeEndElement();
                        break;
                    case XMLStreamConstants.CHARACTERS:
                    case XMLStreamConstants.CDATA:
                        if (!r.isWhiteSpace()) {
                            w.writeCharacters(r.getTextCharacters(), r.getTextStart(), r.getTextLength());
                        }
                        break;
                    default:
                        // comments, PIs, whitespace, document events: not needed downstream
                        break;
                }
            }
            w.writeEndDocument();
            w.close();
            r.close();
        } catch (XMLStreamException e) {
            throw new IOException("malformed OSM XML: " + e.getMessage(), e);
        }

        double[] centroid = nodes.isEmpty() ? null
                : new double[]{sumLon / nodes.size(), sumLat / nodes.size()};
        return new OsmFullDocument(nodes, ways, relations, sw.toString(), centroid);
    }

    /**
     * Consume events up to and including the end of the element the reader is currently on.
     * If {@code ndRefs} is non-null, every {@code <nd ref="..."/>} seen on the way is added.
     */
    private static void skipSubtree(XMLStreamReader r, List<String> ndRefs) throws XMLStreamException {
        int level = 1;
        while (level > 0 && r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                level++;
                if (ndRefs != null && "nd".equals(r.getLocalName())) {
                    String ref = r.getAttributeValue(null, "ref");
                    if (ref != null) ndRefs.add(ref);
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                level--;
            }
        }
    }

    private static void copyStartElement(XMLStreamReader r, XMLStreamWriter w) throws XMLStreamException {
        String ns = r.getNamespaceURI();
        String prefix = r.getPrefix();
        if (ns == null || ns.isEmpty()) {
            w.writeStartElement(r.getLocalName());
        } else {
            w.writeStartElement(prefix == null ? "" : prefix, r.getLocalName(), ns);
        }
        for (int i = 0; i < r.getNamespaceCount(); i++) {
            String p = r.getNamespacePrefix(i);
            if (p == null || p.isEmpty()) {
                w.writeDefaultNamespace(r.getNamespaceURI(i));
            } else {
                w.writeNamespace(p, r.getNamespaceURI(i));
            }
        }
        for (int i = 0; i < r.getAttributeCount(); i++) {
            String ans = r.getAttributeNamespace(i);
            if (ans == null || ans.isEmpty()) {
                w.writeAttribute(r.getAttributeLocalName(i), r.getAttributeValue(i));
            } else {
                w.writeAttribute(r.getAttributePrefix(i), ans, r.getAttributeLocalName(i), r.getAttributeValue(i));
            }
        }
    }
}
