<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet
  xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
  xmlns:wfs="http://www.opengis.net/wfs/2.0"
  xmlns:gml="http://www.opengis.net/gml/3.2"
  xmlns:osm="http://osm.geovocab.org/vocab#"
  exclude-result-prefixes="xsl"
  version="2.0">

  <xsl:output method="xml" encoding="UTF-8" indent="yes"/>

  <xsl:strip-space elements="*"/>

  <!-- Mean member node coordinates, computed in Java (see relation.xsl). Empty = no geometry. -->
  <xsl:param name="centroid-lat" select="''"/>
  <xsl:param name="centroid-lon" select="''"/>
  <!-- id of the requested relation; the point geometry is emitted for that one only -->
  <xsl:param name="element-id" select="''"/>

  <xsl:template match="osm">
    <wfs:FeatureCollection
        gml:id="linked-osm"
        numberMatched="1"
        numberReturned="1">
      <xsl:apply-templates select="relation"/>
    </wfs:FeatureCollection>
  </xsl:template>

  <xsl:template match="relation">
    <wfs:member>
      <osm:relation>
        <xsl:attribute name="gml:id">relation.<xsl:value-of select="@id"/></xsl:attribute>
        <xsl:if test="normalize-space($centroid-lat) != '' and normalize-space($centroid-lon) != ''
                      and ($element-id = '' or @id = $element-id)">
          <osm:geometry>
            <gml:Point srsName="http://www.opengis.net/def/crs/OGC/1.3/CRS84">
              <gml:pos>
                <xsl:value-of select="$centroid-lon"/>
                <xsl:text> </xsl:text>
                <xsl:value-of select="$centroid-lat"/>
              </gml:pos>
            </gml:Point>
          </osm:geometry>
        </xsl:if>
        <xsl:apply-templates select="tag"/>
        <xsl:apply-templates select="member"/>
      </osm:relation>
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
