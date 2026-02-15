<xsl:stylesheet
   xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
   xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
   xmlns:foaf="http://xmlns.com/foaf/0.1/"
   xmlns:owl="http://www.w3.org/2002/07/owl#"
   xmlns:dc="http://purl.org/dc/elements/1.1/"
   xmlns:sioc="http://rdfs.org/sioc/ns#"
   xmlns:geo="http://www.w3.org/2003/01/geo/wgs84_pos#"
   xmlns:prov="http://www.w3.org/ns/prov#"
   xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
   version="2.0">
  
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
	<dc:publisher>OpenStreetMap Contributors (https://www.openstreetmap.org/) via Linked OSM (http://osmwrap.ontologycentral.com/)</dc:publisher>
	<rdfs:seeAlso rdf:resource="https://www.openstreetmap.org/copyright"/>
	<rdfs:seeAlso rdf:resource="https://wiki.openstreetmap.org/wiki/Legal_FAQ"/>
	<prov:wasGeneratedBy rdf:resource="#transformation"/>
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

      <!-- PROV: Transformation activity -->
      <prov:Activity rdf:about="#transformation">
        <rdfs:label>OSM XML to RDF Map Transformation</rdfs:label>
        <prov:used rdf:resource="https://api.openstreetmap.org/api/0.6/map"/>
        <prov:wasAssociatedWith rdf:resource="#osmwrap"/>
        <dc:date rdf:datatype="http://www.w3.org/2001/XMLSchema#dateTime"><xsl:value-of select="current-dateTime()"/></dc:date>
      </prov:Activity>

      <!-- PROV: Agent (osmwrap service) -->
      <prov:SoftwareAgent rdf:about="#osmwrap">
        <rdfs:label>Linked OSM (osmwrap)</rdfs:label>
        <foaf:homepage rdf:resource="http://osmwrap.ontologycentral.com/"/>
        <dc:description>Service for converting OpenStreetMap data to RDF</dc:description>
      </prov:SoftwareAgent>

      <xsl:apply-templates/>
    </rdf:RDF>
  </xsl:template>

  <xsl:import href="node.xsl"/>
  <xsl:import href="relation.xsl"/>
  <xsl:import href="way.xsl"/>
</xsl:stylesheet>
