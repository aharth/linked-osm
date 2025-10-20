<xsl:stylesheet
   xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
   xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
   xmlns:foaf="http://xmlns.com/foaf/0.1/"
   xmlns:owl="http://www.w3.org/2002/07/owl#"
   xmlns:dc="http://purl.org/dc/elements/1.1/"
   xmlns:sioc="http://rdfs.org/sioc/ns#"
   xmlns:geo="http://www.w3.org/2003/01/geo/wgs84_pos#"
   xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
   version="1.0">
  
  <xsl:output method="xml"/>

  <xsl:strip-space elements="*"/>

  <xsl:template match="osm">
    <rdf:RDF>
      <rdf:Description rdf:about="">
	<rdfs:comment><xsl:value-of select="@generator"/></rdfs:comment>
	<rdfs:comment><xsl:value-of select="@copyright"/></rdfs:comment>
	<dc:attribution><xsl:value-of select="@attribution"/></dc:attribution>
	<dc:license><xsl:value-of select="@license"/></dc:license>
	<dc:date><xsl:value-of select="node/@timestamp"/></dc:date>
      </rdf:Description>

      <xsl:apply-templates/>
    </rdf:RDF>
  </xsl:template>

  <xsl:include href="node.xsl"/>
  <xsl:include href="relation.xsl"/>
  <xsl:include href="way.xsl"/>
</xsl:stylesheet>
