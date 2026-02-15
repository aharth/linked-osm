<xsl:stylesheet
   xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
   xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
   xmlns:foaf="http://xmlns.com/foaf/0.1/"
   xmlns:owl="http://www.w3.org/2002/07/owl#"
   xmlns:dc="http://purl.org/dc/elements/1.1/"
   xmlns:sioc="http://rdfs.org/sioc/ns#"
   xmlns:geo="http://www.w3.org/2003/01/geo/wgs84_pos#"
   xmlns:geom="http://geovocab.org/geometry#"
   xmlns:prov="http://www.w3.org/ns/prov#"
   xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
   xmlns="http://osm.geovocab.org/vocab#"
   version="1.0">
  
  <xsl:output method="xml"/>

  <xsl:strip-space elements="*"/>

  <xsl:template match="osm">
    <rdf:RDF>
      <rdf:Description rdf:about="">
	<rdfs:comment>No guarantee of correctness! USE AT YOUR OWN RISK!</rdfs:comment>
	<rdfs:comment><xsl:value-of select="@generator"/></rdfs:comment>
	<rdfs:comment><xsl:value-of select="@copyright"/></rdfs:comment>
	<dc:publisher>OpenStreetMap Contributors (https://www.openstreetmap.org/) via Linked OSM (http://osmwrap.ontologycentral.com/)</dc:publisher>
	<dc:attribution><xsl:value-of select="@attribution"/></dc:attribution>
	<dc:license><xsl:value-of select="@license"/></dc:license>
	<dc:date><xsl:value-of select="node/@timestamp"/></dc:date>
	<rdfs:seeAlso rdf:resource="https://www.openstreetmap.org/copyright"/>
	<rdfs:seeAlso rdf:resource="https://wiki.openstreetmap.org/wiki/Legal_FAQ"/>
	<prov:wasGeneratedBy rdf:resource="#transformation"/>
      </rdf:Description>

      <!-- PROV: Transformation activity -->
      <prov:Activity rdf:about="#transformation">
        <rdfs:label>OSM XML to RDF Node Transformation</rdfs:label>
        <prov:used>
          <xsl:attribute name="rdf:resource">https://api.openstreetmap.org/api/0.6/node/<xsl:value-of select="node/@id"/></xsl:attribute>
        </prov:used>
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

  <xsl:template match="node">
      <rdf:Description>
	<xsl:attribute name="rdf:about">/node/<xsl:value-of select="@id"/>#id</xsl:attribute>
	<geo:lat><xsl:value-of select="@lat"/></geo:lat>
	<geo:long><xsl:value-of select="@lon"/></geo:long>

	<!-- Links to document representations -->
	<foaf:page rdf:resource="https://www.openstreetmap.org/node/{@id}"/>
	<foaf:page rdf:resource="/node/{@id}.rdf"/>
	<foaf:page rdf:resource="/node/{@id}.json"/>

	<!-- Geometry representation -->
	<geom:geometry rdf:resource="/geo/overpass/node/{@id}"/>
	<geom:geometry rdf:resource="/geo/osm/node/{@id}"/>

	<!-- PROV-O properties -->
	<xsl:if test="@changeset">
	  <prov:wasGeneratedBy rdf:resource="/changeset/{@changeset}"/>
	</xsl:if>
	<xsl:if test="@timestamp">
	  <prov:generatedAtTime rdf:datatype="http://www.w3.org/2001/XMLSchema#dateTime">
	    <xsl:value-of select="@timestamp"/>
	  </prov:generatedAtTime>
	</xsl:if>
	<xsl:if test="@user">
	  <prov:wasAttributedTo>
	    <prov:Agent>
	      <foaf:accountName><xsl:value-of select="@user"/></foaf:accountName>
	      <foaf:accountServiceHomepage rdf:resource="https://www.openstreetmap.org"/>
	      <foaf:homepage rdf:resource="https://www.openstreetmap.org/user/{@user}"/>
	    </prov:Agent>
	  </prov:wasAttributedTo>
	</xsl:if>
	<xsl:if test="@version">
	  <prov:hadPrimarySource rdf:resource="https://api.openstreetmap.org/api/0.6/node/{@id}/{@version}"/>
	  <prov:value><xsl:value-of select="@version"/></prov:value>
	</xsl:if>

	<xsl:apply-templates/>
      </rdf:Description>

      <!-- Changeset as Activity -->
      <xsl:if test="@changeset and @timestamp and @user">
	<prov:Activity rdf:about="/changeset/{@changeset}">
	  <prov:endedAtTime rdf:datatype="http://www.w3.org/2001/XMLSchema#dateTime">
	    <xsl:value-of select="@timestamp"/>
	  </prov:endedAtTime>
	  <prov:wasAssociatedWith>
	    <prov:Agent>
	      <foaf:accountName><xsl:value-of select="@user"/></foaf:accountName>
	      <foaf:accountServiceHomepage rdf:resource="https://www.openstreetmap.org"/>
	      <foaf:homepage rdf:resource="https://www.openstreetmap.org/user/{@user}"/>
	    </prov:Agent>
	  </prov:wasAssociatedWith>
	</prov:Activity>
      </xsl:if>
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

  <xsl:template match="tag">
    <xsl:variable name="tag" select="@k"/>

    <xsl:if test="not(contains($tag, ':'))">
      <xsl:element name="{$tag}">
	<xsl:value-of select="@v"/>
      </xsl:element>
    </xsl:if>
  </xsl:template>
</xsl:stylesheet>
