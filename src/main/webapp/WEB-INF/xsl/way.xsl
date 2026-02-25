<xsl:stylesheet
   xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
   xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
   xmlns:foaf="http://xmlns.com/foaf/0.1/"
   xmlns:owl="http://www.w3.org/2002/07/owl#"
   xmlns:dc="http://purl.org/dc/elements/1.1/"
   xmlns:sioc="http://rdfs.org/sioc/ns#"
   xmlns:geo="http://www.w3.org/2003/01/geo/wgs84_pos#"
   xmlns:geom="http://geovocab.org/geometry#"
   xmlns:locn="http://www.w3.org/ns/locn#"
   xmlns:spatial="http://geovocab.org/spatial#"
   xmlns:prov="http://www.w3.org/ns/prov#"
   xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
   version="2.0">

  <xsl:output method="xml"/>

  <xsl:strip-space elements="*"/>

  <xsl:key name="nodeById" match="node" use="@id"/>

  <xsl:template match="osm">
    <rdf:RDF>
      <rdf:Description rdf:about="">
	<rdfs:comment>No guarantee of correctness! USE AT YOUR OWN RISK!</rdfs:comment>
	<rdfs:comment><xsl:value-of select="@generator"/></rdfs:comment>
	<rdfs:comment><xsl:value-of select="@copyright"/></rdfs:comment>
	<dc:publisher>OpenStreetMap Contributors (https://www.openstreetmap.org/) via Linked OSM (http://osmwrap.ontologycentral.com/)</dc:publisher>
	<dc:attribution><xsl:value-of select="@attribution"/></dc:attribution>
	<dc:license><xsl:value-of select="@license"/></dc:license>
	<dc:date><xsl:value-of select="way/@timestamp"/></dc:date>
	<rdfs:seeAlso rdf:resource="https://www.openstreetmap.org/copyright"/>
	<rdfs:seeAlso rdf:resource="https://wiki.openstreetmap.org/wiki/Legal_FAQ"/>
	<prov:wasGeneratedBy rdf:resource="#transformation"/>
      </rdf:Description>

      <!-- PROV: Transformation activity -->
      <prov:Activity rdf:about="#transformation">
        <rdfs:label>OSM XML to RDF Way Transformation</rdfs:label>
        <prov:used>
          <xsl:attribute name="rdf:resource">https://api.openstreetmap.org/api/0.6/way/<xsl:value-of select="way/@id"/></xsl:attribute>
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

  <xsl:template match="way">
      <spatial:Feature>
	<xsl:attribute name="rdf:about">/way/<xsl:value-of select="@id"/>#id</xsl:attribute>

	<!-- Links to document representations -->
	<foaf:page rdf:resource="https://www.openstreetmap.org/way/{@id}"/>
	<foaf:page rdf:resource="/way/{@id}.rdf"/>
	<foaf:page rdf:resource="/way/{@id}.json"/>

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
	  <prov:hadPrimarySource rdf:resource="https://api.openstreetmap.org/api/0.6/way/{@id}/{@version}"/>
	  <prov:value><xsl:value-of select="@version"/></prov:value>
	</xsl:if>

	<xsl:apply-templates/>

	<geom:geometry rdf:resource="/way/{@id}#geo"/>
      </spatial:Feature>

      <!-- Geometry resource -->
      <geom:Geometry>
	<xsl:attribute name="rdf:about">/way/<xsl:value-of select="@id"/>#geo</xsl:attribute>
	<foaf:page rdf:resource="/geo/osm/way/{@id}"/>
	<foaf:page rdf:resource="/geo/overpass/way/{@id}"/>

	<!-- Centroid: mean of node coordinates -->
	<xsl:variable name="nodes" select="nd/key('nodeById', @ref)[normalize-space(@lat)]"/>
	<xsl:if test="count($nodes) > 0">
	  <geo:lat><xsl:value-of select="sum($nodes/@lat) div count($nodes)"/></geo:lat>
	  <geo:long><xsl:value-of select="sum($nodes/@lon) div count($nodes)"/></geo:long>
	</xsl:if>

	<!-- WKT geometry (LineString or Polygon if closed) -->
	<xsl:variable name="firstRef" select="nd[1]/@ref"/>
	<xsl:variable name="lastRef"  select="nd[last()]/@ref"/>
	<xsl:variable name="closed"
	  select="count($nodes) >= 4 and $firstRef = $lastRef"/>
	<xsl:variable name="wktCoords">
	  <xsl:for-each select="nd">
	    <xsl:variable name="n" select="key('nodeById', @ref)"/>
	    <xsl:if test="$n/@lon and $n/@lat">
	      <xsl:if test="position() > 1">, </xsl:if>
	      <xsl:value-of select="$n/@lon"/>
	      <xsl:text> </xsl:text>
	      <xsl:value-of select="$n/@lat"/>
	    </xsl:if>
	  </xsl:for-each>
	</xsl:variable>
	<xsl:if test="normalize-space($wktCoords) != ''">
	  <locn:geometry rdf:datatype="http://www.opengis.net/ont/geosparql#wktLiteral">
	    <xsl:choose>
	      <xsl:when test="$closed">POLYGON((<xsl:value-of select="$wktCoords"/>))</xsl:when>
	      <xsl:otherwise>LINESTRING(<xsl:value-of select="$wktCoords"/>)</xsl:otherwise>
	    </xsl:choose>
	  </locn:geometry>
	</xsl:if>
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
	  <xsl:attribute name="rdf:resource">http://<xsl:value-of select="substring(@v, 0, 3)"/>.wikipedia.org/wiki/<xsl:value-of select="encode-for-uri(substring(@v, 4))"/></xsl:attribute>
	</xsl:when>
	<xsl:otherwise>
	  <xsl:attribute name="rdf:resource">http://en.wikipedia.org/wiki/<xsl:value-of select="encode-for-uri(@v)"/></xsl:attribute>
	</xsl:otherwise>
      </xsl:choose>
    </foaf:page>

    <owl:sameAs>
      <xsl:choose>
	<xsl:when test="contains(@v, ':')">
	  <xsl:choose>
	    <xsl:when test="substring(@v, 0, 3) = 'en'">
	      <xsl:attribute name="rdf:resource">http://dbpedia.org/resource/<xsl:value-of select="encode-for-uri(substring(@v, 4))"/></xsl:attribute>
	    </xsl:when>
	    <xsl:otherwise>
	      <xsl:attribute name="rdf:resource">http://<xsl:value-of select="substring(@v, 0, 3)"/>.dbpedia.org/resource/<xsl:value-of select="encode-for-uri(substring(@v, 4))"/></xsl:attribute>
	    </xsl:otherwise>
	  </xsl:choose>
	</xsl:when>
	<xsl:otherwise>
	  <xsl:attribute name="rdf:resource">http://dbpedia.org/resource/<xsl:value-of select="encode-for-uri(@v)"/></xsl:attribute>
	</xsl:otherwise>
      </xsl:choose>
    </owl:sameAs>
  </xsl:template>

  <xsl:template match="tag[@k = 'wikidata']">
    <owl:sameAs>
      <xsl:attribute name="rdf:resource">http://www.wikidata.org/entity/<xsl:value-of select="@v"/></xsl:attribute>
    </owl:sameAs>
  </xsl:template>

</xsl:stylesheet>
