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
   xmlns:prov="http://www.w3.org/ns/prov#"
   xmlns:dcat="http://www.w3.org/ns/dcat#"
   xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
   version="2.0">
  
  <xsl:output method="xml"/>

  <xsl:strip-space elements="*"/>

  <xsl:param name="upstream-bytes" select="-1"/>
  <xsl:param name="upstream-url" select="'https://nominatim.openstreetmap.org/'"/>

  <xsl:template match="searchresults">
    <rdf:RDF>
      <rdf:Description rdf:about="">
	<dc:attribution><xsl:value-of select="@attribution"/></dc:attribution>
	<dc:date><xsl:value-of select="@timestamp"/></dc:date>
	<dc:publisher>Nominatim (https://nominatim.openstreetmap.org/) via Linked OSM (https://osmwrap.ontologycentral.com/)</dc:publisher>
	<prov:hadPrimarySource>
	  <xsl:attribute name="rdf:resource"><xsl:value-of select="$upstream-url"/></xsl:attribute>
	</prov:hadPrimarySource>
	<prov:generatedAtTime rdf:datatype="http://www.w3.org/2001/XMLSchema#dateTime"><xsl:value-of select="current-dateTime()"/></prov:generatedAtTime>
	<prov:wasAttributedTo rdf:resource="/#osmwrap"/>
	<xsl:apply-templates/>
      </rdf:Description>

      <xsl:if test="$upstream-bytes >= 0">
        <rdf:Description>
          <xsl:attribute name="rdf:about"><xsl:value-of select="$upstream-url"/></xsl:attribute>
          <dcat:byteSize rdf:datatype="http://www.w3.org/2001/XMLSchema#decimal"><xsl:value-of select="$upstream-bytes"/></dcat:byteSize>
        </rdf:Description>
      </xsl:if>

    </rdf:RDF>
  </xsl:template>

  <xsl:template match="place">
      <rdfs:seeAlso>
	<rdf:Description>
	  <rdfs:seeAlso>
	    <rdf:Description>
	      <xsl:attribute name="rdf:about">./<xsl:value-of select="@osm_type"/>/<xsl:value-of select="@osm_id"/>#id</xsl:attribute>
	      <rdfs:label><xsl:value-of select="@display_name"/></rdfs:label>
	      <geo:lat><xsl:value-of select="@lat"/></geo:lat>
	      <geo:long><xsl:value-of select="@lon"/></geo:long>
	    </rdf:Description>
	  </rdfs:seeAlso>
	  <xsl:if test="@importance">
	    <rdfs:comment>importance: <xsl:value-of select="@importance"/></rdfs:comment>
	  </xsl:if>
	</rdf:Description>
      </rdfs:seeAlso>
  </xsl:template>
</xsl:stylesheet>
