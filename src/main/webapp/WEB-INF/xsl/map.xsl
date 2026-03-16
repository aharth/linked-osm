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
	<dc:publisher>OpenStreetMap Contributors (https://www.openstreetmap.org/) via Linked OSM (https://osmwrap.ontologycentral.com/)</dc:publisher>
	<rdfs:seeAlso rdf:resource="https://www.openstreetmap.org/copyright"/>
	<rdfs:seeAlso rdf:resource="https://wiki.openstreetmap.org/wiki/Legal_FAQ"/>
	<xsl:if test="$upstream-url != ''">
	  <prov:hadPrimarySource rdf:resource="{$upstream-url}"/>
	</xsl:if>
	<prov:generatedAtTime rdf:datatype="http://www.w3.org/2001/XMLSchema#dateTime"><xsl:value-of select="current-dateTime()"/></prov:generatedAtTime>
	<prov:wasAttributedTo rdf:resource="/#osmwrap"/>
	<xsl:for-each select="node">
	  <rdfs:seeAlso>
	    <rdf:Description>
	      <rdfs:seeAlso rdf:resource="/node/{@id}#id"/>
	    </rdf:Description>
	  </rdfs:seeAlso>
	</xsl:for-each>
	<xsl:for-each select="way">
	  <rdfs:seeAlso>
	    <rdf:Description>
	      <rdfs:seeAlso rdf:resource="/way/{@id}#id"/>
	    </rdf:Description>
	  </rdfs:seeAlso>
	</xsl:for-each>
	<xsl:for-each select="relation">
	  <rdfs:seeAlso>
	    <rdf:Description>
	      <rdfs:seeAlso rdf:resource="/relation/{@id}#id"/>
	    </rdf:Description>
	  </rdfs:seeAlso>
	</xsl:for-each>
      </rdf:Description>

      <xsl:if test="$upstream-url != '' and $upstream-bytes >= 0">
        <rdf:Description rdf:about="{$upstream-url}">
          <dcat:byteSize rdf:datatype="http://www.w3.org/2001/XMLSchema#decimal"><xsl:value-of select="$upstream-bytes"/></dcat:byteSize>
        </rdf:Description>
      </xsl:if>

    </rdf:RDF>
  </xsl:template>

</xsl:stylesheet>
