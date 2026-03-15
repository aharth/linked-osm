<xsl:stylesheet
   xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
   xmlns:xs="http://www.w3.org/2001/XMLSchema"
   xmlns:local="urn:local"
   version="2.0"
   exclude-result-prefixes="xs local">

  <xsl:output method="text" encoding="UTF-8"/>

  <xsl:strip-space elements="*"/>

  <xsl:param name="upstream-bytes" select="-1"/>
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
    <xsl:text>@prefix locn:    &lt;http://www.w3.org/ns/locn#&gt; .&#10;</xsl:text>
    <xsl:text>@prefix prov:    &lt;http://www.w3.org/ns/prov#&gt; .&#10;</xsl:text>
    <xsl:text>@prefix dcat:    &lt;http://www.w3.org/ns/dcat#&gt; .&#10;</xsl:text>
    <xsl:text>@prefix osm:     &lt;https://osmwrap.ontologycentral.com/vocab#&gt; .&#10;</xsl:text>
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

    <!-- PROV: Agent -->
    <xsl:text>&lt;/#osmwrap&gt; a prov:SoftwareAgent ;&#10;</xsl:text>
    <xsl:text>    rdfs:label "Linked OSM (osmwrap)" ;&#10;</xsl:text>
    <xsl:text>    foaf:homepage &lt;https://osmwrap.ontologycentral.com/&gt; ;&#10;</xsl:text>
    <xsl:text>    dc:description "Service for converting OpenStreetMap data to RDF" .&#10;</xsl:text>
    <xsl:text>&#10;</xsl:text>

    <xsl:apply-templates/>
  </xsl:template>

  <xsl:template match="way">
    <!-- Feature -->
    <xsl:text>&lt;</xsl:text><xsl:value-of select="$source-prefix"/><xsl:text>/way/</xsl:text><xsl:value-of select="@id"/><xsl:text>#id&gt; a spatial:Feature, osm:Way ;&#10;</xsl:text>
    <xsl:text>    dcterms:identifier "</xsl:text><xsl:value-of select="@id"/><xsl:text>" ;&#10;</xsl:text>
    <xsl:text>    foaf:page &lt;https://www.openstreetmap.org/way/</xsl:text><xsl:value-of select="@id"/><xsl:text>&gt; ;&#10;</xsl:text>
    <xsl:text>    foaf:page &lt;</xsl:text><xsl:value-of select="$source-prefix"/><xsl:text>/way/</xsl:text><xsl:value-of select="@id"/><xsl:text>.rdf&gt; ;&#10;</xsl:text>
    <xsl:text>    foaf:page &lt;</xsl:text><xsl:value-of select="$source-prefix"/><xsl:text>/way/</xsl:text><xsl:value-of select="@id"/><xsl:text>.ttl&gt; ;&#10;</xsl:text>
    <xsl:text>    foaf:page &lt;</xsl:text><xsl:value-of select="$source-prefix"/><xsl:text>/way/</xsl:text><xsl:value-of select="@id"/><xsl:text>.json&gt; ;&#10;</xsl:text>
    <xsl:text>    geom:geometry &lt;/way/</xsl:text><xsl:value-of select="@id"/><xsl:text>#geo&gt;</xsl:text>
    <xsl:if test="@changeset">
      <xsl:text> ;&#10;    prov:wasGeneratedBy &lt;/changeset/</xsl:text><xsl:value-of select="@changeset"/><xsl:text>&gt;</xsl:text>
    </xsl:if>
    <xsl:if test="@timestamp">
      <xsl:text> ;&#10;    prov:generatedAtTime "</xsl:text><xsl:value-of select="@timestamp"/><xsl:text>"^^xsd:dateTime</xsl:text>
    </xsl:if>
    <xsl:if test="@version">
      <xsl:text> ;&#10;    prov:hadPrimarySource &lt;https://api.openstreetmap.org/api/0.6/way/</xsl:text><xsl:value-of select="@id"/><xsl:text>/</xsl:text><xsl:value-of select="@version"/><xsl:text>&gt;</xsl:text>
      <xsl:text> ;&#10;    prov:value "</xsl:text><xsl:value-of select="@version"/><xsl:text>"</xsl:text>
    </xsl:if>
    <xsl:if test="@user">
      <xsl:text> ;&#10;    prov:wasAttributedTo [ a prov:Agent ;&#10;</xsl:text>
      <xsl:text>        foaf:accountName "</xsl:text><xsl:value-of select="local:ttl(@user)"/><xsl:text>" ;&#10;</xsl:text>
      <xsl:text>        foaf:accountServiceHomepage &lt;https://www.openstreetmap.org&gt; ;&#10;</xsl:text>
      <xsl:text>        foaf:homepage &lt;https://www.openstreetmap.org/user/</xsl:text><xsl:value-of select="encode-for-uri(@user)"/><xsl:text>&gt; ]</xsl:text>
    </xsl:if>
    <!-- Tags (nd elements suppressed; each tag emits: ; <uri> "value") -->
    <xsl:apply-templates/>
    <xsl:text> .&#10;</xsl:text>
    <xsl:text>&#10;</xsl:text>

    <!-- Geometry resource -->
    <xsl:variable name="nodes" select="nd/key('nodeById', @ref)[normalize-space(@lat)]"/>
    <xsl:variable name="firstRef" select="nd[1]/@ref"/>
    <xsl:variable name="lastRef"  select="nd[last()]/@ref"/>
    <xsl:variable name="closed"   select="count($nodes) >= 4 and $firstRef = $lastRef"/>
    <xsl:variable name="gmlCoords">
      <xsl:for-each select="nd">
        <xsl:variable name="n" select="key('nodeById', @ref)"/>
        <xsl:if test="$n/@lon and $n/@lat">
          <xsl:if test="position() > 1"><xsl:text> </xsl:text></xsl:if>
          <xsl:value-of select="$n/@lon"/>
          <xsl:text> </xsl:text>
          <xsl:value-of select="$n/@lat"/>
        </xsl:if>
      </xsl:for-each>
    </xsl:variable>

    <xsl:text>&lt;/way/</xsl:text><xsl:value-of select="@id"/><xsl:text>#geo&gt; a geom:Geometry ;&#10;</xsl:text>
    <xsl:text>    foaf:page &lt;/geo/osm/way/</xsl:text><xsl:value-of select="@id"/><xsl:text>&gt; ;&#10;</xsl:text>
    <xsl:text>    foaf:page &lt;/geo/overpass/way/</xsl:text><xsl:value-of select="@id"/><xsl:text>&gt;</xsl:text>
    <xsl:if test="count($nodes) > 0">
      <xsl:text> ;&#10;    geo:lat "</xsl:text><xsl:value-of select="sum($nodes/@lat) div count($nodes)"/><xsl:text>"</xsl:text>
      <xsl:text> ;&#10;    geo:long "</xsl:text><xsl:value-of select="sum($nodes/@lon) div count($nodes)"/><xsl:text>"</xsl:text>
    </xsl:if>
    <xsl:if test="normalize-space($gmlCoords) != ''">
      <xsl:choose>
        <xsl:when test="$closed">
          <xsl:text> ;&#10;    locn:geometry "&lt;gml:Polygon xmlns:gml=\&quot;http://www.opengis.net/gml/3.2\&quot; srsName=\&quot;http://www.opengis.net/def/crs/OGC/1.3/CRS84\&quot;&gt;&lt;gml:exterior&gt;&lt;gml:LinearRing&gt;&lt;gml:posList&gt;</xsl:text>
          <xsl:value-of select="$gmlCoords"/>
          <xsl:text>&lt;/gml:posList&gt;&lt;/gml:LinearRing&gt;&lt;/gml:exterior&gt;&lt;/gml:Polygon&gt;"^^rdf:XMLLiteral</xsl:text>
        </xsl:when>
        <xsl:otherwise>
          <xsl:text> ;&#10;    locn:geometry "&lt;gml:LineString xmlns:gml=\&quot;http://www.opengis.net/gml/3.2\&quot; srsName=\&quot;http://www.opengis.net/def/crs/OGC/1.3/CRS84\&quot;&gt;&lt;gml:posList&gt;</xsl:text>
          <xsl:value-of select="$gmlCoords"/>
          <xsl:text>&lt;/gml:posList&gt;&lt;/gml:LineString&gt;"^^rdf:XMLLiteral</xsl:text>
        </xsl:otherwise>
      </xsl:choose>
    </xsl:if>
    <xsl:text> .&#10;</xsl:text>
    <xsl:text>&#10;</xsl:text>

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

  <!-- Suppress nd elements from tag output -->
  <xsl:template match="nd"/>

  <xsl:template match="tag[@k = 'name:en']">
    <xsl:text> ;&#10;    rdfs:label "</xsl:text>
    <xsl:value-of select="local:ttl(@v)"/>
    <xsl:text>"</xsl:text>
    <xsl:text> ;&#10;    &lt;https://osmwrap.ontologycentral.com/tag/name:en&gt; "</xsl:text>
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
    <xsl:text> ;&#10;    &lt;https://osmwrap.ontologycentral.com/tag/</xsl:text>
    <xsl:value-of select="@k"/>
    <xsl:text>&gt; "</xsl:text>
    <xsl:value-of select="local:ttl(@v)"/>
    <xsl:text>"</xsl:text>
  </xsl:template>

</xsl:stylesheet>
