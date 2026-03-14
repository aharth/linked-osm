<xsl:stylesheet
   xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
   xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
   xmlns:foaf="http://xmlns.com/foaf/0.1/"
   xmlns:owl="http://www.w3.org/2002/07/owl#"
   xmlns:dc="http://purl.org/dc/elements/1.1/"
   xmlns:dcterms="http://purl.org/dc/terms/"
   xmlns:sioc="http://rdfs.org/sioc/ns#"
   xmlns:geo="http://www.w3.org/2003/01/geo/wgs84_pos#"
   xmlns:spatial="http://geovocab.org/spatial#"
   xmlns:geom="http://geovocab.org/geometry#"
   xmlns:gml="http://www.opengis.net/gml/3.2"
   xmlns:locn="http://www.w3.org/ns/locn#"
   xmlns:prov="http://www.w3.org/ns/prov#"
   xmlns:dcat="http://www.w3.org/ns/dcat#"
   xmlns:osm="https://osmwrap.ontologycentral.com/vocab#"
   xmlns:osmt="https://osmwrap.ontologycentral.com/tag/"
   xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
   version="2.0">

  <xsl:output method="xml"/>

  <xsl:strip-space elements="*"/>

  <xsl:param name="upstream-bytes" select="-1"/>
  <xsl:param name="source-prefix" select="'/osm'"/>

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
          <prov:Entity>
            <xsl:attribute name="rdf:about"><xsl:value-of select="$source-prefix"/>/node/<xsl:value-of select="node/@id"/></xsl:attribute>
            <xsl:if test="$upstream-bytes >= 0">
              <dcat:byteSize rdf:datatype="http://www.w3.org/2001/XMLSchema#decimal"><xsl:value-of select="$upstream-bytes"/></dcat:byteSize>
            </xsl:if>
          </prov:Entity>
        </prov:used>
        <prov:wasAssociatedWith rdf:resource="/#osmwrap"/>
        <dc:date rdf:datatype="http://www.w3.org/2001/XMLSchema#dateTime"><xsl:value-of select="current-dateTime()"/></dc:date>
      </prov:Activity>

      <!-- PROV: Agent (osmwrap service) -->
      <prov:SoftwareAgent rdf:about="/#osmwrap">
        <rdfs:label>Linked OSM (osmwrap)</rdfs:label>
        <foaf:homepage rdf:resource="http://osmwrap.ontologycentral.com/"/>
        <dc:description>Service for converting OpenStreetMap data to RDF</dc:description>
      </prov:SoftwareAgent>

      <xsl:apply-templates/>
    </rdf:RDF>
  </xsl:template>

  <xsl:template match="node">
      <spatial:Feature>
	<xsl:attribute name="rdf:about"><xsl:value-of select="$source-prefix"/>/node/<xsl:value-of select="@id"/>#id</xsl:attribute>
	<rdf:type rdf:resource="https://osmwrap.ontologycentral.com/vocab#Node"/>
	<dcterms:identifier><xsl:value-of select="@id"/></dcterms:identifier>
	<geo:lat><xsl:value-of select="@lat"/></geo:lat>
	<geo:long><xsl:value-of select="@lon"/></geo:long>

	<!-- Links to document representations -->
	<foaf:page rdf:resource="https://www.openstreetmap.org/node/{@id}"/>
	<foaf:page><xsl:attribute name="rdf:resource"><xsl:value-of select="$source-prefix"/>/node/<xsl:value-of select="@id"/>.rdf</xsl:attribute></foaf:page>
	<foaf:page><xsl:attribute name="rdf:resource"><xsl:value-of select="$source-prefix"/>/node/<xsl:value-of select="@id"/>.json</xsl:attribute></foaf:page>

	<!-- Geometry representation -->
	<geom:geometry><xsl:attribute name="rdf:resource"><xsl:value-of select="$source-prefix"/>/node/<xsl:value-of select="@id"/>#geo</xsl:attribute></geom:geometry>

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
	      <foaf:homepage><xsl:attribute name="rdf:resource">https://www.openstreetmap.org/user/<xsl:value-of select="encode-for-uri(@user)"/></xsl:attribute></foaf:homepage>
	    </prov:Agent>
	  </prov:wasAttributedTo>
	</xsl:if>
	<xsl:if test="@version">
	  <prov:hadPrimarySource rdf:resource="https://api.openstreetmap.org/api/0.6/node/{@id}/{@version}"/>
	  <prov:value><xsl:value-of select="@version"/></prov:value>
	</xsl:if>

	<xsl:apply-templates/>
      </spatial:Feature>

      <!-- Geometry resource -->
      <geom:Geometry>
	<xsl:attribute name="rdf:about"><xsl:value-of select="$source-prefix"/>/node/<xsl:value-of select="@id"/>#geo</xsl:attribute>
	<geo:lat><xsl:value-of select="@lat"/></geo:lat>
	<geo:long><xsl:value-of select="@lon"/></geo:long>
	<locn:geometry rdf:parseType="Literal">
	  <gml:Point srsName="http://www.opengis.net/def/crs/OGC/1.3/CRS84">
	    <gml:pos><xsl:value-of select="@lon"/><xsl:text> </xsl:text><xsl:value-of select="@lat"/></gml:pos>
	  </gml:Point>
	</locn:geometry>
      </geom:Geometry>

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
	      <foaf:homepage><xsl:attribute name="rdf:resource">https://www.openstreetmap.org/user/<xsl:value-of select="encode-for-uri(@user)"/></xsl:attribute></foaf:homepage>
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

  <xsl:template match="tag[@k = 'wikidata']">
    <owl:sameAs>
      <xsl:attribute name="rdf:resource">http://www.wikidata.org/entity/<xsl:value-of select="@v"/></xsl:attribute>
    </owl:sameAs>
  </xsl:template>

  <xsl:template match="tag">
    <xsl:variable name="tag" select="@k"/>
    <xsl:choose>
      <xsl:when test="not(contains($tag, ':'))">
        <xsl:element name="osmt:{$tag}" namespace="https://osmwrap.ontologycentral.com/tag/">
          <xsl:value-of select="@v"/>
        </xsl:element>
      </xsl:when>
      <xsl:otherwise>
        <osm:tag osm:key="/tag/{encode-for-uri(@k)}" osm:value="{@v}"/>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
</xsl:stylesheet>
