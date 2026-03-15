<xsl:stylesheet
   xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
   xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
   xmlns:foaf="http://xmlns.com/foaf/0.1/"
   xmlns:owl="http://www.w3.org/2002/07/owl#"
   xmlns:dc="http://purl.org/dc/elements/1.1/"
   xmlns:sioc="http://rdfs.org/sioc/ns#"
   xmlns:geo="http://www.w3.org/2003/01/geo/wgs84_pos#"
   xmlns:prov="http://www.w3.org/ns/prov#"
   xmlns:dcat="http://www.w3.org/ns/dcat#"
   xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
   version="2.0">
  
  <xsl:output method="xml"/>

  <xsl:strip-space elements="*"/>

  <xsl:param name="upstream-bytes" select="-1"/>
  <xsl:param name="upstream-url" select="''"/>

  <xsl:template match="osm">
    <rdf:RDF>
      <rdf:Description rdf:about="">
	<rdfs:comment><xsl:value-of select="@generator"/></rdfs:comment>
	<dc:attribution>&#169; OpenStreetMap contributors</dc:attribution>
	<dc:license rdf:resource="https://opendatacommons.org/licenses/odbl/"/>
	<dc:publisher>Overpass API (https://overpass-api.de/) via Linked OSM (https://osmwrap.ontologycentral.com/)</dc:publisher>
	<xsl:for-each select="note">
	  <rdfs:comment><xsl:value-of select="."/></rdfs:comment>
	</xsl:for-each>
	<xsl:if test="$upstream-url != ''">
	  <prov:hadPrimarySource rdf:resource="{$upstream-url}"/>
	</xsl:if>
	<prov:generatedAtTime rdf:datatype="http://www.w3.org/2001/XMLSchema#dateTime"><xsl:value-of select="current-dateTime()"/></prov:generatedAtTime>
	<prov:wasAttributedTo rdf:resource="/#osmwrap"/>
	<xsl:apply-templates select="node"/>
      </rdf:Description>

      <xsl:if test="$upstream-url != '' and $upstream-bytes >= 0">
        <rdf:Description rdf:about="{$upstream-url}">
          <dcat:byteSize rdf:datatype="http://www.w3.org/2001/XMLSchema#decimal"><xsl:value-of select="$upstream-bytes"/></dcat:byteSize>
        </rdf:Description>
      </xsl:if>

      <!-- PROV: Agent (osmwrap service) -->
      <prov:SoftwareAgent rdf:about="/#osmwrap">
        <rdfs:label>Linked OSM (osmwrap)</rdfs:label>
        <foaf:homepage rdf:resource="https://osmwrap.ontologycentral.com/"/>
        <dc:description>Service for converting OpenStreetMap data to RDF</dc:description>
      </prov:SoftwareAgent>
    </rdf:RDF>
  </xsl:template>

  <xsl:template match="node">
      <rdfs:seeAlso>
	<rdf:Description>
	  <rdfs:seeAlso>
	    <rdf:Description>
	      <xsl:attribute name="rdf:about">/node/<xsl:value-of select="@id"/>#id</xsl:attribute>
	      <geo:lat><xsl:value-of select="@lat"/></geo:lat>
	      <geo:long><xsl:value-of select="@lon"/></geo:long>
	      <xsl:apply-templates/>
	    </rdf:Description>
	  </rdfs:seeAlso>
	</rdf:Description>
      </rdfs:seeAlso>
  </xsl:template>


  <!-- could be either in the form fr:Paris or just Paris -->
  <xsl:template match="tag[@k = 'wikipedia']">
    <xsl:choose>
      <xsl:when test="contains(@v, ':')">
	<foaf:page>
	  <xsl:attribute name="rdf:resource">http://<xsl:value-of select="substring(@v, 0, 3)"/>.wikipedia.org/wiki/<xsl:value-of select="encode-for-uri(substring(@v, 4))"/></xsl:attribute>
	</foaf:page>
	<owl:sameAs>
	  <xsl:choose>
	    <xsl:when test="substring(@v, 0, 3) = 'en'">
	      <xsl:attribute name="rdf:resource">http://dbpedia.org/resource/<xsl:value-of select="encode-for-uri(substring(@v, 4))"/></xsl:attribute>
	    </xsl:when>
	    <xsl:otherwise>
	      <xsl:attribute name="rdf:resource">http://<xsl:value-of select="substring(@v, 0, 3)"/>.dbpedia.org/resource/<xsl:value-of select="encode-for-uri(substring(@v, 4))"/></xsl:attribute>
	    </xsl:otherwise>
	  </xsl:choose>
	</owl:sameAs>
      </xsl:when>
      <xsl:otherwise>
	<owl:sameAs>
	  <xsl:attribute name="rdf:resource">http://dbpedia.org/resource/<xsl:value-of select="encode-for-uri(@v)"/></xsl:attribute>
	</owl:sameAs>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
</xsl:stylesheet>
