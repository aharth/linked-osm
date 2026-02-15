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
	<dc:attribution>Overpass API (https://overpass-api.de/)</dc:attribution>
	<xsl:for-each select="note">
	  <rdfs:comment><xsl:value-of select="."/></rdfs:comment>
	</xsl:for-each>
      </rdf:Description>

      <xsl:apply-templates select="node"/>
    </rdf:RDF>
  </xsl:template>

  <xsl:template match="node">
      <rdf:Description>
	<xsl:attribute name="rdf:about">/node/<xsl:value-of select="@id"/>#id</xsl:attribute>
	<geo:lat><xsl:value-of select="@lat"/></geo:lat>
	<geo:long><xsl:value-of select="@lon"/></geo:long>

	
	<xsl:apply-templates/>
      </rdf:Description>
  </xsl:template>


  <!-- could be either in the form fr:Paris or just Paris -->
  <xsl:template match="tag[@k = 'wikipedia']">
    <xsl:choose>
      <xsl:when test="contains(@v, ':')">
	<foaf:page>
	  <xsl:attribute name="rdf:resource">http://<xsl:value-of select="substring(@v, 0, 3)"/>.wikipedia.org/wiki/<xsl:value-of select="substring(@v, 4)"/></xsl:attribute>
	</foaf:page>
	<owl:sameAs>
	  <xsl:choose>
	    <xsl:when test="substring(@v, 0, 3) = 'en'">
	      <xsl:attribute name="rdf:resource">http://dbpedia.org/resource/<xsl:value-of select="substring(@v, 4)"/></xsl:attribute>
	    </xsl:when>
	    <xsl:otherwise>
	      <xsl:attribute name="rdf:resource">http://<xsl:value-of select="substring(@v, 0, 3)"/>.dbpedia.org/resource/<xsl:value-of select="substring(@v, 4)"/></xsl:attribute>
	    </xsl:otherwise>
	  </xsl:choose>
	</owl:sameAs>
      </xsl:when>
      <xsl:otherwise>
	<owl:sameAs>
	  <xsl:attribute name="rdf:resource">http://dbpedia.org/resource/<xsl:value-of select="@v"/></xsl:attribute>
	</owl:sameAs>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
</xsl:stylesheet>
