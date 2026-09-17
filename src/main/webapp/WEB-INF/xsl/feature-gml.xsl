<?xml version="1.0" encoding="UTF-8"?>
<!-- WFS-style GML feature collection for one OSM element (node, way or relation).
     Input and parameters as for feature.xsl; the geometry arrives as a GML string and is
     parsed back into the result tree. -->
<xsl:stylesheet
  xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
  xmlns:wfs="http://www.opengis.net/wfs/2.0"
  xmlns:gml="http://www.opengis.net/gml/3.2"
  xmlns:osm="http://osm.geovocab.org/vocab#"
  exclude-result-prefixes="xsl"
  version="3.0">

  <xsl:output method="xml" encoding="UTF-8" indent="yes"/>

  <xsl:strip-space elements="*"/>

  <xsl:param name="element-type" select="''"/>
  <xsl:param name="element-id" select="''"/>
  <xsl:param name="geometry-gml" select="''"/>

  <xsl:template match="osm">
    <wfs:FeatureCollection
        gml:id="linked-osm"
        numberMatched="1"
        numberReturned="1">
      <xsl:apply-templates select="*[local-name() = $element-type and @id = $element-id]"/>
    </wfs:FeatureCollection>
  </xsl:template>

  <xsl:template match="node | way | relation">
    <wfs:member>
      <xsl:element name="osm:{local-name()}">
        <xsl:attribute name="gml:id"><xsl:value-of select="local-name()"/>.<xsl:value-of select="@id"/></xsl:attribute>
        <xsl:if test="normalize-space($geometry-gml) != ''">
          <osm:geometry>
            <xsl:copy-of select="parse-xml($geometry-gml)/*"/>
          </osm:geometry>
        </xsl:if>
        <xsl:apply-templates select="tag"/>
        <xsl:apply-templates select="member"/>
      </xsl:element>
    </wfs:member>
  </xsl:template>

  <xsl:template match="tag">
    <osm:tag>
      <xsl:attribute name="key"><xsl:value-of select="@k"/></xsl:attribute>
      <xsl:attribute name="value"><xsl:value-of select="@v"/></xsl:attribute>
    </osm:tag>
  </xsl:template>

  <xsl:template match="member">
    <osm:member>
      <xsl:attribute name="type"><xsl:value-of select="@type"/></xsl:attribute>
      <xsl:attribute name="ref"><xsl:value-of select="@ref"/></xsl:attribute>
      <xsl:attribute name="role"><xsl:value-of select="@role"/></xsl:attribute>
    </osm:member>
  </xsl:template>

</xsl:stylesheet>
