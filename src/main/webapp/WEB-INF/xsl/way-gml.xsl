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

  <xsl:key name="nodeById" match="node" use="@id"/>

  <xsl:template match="osm">
    <wfs:FeatureCollection
        gml:id="linked-osm"
        numberMatched="1"
        numberReturned="1">
      <xsl:apply-templates select="way"/>
    </wfs:FeatureCollection>
  </xsl:template>

  <xsl:template match="way">
    <xsl:variable name="firstRef" select="nd[1]/@ref"/>
    <xsl:variable name="lastRef"  select="nd[last()]/@ref"/>
    <xsl:variable name="ndCount"  select="count(nd)"/>
    <xsl:variable name="closed"   select="$ndCount >= 4 and $firstRef = $lastRef"/>
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
    <wfs:member>
      <osm:way>
        <xsl:attribute name="gml:id">way.<xsl:value-of select="@id"/></xsl:attribute>
        <xsl:if test="normalize-space($gmlCoords) != ''">
          <osm:geometry>
            <xsl:choose>
              <xsl:when test="$closed">
                <gml:Polygon srsName="http://www.opengis.net/def/crs/OGC/1.3/CRS84">
                  <gml:exterior>
                    <gml:LinearRing>
                      <gml:posList><xsl:value-of select="$gmlCoords"/></gml:posList>
                    </gml:LinearRing>
                  </gml:exterior>
                </gml:Polygon>
              </xsl:when>
              <xsl:otherwise>
                <gml:LineString srsName="http://www.opengis.net/def/crs/OGC/1.3/CRS84">
                  <gml:posList><xsl:value-of select="$gmlCoords"/></gml:posList>
                </gml:LineString>
              </xsl:otherwise>
            </xsl:choose>
          </osm:geometry>
        </xsl:if>
        <xsl:apply-templates select="tag"/>
      </osm:way>
    </wfs:member>
  </xsl:template>

  <xsl:template match="tag">
    <osm:tag>
      <xsl:attribute name="key"><xsl:value-of select="@k"/></xsl:attribute>
      <xsl:attribute name="value"><xsl:value-of select="@v"/></xsl:attribute>
    </osm:tag>
  </xsl:template>

</xsl:stylesheet>
