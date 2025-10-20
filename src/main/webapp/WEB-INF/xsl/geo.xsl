<xsl:stylesheet
   xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
   xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
   xmlns:foaf="http://xmlns.com/foaf/0.1/"
   xmlns:owl="http://www.w3.org/2002/07/owl#"
   xmlns:dc="http://purl.org/dc/elements/1.1/"
   xmlns:sioc="http://rdfs.org/sioc/ns#"
   xmlns:geo="http://www.w3.org/2003/01/geo/wgs84_pos#"
   xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
   xmlns:spatial="http://geovocab.org/spatial#"
   xmlns:geom="http://geovocab.org/geometry#"
   xmlns:ogc="http://www.opengis.net/ont/geosparql#"
   version="1.0">
  
  <xsl:output method="text"/>

  <xsl:strip-space elements="*"/>

  <xsl:template match="rdf:RDF|rdf:Description|foaf:primaryTopic|geom:Geometry">
    <xsl:apply-templates/>
  </xsl:template>

  <xsl:template match="ogc:asWKT"><xsl:value-of select="."/></xsl:template>

  <xsl:template match="*"/>
</xsl:stylesheet>
