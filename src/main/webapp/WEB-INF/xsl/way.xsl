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

  <xsl:template match="osm">
    <rdf:RDF>
      <rdf:Description rdf:about="">
	<rdfs:comment><xsl:value-of select="@generator"/></rdfs:comment>
	<rdfs:comment><xsl:value-of select="@copyright"/></rdfs:comment>
	<dc:attribution><xsl:value-of select="@attribution"/></dc:attribution>
	<dc:license><xsl:value-of select="@license"/></dc:license>
	<dc:date><xsl:value-of select="relation/@timestamp"/></dc:date>
      </rdf:Description>

      <xsl:apply-templates/>
    </rdf:RDF>
  </xsl:template>

  <xsl:template match="way">
      <spatial:Feature>
	<xsl:attribute name="rdf:about">/way/<xsl:value-of select="@id"/>#id</xsl:attribute>

	<owl:sameAs>
	  <xsl:attribute name="rdf:resource">http://linkedgeodata.org/triplify/way<xsl:value-of select="@id"/></xsl:attribute>
	</owl:sameAs>

	<xsl:apply-templates/>

	<geom:geometry>
	  <!-- content negotiation there, kml, html etc -->
	  <geom:Geometry>
	    <xsl:attribute name="rdf:about">/geo/way/<xsl:value-of select="@id"/></xsl:attribute>
	  </geom:Geometry>
	</geom:geometry>
      </spatial:Feature>
  </xsl:template>

<!--
  <xsl:template match="nd">
      <rdfs:seeAlso>
	<xsl:attribute name="rdf:resource">/node/<xsl:value-of select="@ref"/>#id</xsl:attribute>
      </rdfs:seeAlso>
  </xsl:template>
-->

  <xsl:template match="tag[@k = 'name:en']">
    <rdfs:label><xsl:value-of select="@v"/></rdfs:label>
  </xsl:template>

  <xsl:template match="tag[@k = 'wikipedia']">
    <foaf:page>
      <xsl:choose>
	<xsl:when test="contains(@v, ':')">
	  <xsl:attribute name="rdf:resource">http://<xsl:value-of select="substring(@v, 0, 3)"/>.wikipedia.org/wiki/<xsl:value-of select="substring(@v, 4)"/></xsl:attribute>
	</xsl:when>
	<xsl:otherwise>
	  <xsl:attribute name="rdf:resource">http://en.wikipedia.org/wiki/<xsl:value-of select="@v"/></xsl:attribute>
	</xsl:otherwise>
      </xsl:choose>
    </foaf:page>

    <owl:sameAs>
      <xsl:choose>
	<xsl:when test="contains(@v, ':')">
	  <xsl:choose>
	    <xsl:when test="substring(@v, 0, 3) = 'en'">
	      <xsl:attribute name="rdf:resource">http://dbpedia.org/resource/<xsl:value-of select="substring(@v, 4)"/></xsl:attribute>
	    </xsl:when>
	    <xsl:otherwise>
	      <xsl:attribute name="rdf:resource">http://<xsl:value-of select="substring(@v, 0, 3)"/>.dbpedia.org/resource/<xsl:value-of select="substring(@v, 4)"/></xsl:attribute>
	    </xsl:otherwise>
	  </xsl:choose>
	</xsl:when>
	<xsl:otherwise>
	  <xsl:attribute name="rdf:resource">http://dbpedia.org/resource/<xsl:value-of select="@v"/></xsl:attribute>
	</xsl:otherwise>
      </xsl:choose>
    </owl:sameAs>
  </xsl:template>

</xsl:stylesheet>
