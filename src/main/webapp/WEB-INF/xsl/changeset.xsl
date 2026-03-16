<xsl:stylesheet
   xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
   xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
   xmlns:foaf="http://xmlns.com/foaf/0.1/"
   xmlns:owl="http://www.w3.org/2002/07/owl#"
   xmlns:dc="http://purl.org/dc/elements/1.1/"
   xmlns:geo="http://www.w3.org/2003/01/geo/wgs84_pos#"
   xmlns:prov="http://www.w3.org/ns/prov#"
   xmlns:dcat="http://www.w3.org/ns/dcat#"
   xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
   xmlns="http://osm.geovocab.org/vocab#"
   version="2.0">

  <xsl:output method="xml"/>

  <xsl:strip-space elements="*"/>

  <xsl:param name="upstream-bytes" select="-1"/>

  <xsl:template match="osm">
    <rdf:RDF>
      <rdf:Description rdf:about="">
	<rdfs:comment>No guarantee of correctness! USE AT YOUR OWN RISK!</rdfs:comment>
	<rdfs:comment><xsl:value-of select="@generator"/></rdfs:comment>
	<rdfs:comment><xsl:value-of select="@copyright"/></rdfs:comment>
	<dc:publisher>OpenStreetMap Contributors (https://www.openstreetmap.org/) via Linked OSM (https://osmwrap.ontologycentral.com/)</dc:publisher>
	<dc:attribution><xsl:value-of select="@attribution"/></dc:attribution>
	<dc:license><xsl:value-of select="@license"/></dc:license>
	<dc:date><xsl:value-of select="changeset/@created_at"/></dc:date>
	<rdfs:seeAlso rdf:resource="https://www.openstreetmap.org/copyright"/>
	<rdfs:seeAlso rdf:resource="https://wiki.openstreetmap.org/wiki/Legal_FAQ"/>
	<prov:wasGeneratedBy rdf:resource="#transformation"/>
      </rdf:Description>

      <!-- PROV: Transformation activity -->
      <prov:Activity rdf:about="#transformation">
        <rdfs:label>OSM XML to RDF Changeset Transformation</rdfs:label>
        <prov:used>
          <prov:Entity>
            <xsl:attribute name="rdf:about">https://api.openstreetmap.org/api/0.6/changeset/<xsl:value-of select="changeset/@id"/></xsl:attribute>
            <xsl:if test="$upstream-bytes >= 0">
              <dcat:byteSize rdf:datatype="http://www.w3.org/2001/XMLSchema#decimal"><xsl:value-of select="$upstream-bytes"/></dcat:byteSize>
            </xsl:if>
          </prov:Entity>
        </prov:used>
        <prov:wasAssociatedWith rdf:resource="/#osmwrap"/>
        <dc:date rdf:datatype="http://www.w3.org/2001/XMLSchema#dateTime"><xsl:value-of select="current-dateTime()"/></dc:date>
      </prov:Activity>

      <xsl:apply-templates/>
    </rdf:RDF>
  </xsl:template>

  <xsl:template match="changeset">
    <prov:Activity>
      <xsl:attribute name="rdf:about">/changeset/<xsl:value-of select="@id"/></xsl:attribute>

      <!-- Changeset metadata -->
      <xsl:if test="@created_at">
        <prov:startedAtTime rdf:datatype="http://www.w3.org/2001/XMLSchema#dateTime">
          <xsl:value-of select="@created_at"/>
        </prov:startedAtTime>
      </xsl:if>

      <xsl:if test="@closed_at">
        <prov:endedAtTime rdf:datatype="http://www.w3.org/2001/XMLSchema#dateTime">
          <xsl:value-of select="@closed_at"/>
        </prov:endedAtTime>
      </xsl:if>

      <xsl:if test="@user">
        <prov:wasAssociatedWith>
          <prov:Agent>
            <foaf:accountName><xsl:value-of select="@user"/></foaf:accountName>
            <foaf:accountServiceHomepage rdf:resource="https://www.openstreetmap.org"/>
            <foaf:homepage><xsl:attribute name="rdf:resource">https://www.openstreetmap.org/user/<xsl:value-of select="encode-for-uri(@user)"/></xsl:attribute></foaf:homepage>
          </prov:Agent>
        </prov:wasAssociatedWith>
      </xsl:if>

      <xsl:if test="@uid">
        <uid><xsl:value-of select="@uid"/></uid>
      </xsl:if>

      <!-- Change counts -->
      <xsl:if test="@changes_count">
        <changesCount><xsl:value-of select="@changes_count"/></changesCount>
      </xsl:if>

      <!-- Geographic bounds -->
      <xsl:if test="@min_lat and @min_lon and @max_lat and @max_lon">
        <geo:bbox>
          <xsl:value-of select="@min_lat"/>,<xsl:value-of select="@min_lon"/>,<xsl:value-of select="@max_lat"/>,<xsl:value-of select="@max_lon"/>
        </geo:bbox>
      </xsl:if>

      <!-- Process tags -->
      <xsl:apply-templates/>

      <!-- Links -->
      <foaf:page rdf:resource="https://www.openstreetmap.org/changeset/{@id}"/>
      <prov:hadPrimarySource rdf:resource="https://api.openstreetmap.org/api/0.6/changeset/{@id}"/>

    </prov:Activity>


  </xsl:template>

  <!-- Handle changeset tags -->
  <xsl:template match="tag[@k='comment']">
    <rdfs:comment><xsl:value-of select="@v"/></rdfs:comment>
    <dc:description><xsl:value-of select="@v"/></dc:description>
  </xsl:template>

  <xsl:template match="tag[@k='created_by']">
    <prov:wasAssociatedWith>
      <prov:SoftwareAgent>
        <foaf:name><xsl:value-of select="@v"/></foaf:name>
        <rdfs:label>Editor: <xsl:value-of select="@v"/></rdfs:label>
      </prov:SoftwareAgent>
    </prov:wasAssociatedWith>
  </xsl:template>

  <xsl:template match="tag[@k='source']">
    <dc:source><xsl:value-of select="@v"/></dc:source>
    <prov:hadPrimarySource><xsl:value-of select="@v"/></prov:hadPrimarySource>
  </xsl:template>

  <xsl:template match="tag[@k='imagery_used']">
    <usedImagery><xsl:value-of select="@v"/></usedImagery>
    <prov:used><xsl:value-of select="@v"/></prov:used>
  </xsl:template>

  <!-- Generic tag handler for other changeset tags -->
  <xsl:template match="tag">
    <xsl:variable name="tag" select="@k"/>

    <!-- Skip tags we've already handled specifically -->
    <xsl:if test="not($tag = 'comment' or $tag = 'created_by' or $tag = 'source' or $tag = 'imagery_used')">
      <xsl:if test="not(contains($tag, ':'))">
        <xsl:element name="{$tag}">
          <xsl:value-of select="@v"/>
        </xsl:element>
      </xsl:if>
    </xsl:if>
  </xsl:template>

</xsl:stylesheet>