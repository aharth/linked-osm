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

  <xsl:template match="osm">
    <wfs:FeatureCollection
        gml:id="linked-osm"
        numberMatched="1"
        numberReturned="1">
      <xsl:apply-templates select="node"/>
    </wfs:FeatureCollection>
  </xsl:template>

  <xsl:template match="node">
    <wfs:member>
      <osm:node>
        <xsl:attribute name="gml:id">node.<xsl:value-of select="@id"/></xsl:attribute>
        <osm:geometry>
          <gml:Point srsName="http://www.opengis.net/def/crs/OGC/1.3/CRS84">
            <gml:pos><xsl:value-of select="@lon"/><xsl:text> </xsl:text><xsl:value-of select="@lat"/></gml:pos>
          </gml:Point>
        </osm:geometry>
        <xsl:apply-templates select="tag"/>
      </osm:node>
    </wfs:member>
  </xsl:template>

  <xsl:template match="tag">
    <osm:tag>
      <xsl:attribute name="key"><xsl:value-of select="@k"/></xsl:attribute>
      <xsl:attribute name="value"><xsl:value-of select="@v"/></xsl:attribute>
    </osm:tag>
  </xsl:template>

</xsl:stylesheet>
