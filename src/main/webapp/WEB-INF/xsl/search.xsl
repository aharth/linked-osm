<xsl:stylesheet
   xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
   xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
   xmlns:foaf="http://xmlns.com/foaf/0.1/"
   xmlns:owl="http://www.w3.org/2002/07/owl#"
   xmlns:dc="http://purl.org/dc/elements/1.1/"
   xmlns:sioc="http://rdfs.org/sioc/ns#"
   xmlns:geo="http://www.w3.org/2003/01/geo/wgs84_pos#"
   xmlns:geom="http://geovocab.org/geometry#"
   xmlns:spatial="http://geovocab.org/spatial#"
   xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
   version="1.0">
  
  <xsl:output method="xml"/>

  <xsl:strip-space elements="*"/>

  <xsl:template match="searchresults">
    <rdf:RDF>
      <rdf:Description rdf:about="">
	<dc:attribution><xsl:value-of select="@attribution"/></dc:attribution>
	<dc:date><xsl:value-of select="@timestamp"/></dc:date>
      </rdf:Description>

      <xsl:apply-templates/>
    </rdf:RDF>
  </xsl:template>

  <xsl:template match="place">
      <rdf:Description>
	<xsl:attribute name="rdf:about">./<xsl:value-of select="@osm_type"/>/<xsl:value-of select="@osm_id"/>#id</xsl:attribute>
	<rdf:type><xsl:attribute name="rdf:resource">http://geovocab.org/spatial#Feature</xsl:attribute></rdf:type>
	<rdfs:label><xsl:value-of select="@display_name"/></rdfs:label>
      </rdf:Description>
  </xsl:template>
</xsl:stylesheet>
