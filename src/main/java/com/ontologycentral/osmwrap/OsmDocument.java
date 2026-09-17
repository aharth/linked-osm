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
 * A single-pass StAX parse of an OSM API element response: {@code /node/{id}},
 * {@code /way/{id}/full} or {@code /relation/{id}/full}.
 *
 * <p>The same parse serves all three element types. A {@code /full} document can be tens
 * of megabytes (relation 51477 is 34 MB), so it is streamed once and only what the wrapper
 * needs is kept:
 *
 * <ul>
 *   <li>{@link #nodes()}: node id → {@code [lon, lat]} for every {@code <node>} with
 *       coordinates. A node can be referenced by a {@code <nd>}/{@code <member>} that
 *       appears before the node's own definition, so lookups wait until the pass is done.</li>
 *   <li>{@link #ways()}: way id → ordered node refs for every {@code <way>}.</li>
 *   <li>{@link #primary()}: the requested element (attributes, tags, members).</li>
 *   <li>{@link #relations()}: every {@code <relation>} in document order. A relation's
 *       {@code /full} lists member relations too, and the primary is not necessarily first
 *       or last.</li>
 *   <li>{@link #strippedXml()}: the document with every non-primary {@code <node>} and
 *       {@code <way>} subtree removed, so the stylesheets only ever see the primary element
 *       and the relations. Geometry is passed to them as a parameter instead.</li>
 * </ul>
 *
 * Peak memory is proportional to the number of distinct nodes and node refs, not to the raw
 * markup size.
 */
public final class OsmDocument {

    /** One {@code <member>} of a relation. */
    public record Member(String type, String ref, String role) {}

    /** One {@code <node>}, {@code <way>} or {@code <relation>} element: attributes, tags, members. */
    public static final class Element {
        private final String type;
        private final String id;
        private final Map<String, String> attributes = new LinkedHashMap<>();
        private final List<Member> members = new ArrayList<>();
        private final List<String[]> tags = new ArrayList<>();
        private final List<String> nodeRefs = new ArrayList<>();

        Element(String type, String id) {
            this.type = type;
            this.id = id;
        }

        /** {@code node}, {@code way} or {@code relation}. */
        public String type() {
            return type;
        }

        public String id() {
            return id;
        }

        /** Element attributes ({@code version}, {@code changeset}, {@code timestamp}, ...). */
        public Map<String, String> attributes() {
            return Collections.unmodifiableMap(attributes);
        }

        /** Relation members, in order; empty for nodes and ways. */
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

    private final String primaryType;
    private final String primaryId;
    private final Map<String, double[]> nodes;
    private final Map<String, List<String>> ways;
    private final Map<String, Element> relations;
    private final Element primary;
    private final String strippedXml;
    private final double[] centroid;

    private OsmDocument(String primaryType, String primaryId, Map<String, double[]> nodes,
            Map<String, List<String>> ways, Map<String, Element> relations, Element primary,
            String strippedXml, double[] centroid) {
        this.primaryType = primaryType;
        this.primaryId = primaryId;
        this.nodes = nodes;
        this.ways = ways;
        this.relations = relations;
        this.primary = primary;
        this.strippedXml = strippedXml;
        this.centroid = centroid;
    }

    public String primaryType() {
        return primaryType;
    }

    public String primaryId() {
        return primaryId;
    }

    /** The requested element, or {@code null} if the document does not contain it. */
    public Element primary() {
        return primary;
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
    public Map<String, Element> relations() {
        return Collections.unmodifiableMap(relations);
    }

    public Element relation(String id) {
        return relations.get(id);
    }

    /** The document with all non-primary {@code <node>} and {@code <way>} subtrees removed. */
    public String strippedXml() {
        return strippedXml;
    }

    /**
     * Mean {@code [lon, lat]} over every node in the document, or {@code null} if there are
     * none. For a node that is the node itself; for a way its nodes; for a relation every
     * node of every member way.
     */
    public double[] centroid() {
        return centroid;
    }

    /**
     * Parse an OSM API response. The stream is read exactly once and is not closed.
     *
     * @param primaryType {@code node}, {@code way} or {@code relation}
     * @param primaryId   id of the requested element
     * @throws IOException if the XML is not well-formed
     */
    public static OsmDocument parse(InputStream in, String primaryType, String primaryId) throws IOException {
        XMLInputFactory inf = XMLInputFactory.newFactory();
        inf.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        inf.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        inf.setProperty(XMLInputFactory.IS_COALESCING, Boolean.TRUE);

        Map<String, double[]> nodes = new HashMap<>();
        Map<String, List<String>> ways = new HashMap<>();
        Map<String, Element> relations = new LinkedHashMap<>();
        Element primary = null;
        StringWriter sw = new StringWriter();
        double sumLon = 0;
        double sumLat = 0;

        try {
            XMLStreamReader r = inf.createXMLStreamReader(in);
            XMLStreamWriter w = XMLOutputFactory.newFactory().createXMLStreamWriter(sw);
            w.writeStartDocument("UTF-8", "1.0");
            // Elements currently open and being copied through (relations, and the primary
            // node/way). Their tags/members/nd children are attached to the innermost one.
            List<Element> open = new ArrayList<>();
            int depth = 0;

            while (r.hasNext()) {
                int ev = r.next();
                switch (ev) {
                    case XMLStreamConstants.START_ELEMENT: {
                        String name = r.getLocalName();
                        boolean osmElement = depth == 1
                                && ("node".equals(name) || "way".equals(name) || "relation".equals(name));
                        String id = osmElement ? r.getAttributeValue(null, "id") : null;
                        boolean isPrimary = osmElement && name.equals(primaryType) && primaryId.equals(id);

                        if (osmElement && "node".equals(name)) {
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
                        }
                        if (osmElement && !isPrimary && !"relation".equals(name)) {
                            // Member node/way: index and drop from the stripped document.
                            if ("way".equals(name)) {
                                List<String> refs = new ArrayList<>();
                                skipSubtree(r, refs);
                                if (id != null) ways.put(id, refs);
                            } else {
                                skipSubtree(r, null);
                            }
                            continue;
                        }

                        depth++;
                        copyStartElement(r, w);
                        Element current = open.isEmpty() ? null : open.get(open.size() - 1);
                        if (osmElement) {
                            Element el = new Element(name, id);
                            for (int i = 0; i < r.getAttributeCount(); i++) {
                                el.attributes.put(r.getAttributeLocalName(i), r.getAttributeValue(i));
                            }
                            if ("relation".equals(name) && id != null) relations.put(id, el);
                            if (isPrimary) primary = el;
                            open.add(el);
                        } else if (current != null && "member".equals(name)) {
                            current.members.add(new Member(
                                    r.getAttributeValue(null, "type"),
                                    r.getAttributeValue(null, "ref"),
                                    r.getAttributeValue(null, "role")));
                        } else if (current != null && "tag".equals(name)) {
                            String k = r.getAttributeValue(null, "k");
                            String v = r.getAttributeValue(null, "v");
                            if (k != null) current.tags.add(new String[]{k, v == null ? "" : v});
                        } else if (current != null && "nd".equals(name)) {
                            String ref = r.getAttributeValue(null, "ref");
                            if (ref != null) current.nodeRefs.add(ref);
                        }
                        break;
                    }
                    case XMLStreamConstants.END_ELEMENT: {
                        depth--;
                        String name = r.getLocalName();
                        if (depth == 1 && !open.isEmpty()
                                && ("node".equals(name) || "way".equals(name) || "relation".equals(name))) {
                            Element closed = open.remove(open.size() - 1);
                            if ("way".equals(name) && closed.id != null) ways.put(closed.id, closed.nodeRefs);
                        }
                        w.writeEndElement();
                        break;
                    }
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
        return new OsmDocument(primaryType, primaryId, nodes, ways, relations, primary, sw.toString(), centroid);
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
