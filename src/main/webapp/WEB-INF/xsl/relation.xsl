<xsl:stylesheet
   xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
   xmlns:xs="http://www.w3.org/2001/XMLSchema"
   xmlns:local="urn:local"
   version="2.0"
   exclude-result-prefixes="xs local">

  <xsl:output method="text" encoding="UTF-8"/>

  <xsl:strip-space elements="*"/>

  <xsl:param name="upstream-bytes" select="-1"/>
  <xsl:param name="element-id" select="''"/>
  <xsl:param name="source-prefix" select="'/osm'"/>
  <xsl:param name="upstream-url" select="''"/>

  <xsl:key name="nodeById" match="node" use="@id"/>

  <!-- Escape a string value for use inside a Turtle double-quoted literal -->
  <xsl:function name="local:ttl" as="xs:string">
    <xsl:param name="s" as="xs:string"/>
    <xsl:value-of select="replace(replace(replace(replace(replace($s, '\\', '\\\\'), '&quot;', '\\&quot;'), '&#10;', '\\n'), '&#13;', '\\r'), '&#9;', '\\t')"/>
  </xsl:function>

  <xsl:template match="osm">
    <!-- Prefix declarations -->
    <xsl:text>@prefix rdf:     &lt;http://www.w3.org/1999/02/22-rdf-syntax-ns#&gt; .&#10;</xsl:text>
    <xsl:text>@prefix rdfs:    &lt;http://www.w3.org/2000/01/rdf-schema#&gt; .&#10;</xsl:text>
    <xsl:text>@prefix foaf:    &lt;http://xmlns.com/foaf/0.1/&gt; .&#10;</xsl:text>
    <xsl:text>@prefix owl:     &lt;http://www.w3.org/2002/07/owl#&gt; .&#10;</xsl:text>
    <xsl:text>@prefix dc:      &lt;http://purl.org/dc/elements/1.1/&gt; .&#10;</xsl:text>
    <xsl:text>@prefix dcterms: &lt;http://purl.org/dc/terms/&gt; .&#10;</xsl:text>
    <xsl:text>@prefix geo:     &lt;http://www.w3.org/2003/01/geo/wgs84_pos#&gt; .&#10;</xsl:text>
    <xsl:text>@prefix spatial: &lt;http://geovocab.org/spatial#&gt; .&#10;</xsl:text>
    <xsl:text>@prefix geom:    &lt;http://geovocab.org/geometry#&gt; .&#10;</xsl:text>
    <xsl:text>@prefix prov:    &lt;http://www.w3.org/ns/prov#&gt; .&#10;</xsl:text>
    <xsl:text>@prefix dcat:    &lt;http://www.w3.org/ns/dcat#&gt; .&#10;</xsl:text>
    <xsl:text>@prefix osm:     &lt;/vocab#&gt; .&#10;</xsl:text>
    <xsl:text>@prefix xsd:     &lt;http://www.w3.org/2001/XMLSchema#&gt; .&#10;</xsl:text>
    <xsl:text>&#10;</xsl:text>

    <!-- Document description -->
    <xsl:text>&lt;&gt; rdfs:comment "No guarantee of correctness! USE AT YOUR OWN RISK!" ;&#10;</xsl:text>
    <xsl:if test="normalize-space(@generator) != ''">
      <xsl:text>    rdfs:comment "</xsl:text><xsl:value-of select="local:ttl(string(@generator))"/><xsl:text>" ;&#10;</xsl:text>
    </xsl:if>
    <xsl:text>    dc:publisher "OpenStreetMap Contributors (https://www.openstreetmap.org/) via Linked OSM (https://osmwrap.ontologycentral.com/)" ;&#10;</xsl:text>
    <xsl:text>    dc:attribution "\u00a9 OpenStreetMap contributors" ;&#10;</xsl:text>
    <xsl:text>    dc:license &lt;https://opendatacommons.org/licenses/odbl/&gt; ;&#10;</xsl:text>
    <xsl:text>    rdfs:seeAlso &lt;https://www.openstreetmap.org/copyright&gt; ;&#10;</xsl:text>
    <xsl:text>    rdfs:seeAlso &lt;https://wiki.openstreetmap.org/wiki/Legal_FAQ&gt; ;&#10;</xsl:text>
    <xsl:if test="$upstream-url != ''">
      <xsl:text>    prov:hadPrimarySource &lt;</xsl:text><xsl:value-of select="$upstream-url"/><xsl:text>&gt; ;&#10;</xsl:text>
    </xsl:if>
    <xsl:text>    prov:generatedAtTime "</xsl:text><xsl:value-of select="current-dateTime()"/><xsl:text>"^^xsd:dateTime ;&#10;</xsl:text>
    <xsl:text>    prov:wasAttributedTo &lt;/#osmwrap&gt; .&#10;</xsl:text>
    <xsl:text>&#10;</xsl:text>
    <xsl:if test="$upstream-url != '' and $upstream-bytes >= 0">
      <xsl:text>&lt;</xsl:text><xsl:value-of select="$upstream-url"/><xsl:text>&gt; dcat:byteSize "</xsl:text><xsl:value-of select="$upstream-bytes"/><xsl:text>"^^xsd:decimal .&#10;</xsl:text>
      <xsl:text>&#10;</xsl:text>
    </xsl:if>

    <xsl:apply-templates/>
  </xsl:template>

  <xsl:template match="relation">
    <xsl:variable name="allNodes" select="//node[normalize-space(@lat)]"/>
    <xsl:variable name="hasGeo" select="count($allNodes) > 0"/>

    <!-- Feature -->
    <xsl:text>&lt;</xsl:text><xsl:value-of select="$source-prefix"/><xsl:text>/relation/</xsl:text><xsl:value-of select="@id"/><xsl:text>#id&gt; a spatial:Feature, osm:Relation ;&#10;</xsl:text>
    <xsl:text>    dcterms:identifier "</xsl:text><xsl:value-of select="@id"/><xsl:text>" ;&#10;</xsl:text>
    <xsl:text>    foaf:page &lt;https://www.openstreetmap.org/relation/</xsl:text><xsl:value-of select="@id"/><xsl:text>&gt; ;&#10;</xsl:text>
    <xsl:text>    foaf:page &lt;</xsl:text><xsl:value-of select="$source-prefix"/><xsl:text>/relation/</xsl:text><xsl:value-of select="@id"/><xsl:text>.rdf&gt; ;&#10;</xsl:text>
    <xsl:text>    foaf:page &lt;</xsl:text><xsl:value-of select="$source-prefix"/><xsl:text>/relation/</xsl:text><xsl:value-of select="@id"/><xsl:text>.ttl&gt; ;&#10;</xsl:text>
    <xsl:text>    foaf:page &lt;</xsl:text><xsl:value-of select="$source-prefix"/><xsl:text>/relation/</xsl:text><xsl:value-of select="@id"/><xsl:text>.json&gt;</xsl:text>
    <xsl:if test="$hasGeo">
      <xsl:text> ;&#10;    geom:geometry &lt;/relation/</xsl:text><xsl:value-of select="@id"/><xsl:text>#geo&gt;</xsl:text>
    </xsl:if>
    <xsl:if test="@changeset">
      <xsl:text> ;&#10;    prov:wasGeneratedBy &lt;/changeset/</xsl:text><xsl:value-of select="@changeset"/><xsl:text>&gt;</xsl:text>
    </xsl:if>
    <xsl:if test="@timestamp">
      <xsl:text> ;&#10;    prov:generatedAtTime "</xsl:text><xsl:value-of select="@timestamp"/><xsl:text>"^^xsd:dateTime</xsl:text>
    </xsl:if>
    <xsl:if test="@version">
      <xsl:text> ;&#10;    prov:hadPrimarySource &lt;https://api.openstreetmap.org/api/0.6/relation/</xsl:text><xsl:value-of select="@id"/><xsl:text>/</xsl:text><xsl:value-of select="@version"/><xsl:text>&gt;</xsl:text>
      <xsl:text> ;&#10;    prov:value "</xsl:text><xsl:value-of select="@version"/><xsl:text>"</xsl:text>
    </xsl:if>
    <xsl:if test="@user">
      <xsl:text> ;&#10;    prov:wasAttributedTo [ a prov:Agent ;&#10;</xsl:text>
      <xsl:text>        foaf:accountName "</xsl:text><xsl:value-of select="local:ttl(@user)"/><xsl:text>" ;&#10;</xsl:text>
      <xsl:text>        foaf:accountServiceHomepage &lt;https://www.openstreetmap.org&gt; ;&#10;</xsl:text>
      <xsl:text>        foaf:homepage &lt;https://www.openstreetmap.org/user/</xsl:text><xsl:value-of select="encode-for-uri(@user)"/><xsl:text>&gt; ]</xsl:text>
    </xsl:if>
    <!-- Members and tags -->
    <xsl:apply-templates/>
    <xsl:text> .&#10;</xsl:text>
    <xsl:text>&#10;</xsl:text>

    <!-- Geometry resource (only when relation has node coordinates) -->
    <xsl:if test="$hasGeo">
      <xsl:text>&lt;/relation/</xsl:text><xsl:value-of select="@id"/><xsl:text>#geo&gt; a geom:Geometry ;&#10;</xsl:text>
      <xsl:text>    foaf:page &lt;/geo/osm/relation/</xsl:text><xsl:value-of select="@id"/><xsl:text>&gt; ;&#10;</xsl:text>
      <xsl:text>    foaf:page &lt;/geo/overpass/relation/</xsl:text><xsl:value-of select="@id"/><xsl:text>&gt; ;&#10;</xsl:text>
      <xsl:text>    geo:lat "</xsl:text><xsl:value-of select="sum($allNodes/@lat) div count($allNodes)"/><xsl:text>" ;&#10;</xsl:text>
      <xsl:text>    geo:long "</xsl:text><xsl:value-of select="sum($allNodes/@lon) div count($allNodes)"/><xsl:text>" .&#10;</xsl:text>
      <xsl:text>&#10;</xsl:text>
    </xsl:if>

    <!-- Changeset as Activity -->
    <xsl:if test="@changeset and @timestamp and @user">
      <xsl:text>&lt;/changeset/</xsl:text><xsl:value-of select="@changeset"/><xsl:text>&gt; a prov:Activity ;&#10;</xsl:text>
      <xsl:text>    prov:endedAtTime "</xsl:text><xsl:value-of select="@timestamp"/><xsl:text>"^^xsd:dateTime ;&#10;</xsl:text>
      <xsl:text>    prov:wasAssociatedWith [ a prov:Agent ;&#10;</xsl:text>
      <xsl:text>        foaf:accountName "</xsl:text><xsl:value-of select="local:ttl(@user)"/><xsl:text>" ;&#10;</xsl:text>
      <xsl:text>        foaf:accountServiceHomepage &lt;https://www.openstreetmap.org&gt; ;&#10;</xsl:text>
      <xsl:text>        foaf:homepage &lt;https://www.openstreetmap.org/user/</xsl:text><xsl:value-of select="encode-for-uri(@user)"/><xsl:text>&gt; ] .&#10;</xsl:text>
      <xsl:text>&#10;</xsl:text>
    </xsl:if>
  </xsl:template>

  <!-- Suppress member node/way elements from /full response -->
  <xsl:template match="node|way"/>

  <xsl:template match="member">
    <xsl:text> ;&#10;    rdfs:seeAlso &lt;/</xsl:text><xsl:value-of select="@type"/><xsl:text>/</xsl:text><xsl:value-of select="@ref"/><xsl:text>&gt;</xsl:text>
  </xsl:template>

  <xsl:template match="tag[@k = 'name:en']">
    <xsl:text> ;&#10;    rdfs:label "</xsl:text>
    <xsl:value-of select="local:ttl(@v)"/>
    <xsl:text>"</xsl:text>
    <xsl:text> ;&#10;    &lt;/tag/name:en&gt; "</xsl:text>
    <xsl:value-of select="local:ttl(@v)"/>
    <xsl:text>"</xsl:text>
  </xsl:template>

  <!-- could be either in the form fr:Paris or just Paris -->
  <xsl:template match="tag[@k = 'wikipedia']">
    <xsl:choose>
      <xsl:when test="contains(@v, ':')">
        <xsl:text> ;&#10;    foaf:page &lt;http://</xsl:text>
        <xsl:value-of select="substring(@v, 0, 3)"/>
        <xsl:text>.wikipedia.org/wiki/</xsl:text>
        <xsl:value-of select="encode-for-uri(substring(@v, 4))"/>
        <xsl:text>&gt;</xsl:text>
        <xsl:choose>
          <xsl:when test="substring(@v, 0, 3) = 'en'">
            <xsl:text> ;&#10;    owl:sameAs &lt;http://dbpedia.org/resource/</xsl:text>
            <xsl:value-of select="encode-for-uri(substring(@v, 4))"/>
            <xsl:text>&gt;</xsl:text>
          </xsl:when>
          <xsl:otherwise>
            <xsl:text> ;&#10;    owl:sameAs &lt;http://</xsl:text>
            <xsl:value-of select="substring(@v, 0, 3)"/>
            <xsl:text>.dbpedia.org/resource/</xsl:text>
            <xsl:value-of select="encode-for-uri(substring(@v, 4))"/>
            <xsl:text>&gt;</xsl:text>
          </xsl:otherwise>
        </xsl:choose>
      </xsl:when>
      <xsl:otherwise>
        <xsl:text> ;&#10;    owl:sameAs &lt;http://dbpedia.org/resource/</xsl:text>
        <xsl:value-of select="encode-for-uri(@v)"/>
        <xsl:text>&gt;</xsl:text>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>

  <xsl:template match="tag[@k = 'wikidata']">
    <xsl:text> ;&#10;    owl:sameAs &lt;http://www.wikidata.org/entity/</xsl:text>
    <xsl:value-of select="@v"/>
    <xsl:text>&gt;</xsl:text>
  </xsl:template>

  <xsl:template match="tag">
    <xsl:text> ;&#10;    &lt;/tag/</xsl:text>
    <xsl:value-of select="@k"/>
    <xsl:text>&gt; "</xsl:text>
    <xsl:value-of select="local:ttl(@v)"/>
    <xsl:text>"</xsl:text>
  </xsl:template>

</xsl:stylesheet>
