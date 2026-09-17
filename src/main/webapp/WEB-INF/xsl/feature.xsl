<!-- Turtle for one OSM element (node, way or relation) and, for a relation, the member
     relations that its /full response lists as well.

     Input: the OsmDocument.strippedXml() of the response - the primary element and every
     <relation>, nothing else. Geometry is passed in (see FeatureServlet.setFeatureParameters),
     never derived here. -->
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
  <!-- The requested element. A relation's /full lists member relations too, so @id alone
       does not identify it. -->
  <xsl:param name="element-type" select="''"/>
  <xsl:param name="element-id" select="''"/>
  <!-- Mean of all node coordinates and the GML shape, both computed in Java. All strings. -->
  <xsl:param name="centroid-lat" select="''"/>
  <xsl:param name="centroid-lon" select="''"/>
  <xsl:param name="geometry-gml" select="''"/>

  <xsl:template match="osm">
    <xsl:variable name="primary" select="*[local-name() = $element-type and @id = $element-id]"/>
    <xsl:call-template name="ttl-prefixes"/>
    <xsl:call-template name="doc-header">
      <xsl:with-param name="primary-source-url">
        <xsl:choose>
          <xsl:when test="$primary/@version">
            <xsl:text>https://api.openstreetmap.org/api/0.6/</xsl:text><xsl:value-of select="$element-type"/><xsl:text>/</xsl:text><xsl:value-of select="$element-id"/><xsl:text>/</xsl:text><xsl:value-of select="$primary/@version"/>
          </xsl:when>
          <xsl:otherwise><xsl:value-of select="$upstream-url"/></xsl:otherwise>
        </xsl:choose>
      </xsl:with-param>
      <xsl:with-param name="upstream-url"   select="$upstream-url"/>
      <xsl:with-param name="upstream-bytes" select="$upstream-bytes"/>
    </xsl:call-template>
    <xsl:apply-templates select="node | way | relation"/>
  </xsl:template>

  <xsl:template match="node | way | relation">
    <xsl:variable name="type" select="local-name()"/>
    <xsl:variable name="is-primary" select="$type = $element-type and @id = $element-id"/>
    <!-- Geometry belongs to the requested element only; member relations listed in the
         same document get just their tags and members. -->
    <xsl:variable name="has-geo" select="$is-primary and normalize-space($centroid-lat) != '' and normalize-space($centroid-lon) != ''"/>

    <!-- Feature -->
    <xsl:text>&lt;</xsl:text><xsl:value-of select="$source-prefix"/><xsl:text>/</xsl:text><xsl:value-of select="$type"/><xsl:text>/</xsl:text><xsl:value-of select="@id"/><xsl:text>#id&gt; a spatial:Feature, osm:</xsl:text>
    <xsl:value-of select="concat(upper-case(substring($type, 1, 1)), substring($type, 2))"/>
    <xsl:text> ;&#10;</xsl:text>
    <xsl:text>    dcterms:identifier "</xsl:text><xsl:value-of select="@id"/><xsl:text>" ;&#10;</xsl:text>
    <xsl:if test="$type = 'node' and @lat and @lon">
      <xsl:text>    geo:lat "</xsl:text><xsl:value-of select="@lat"/><xsl:text>" ;&#10;</xsl:text>
      <xsl:text>    geo:long "</xsl:text><xsl:value-of select="@lon"/><xsl:text>" ;&#10;</xsl:text>
    </xsl:if>
    <xsl:text>    foaf:page &lt;https://www.openstreetmap.org/</xsl:text><xsl:value-of select="$type"/><xsl:text>/</xsl:text><xsl:value-of select="@id"/><xsl:text>&gt; ;&#10;</xsl:text>
    <xsl:text>    foaf:page &lt;</xsl:text><xsl:value-of select="$source-prefix"/><xsl:text>/</xsl:text><xsl:value-of select="$type"/><xsl:text>/</xsl:text><xsl:value-of select="@id"/><xsl:text>.rdf&gt; ;&#10;</xsl:text>
    <xsl:text>    foaf:page &lt;</xsl:text><xsl:value-of select="$source-prefix"/><xsl:text>/</xsl:text><xsl:value-of select="$type"/><xsl:text>/</xsl:text><xsl:value-of select="@id"/><xsl:text>.ttl&gt; ;&#10;</xsl:text>
    <xsl:text>    foaf:page &lt;</xsl:text><xsl:value-of select="$source-prefix"/><xsl:text>/</xsl:text><xsl:value-of select="$type"/><xsl:text>/</xsl:text><xsl:value-of select="@id"/><xsl:text>.json&gt;</xsl:text>
    <xsl:if test="$has-geo">
      <xsl:text> ;&#10;    geom:geometry &lt;</xsl:text><xsl:value-of select="$source-prefix"/><xsl:text>/</xsl:text><xsl:value-of select="$type"/><xsl:text>/</xsl:text><xsl:value-of select="@id"/><xsl:text>#geo&gt;</xsl:text>
    </xsl:if>
    <!-- Tags and members (each emits: ; <predicate> object) -->
    <xsl:apply-templates/>
    <xsl:text> .&#10;</xsl:text>
    <xsl:text>&#10;</xsl:text>

    <!-- Primary source (versioned OSM API URL) -->
    <xsl:if test="@version">
      <xsl:call-template name="versioned-source">
        <xsl:with-param name="type"      select="$type"/>
        <xsl:with-param name="id"        select="@id"/>
        <xsl:with-param name="version"   select="@version"/>
        <xsl:with-param name="changeset" select="@changeset"/>
      </xsl:call-template>
    </xsl:if>

    <!-- Geometry resource -->
    <xsl:if test="$has-geo">
      <xsl:call-template name="geometry-resource">
        <xsl:with-param name="source-prefix" select="$source-prefix"/>
        <xsl:with-param name="type"          select="$type"/>
        <xsl:with-param name="id"            select="@id"/>
        <xsl:with-param name="centroid-lat"  select="$centroid-lat"/>
        <xsl:with-param name="centroid-lon"  select="$centroid-lon"/>
        <xsl:with-param name="geometry-gml"  select="$geometry-gml"/>
      </xsl:call-template>
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

</xsl:stylesheet>
