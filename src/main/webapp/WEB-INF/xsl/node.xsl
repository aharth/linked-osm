<xsl:stylesheet
   xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
   xmlns:xs="http://www.w3.org/2001/XMLSchema"
   xmlns:local="urn:local"
   version="2.0"
   exclude-result-prefixes="xs local">

  <xsl:include href="common.xsl"/>

  <xsl:output method="text" encoding="UTF-8"/>

  <xsl:strip-space elements="*"/>

  <xsl:param name="upstream-bytes" select="-1"/>
  <xsl:param name="source-prefix" select="'/osm'"/>
  <xsl:param name="upstream-url" select="''"/>

  <xsl:template match="osm">
    <xsl:call-template name="ttl-prefixes"/>
    <xsl:call-template name="doc-header">
      <xsl:with-param name="primary-source-url">
        <xsl:choose>
          <xsl:when test="node/@version">
            <xsl:text>https://api.openstreetmap.org/api/0.6/node/</xsl:text><xsl:value-of select="node/@id"/><xsl:text>/</xsl:text><xsl:value-of select="node/@version"/>
          </xsl:when>
          <xsl:otherwise><xsl:value-of select="$upstream-url"/></xsl:otherwise>
        </xsl:choose>
      </xsl:with-param>
      <xsl:with-param name="upstream-url"   select="$upstream-url"/>
      <xsl:with-param name="upstream-bytes" select="$upstream-bytes"/>
    </xsl:call-template>
    <xsl:apply-templates/>
  </xsl:template>

  <xsl:template match="node">
    <!-- Feature -->
    <xsl:text>&lt;</xsl:text><xsl:value-of select="$source-prefix"/><xsl:text>/node/</xsl:text><xsl:value-of select="@id"/><xsl:text>#id&gt; a spatial:Feature, osm:Node ;&#10;</xsl:text>
    <xsl:text>    dcterms:identifier "</xsl:text><xsl:value-of select="@id"/><xsl:text>" ;&#10;</xsl:text>
    <xsl:text>    geo:lat "</xsl:text><xsl:value-of select="@lat"/><xsl:text>" ;&#10;</xsl:text>
    <xsl:text>    geo:long "</xsl:text><xsl:value-of select="@lon"/><xsl:text>" ;&#10;</xsl:text>
    <xsl:text>    foaf:page &lt;https://www.openstreetmap.org/node/</xsl:text><xsl:value-of select="@id"/><xsl:text>&gt; ;&#10;</xsl:text>
    <xsl:text>    foaf:page &lt;</xsl:text><xsl:value-of select="$source-prefix"/><xsl:text>/node/</xsl:text><xsl:value-of select="@id"/><xsl:text>.rdf&gt; ;&#10;</xsl:text>
    <xsl:text>    foaf:page &lt;</xsl:text><xsl:value-of select="$source-prefix"/><xsl:text>/node/</xsl:text><xsl:value-of select="@id"/><xsl:text>.ttl&gt; ;&#10;</xsl:text>
    <xsl:text>    foaf:page &lt;</xsl:text><xsl:value-of select="$source-prefix"/><xsl:text>/node/</xsl:text><xsl:value-of select="@id"/><xsl:text>.json&gt; ;&#10;</xsl:text>
    <xsl:text>    geom:geometry &lt;</xsl:text><xsl:value-of select="$source-prefix"/><xsl:text>/node/</xsl:text><xsl:value-of select="@id"/><xsl:text>#geo&gt;</xsl:text>
    <!-- Tags (each emits: ; <uri> "value") -->
    <xsl:apply-templates/>
    <xsl:text> .&#10;</xsl:text>
    <xsl:text>&#10;</xsl:text>

    <!-- Primary source (versioned OSM API URL) -->
    <xsl:if test="@version">
      <xsl:call-template name="versioned-source">
        <xsl:with-param name="type">node</xsl:with-param>
        <xsl:with-param name="id"      select="@id"/>
        <xsl:with-param name="version" select="@version"/>
        <xsl:with-param name="changeset" select="@changeset"/>
      </xsl:call-template>
    </xsl:if>

    <!-- Geometry resource -->
    <xsl:text>&lt;</xsl:text><xsl:value-of select="$source-prefix"/><xsl:text>/node/</xsl:text><xsl:value-of select="@id"/><xsl:text>#geo&gt; a geom:Geometry ;&#10;</xsl:text>
    <xsl:text>    geo:lat "</xsl:text><xsl:value-of select="@lat"/><xsl:text>" ;&#10;</xsl:text>
    <xsl:text>    geo:long "</xsl:text><xsl:value-of select="@lon"/><xsl:text>" ;&#10;</xsl:text>
    <xsl:text>    locn:geometry "&lt;gml:Point xmlns:gml=\&quot;http://www.opengis.net/gml/3.2\&quot; srsName=\&quot;http://www.opengis.net/def/crs/OGC/1.3/CRS84\&quot;&gt;&lt;gml:pos&gt;</xsl:text>
    <xsl:value-of select="@lon"/><xsl:text> </xsl:text><xsl:value-of select="@lat"/>
    <xsl:text>&lt;/gml:pos&gt;&lt;/gml:Point&gt;"^^rdf:XMLLiteral .&#10;</xsl:text>
    <xsl:text>&#10;</xsl:text>

    <!-- Changeset as Activity -->
    <xsl:if test="@changeset and @timestamp and @user">
      <xsl:call-template name="changeset-activity">
        <xsl:with-param name="changeset" select="@changeset"/>
        <xsl:with-param name="timestamp" select="@timestamp"/>
        <xsl:with-param name="user"      select="@user"/>
      </xsl:call-template>
    </xsl:if>
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
