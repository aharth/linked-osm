<!-- Shared templates and functions for node.xsl, way.xsl, relation.xsl -->
<xsl:stylesheet
   xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
   xmlns:xs="http://www.w3.org/2001/XMLSchema"
   xmlns:local="urn:local"
   version="2.0"
   exclude-result-prefixes="xs local">

  <!-- Escape a string value for use inside a Turtle double-quoted literal -->
  <xsl:function name="local:ttl" as="xs:string">
    <xsl:param name="s" as="xs:string"/>
    <xsl:value-of select="replace(replace(replace(replace(replace($s, '\\', '\\\\'), '&quot;', '\\&quot;'), '&#10;', '\\n'), '&#13;', '\\r'), '&#9;', '\\t')"/>
  </xsl:function>

  <!-- Turtle prefix declarations (identical across node/way/relation) -->
  <xsl:template name="ttl-prefixes">
    <xsl:text>@prefix rdf:     &lt;http://www.w3.org/1999/02/22-rdf-syntax-ns#&gt; .&#10;</xsl:text>
    <xsl:text>@prefix rdfs:    &lt;http://www.w3.org/2000/01/rdf-schema#&gt; .&#10;</xsl:text>
    <xsl:text>@prefix foaf:    &lt;http://xmlns.com/foaf/0.1/&gt; .&#10;</xsl:text>
    <xsl:text>@prefix owl:     &lt;http://www.w3.org/2002/07/owl#&gt; .&#10;</xsl:text>
    <xsl:text>@prefix dc:      &lt;http://purl.org/dc/elements/1.1/&gt; .&#10;</xsl:text>
    <xsl:text>@prefix dcterms: &lt;http://purl.org/dc/terms/&gt; .&#10;</xsl:text>
    <xsl:text>@prefix geo:     &lt;http://www.w3.org/2003/01/geo/wgs84_pos#&gt; .&#10;</xsl:text>
    <xsl:text>@prefix spatial: &lt;http://geovocab.org/spatial#&gt; .&#10;</xsl:text>
    <xsl:text>@prefix geom:    &lt;http://geovocab.org/geometry#&gt; .&#10;</xsl:text>
    <xsl:text>@prefix locn:    &lt;http://www.w3.org/ns/locn#&gt; .&#10;</xsl:text>
    <xsl:text>@prefix prov:    &lt;http://www.w3.org/ns/prov#&gt; .&#10;</xsl:text>
    <xsl:text>@prefix dcat:    &lt;http://www.w3.org/ns/dcat#&gt; .&#10;</xsl:text>
    <xsl:text>@prefix osm:     &lt;/vocab#&gt; .&#10;</xsl:text>
    <xsl:text>@prefix xsd:     &lt;http://www.w3.org/2001/XMLSchema#&gt; .&#10;</xsl:text>
    <xsl:text>&#10;</xsl:text>
  </xsl:template>

  <!-- Document <> block. Caller passes the primary-source-url (versioned OSM API URL
       when available, otherwise $upstream-url). Context node must be <osm>. -->
  <xsl:template name="doc-header">
    <xsl:param name="primary-source-url" select="''"/>
    <xsl:param name="upstream-url"       select="''"/>
    <xsl:param name="upstream-bytes"     select="-1"/>
    <xsl:text>&lt;&gt; rdfs:comment "No guarantee of correctness! USE AT YOUR OWN RISK!" ;&#10;</xsl:text>
    <xsl:if test="normalize-space(@generator) != ''">
      <xsl:text>    rdfs:comment "</xsl:text><xsl:value-of select="local:ttl(string(@generator))"/><xsl:text>" ;&#10;</xsl:text>
    </xsl:if>
    <xsl:text>    dc:publisher "OpenStreetMap Contributors (https://www.openstreetmap.org/) via Linked OSM (https://osmwrap.ontologycentral.com/)" ;&#10;</xsl:text>
    <xsl:text>    dc:attribution "\u00a9 OpenStreetMap contributors" ;&#10;</xsl:text>
    <xsl:text>    dc:license &lt;https://opendatacommons.org/licenses/odbl/&gt; ;&#10;</xsl:text>
    <xsl:text>    rdfs:seeAlso &lt;https://www.openstreetmap.org/copyright&gt; ;&#10;</xsl:text>
    <xsl:text>    rdfs:seeAlso &lt;https://wiki.openstreetmap.org/wiki/Legal_FAQ&gt; ;&#10;</xsl:text>
    <xsl:if test="$primary-source-url != ''">
      <xsl:text>    prov:hadPrimarySource &lt;</xsl:text><xsl:value-of select="$primary-source-url"/><xsl:text>&gt; ;&#10;</xsl:text>
    </xsl:if>
    <xsl:text>    prov:generatedAtTime "</xsl:text><xsl:value-of select="current-dateTime()"/><xsl:text>"^^xsd:dateTime ;&#10;</xsl:text>
    <xsl:text>    prov:wasAttributedTo &lt;/#osmwrap&gt; .&#10;</xsl:text>
    <xsl:text>&#10;</xsl:text>
    <xsl:if test="$upstream-url != '' and $upstream-bytes >= 0">
      <xsl:text>&lt;</xsl:text><xsl:value-of select="$upstream-url"/><xsl:text>&gt; dcat:byteSize "</xsl:text><xsl:value-of select="$upstream-bytes"/><xsl:text>"^^xsd:decimal .&#10;</xsl:text>
      <xsl:text>&#10;</xsl:text>
    </xsl:if>
  </xsl:template>

  <!-- Versioned OSM API source entity.
       type: "node" | "way" | "relation"  id/version/changeset: from OSM XML attributes -->
  <xsl:template name="versioned-source">
    <xsl:param name="type"/>
    <xsl:param name="id"/>
    <xsl:param name="version"/>
    <xsl:param name="changeset" select="''"/>
    <xsl:text>&lt;https://api.openstreetmap.org/api/0.6/</xsl:text><xsl:value-of select="$type"/><xsl:text>/</xsl:text><xsl:value-of select="$id"/><xsl:text>/</xsl:text><xsl:value-of select="$version"/><xsl:text>&gt;&#10;</xsl:text>
    <xsl:text>    prov:value "</xsl:text><xsl:value-of select="$version"/><xsl:text>"</xsl:text>
    <xsl:if test="$changeset != ''">
      <xsl:text> ;&#10;    prov:wasGeneratedBy &lt;/changeset/</xsl:text><xsl:value-of select="$changeset"/><xsl:text>&gt;</xsl:text>
    </xsl:if>
    <xsl:text> .&#10;</xsl:text>
    <xsl:text>&#10;</xsl:text>
  </xsl:template>

  <!-- Changeset as prov:Activity. Only emitted when all three attributes are present. -->
  <xsl:template name="changeset-activity">
    <xsl:param name="changeset"/>
    <xsl:param name="timestamp"/>
    <xsl:param name="user"/>
    <xsl:text>&lt;/changeset/</xsl:text><xsl:value-of select="$changeset"/><xsl:text>&gt; a prov:Activity ;&#10;</xsl:text>
    <xsl:text>    prov:endedAtTime "</xsl:text><xsl:value-of select="$timestamp"/><xsl:text>"^^xsd:dateTime ;&#10;</xsl:text>
    <xsl:text>    prov:wasAssociatedWith [ a prov:Agent ;&#10;</xsl:text>
    <xsl:text>        foaf:accountName "</xsl:text><xsl:value-of select="local:ttl($user)"/><xsl:text>" ;&#10;</xsl:text>
    <xsl:text>        foaf:accountServiceHomepage &lt;https://www.openstreetmap.org&gt; ;&#10;</xsl:text>
    <xsl:text>        foaf:homepage &lt;https://www.openstreetmap.org/user/</xsl:text><xsl:value-of select="encode-for-uri($user)"/><xsl:text>&gt; ] .&#10;</xsl:text>
    <xsl:text>&#10;</xsl:text>
  </xsl:template>

</xsl:stylesheet>
