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
  <xsl:param name="element-id" select="''"/>
  <xsl:param name="source-prefix" select="'/osm'"/>
  <xsl:param name="upstream-url" select="''"/>

  <xsl:key name="nodeById" match="node" use="@id"/>

  <xsl:template match="osm">
    <xsl:call-template name="ttl-prefixes"/>
    <xsl:call-template name="doc-header">
      <xsl:with-param name="primary-source-url">
        <xsl:choose>
          <xsl:when test="relation[@id=$element-id]/@version">
            <xsl:text>https://api.openstreetmap.org/api/0.6/relation/</xsl:text><xsl:value-of select="$element-id"/><xsl:text>/</xsl:text><xsl:value-of select="relation[@id=$element-id]/@version"/>
          </xsl:when>
          <xsl:otherwise><xsl:value-of select="$upstream-url"/></xsl:otherwise>
        </xsl:choose>
      </xsl:with-param>
      <xsl:with-param name="upstream-url"   select="$upstream-url"/>
      <xsl:with-param name="upstream-bytes" select="$upstream-bytes"/>
    </xsl:call-template>
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
    <!-- Members and tags -->
    <xsl:apply-templates/>
    <xsl:text> .&#10;</xsl:text>
    <xsl:text>&#10;</xsl:text>

    <!-- Primary source (versioned OSM API URL) -->
    <xsl:if test="@version">
      <xsl:call-template name="versioned-source">
        <xsl:with-param name="type">relation</xsl:with-param>
        <xsl:with-param name="id"      select="@id"/>
        <xsl:with-param name="version" select="@version"/>
        <xsl:with-param name="changeset" select="@changeset"/>
      </xsl:call-template>
    </xsl:if>

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
      <xsl:call-template name="changeset-activity">
        <xsl:with-param name="changeset" select="@changeset"/>
        <xsl:with-param name="timestamp" select="@timestamp"/>
        <xsl:with-param name="user"      select="@user"/>
      </xsl:call-template>
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
