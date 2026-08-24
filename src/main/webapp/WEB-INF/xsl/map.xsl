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
   xmlns:locn="http://www.w3.org/ns/locn#"
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
	<prov:wasAttributedTo rdf:resource="/index#osmwrap"/>
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

      <!-- Feature bodies for answers fetched with `out geom` (the
           /overpass/features query): tags as dc:subject links to the /tag
           SKOS concepts, geometry as a WKT literal (CRS84 lon/lat).
           Untagged elements stay reference-only; relations get tags but no
           geometry (multipolygon assembly is not the XSLT's job). -->
      <xsl:for-each select="node[tag]">
        <rdf:Description rdf:about="/node/{@id}#id">
          <xsl:call-template name="feature-tags"/>
          <xsl:if test="@lat">
            <locn:geometry rdf:datatype="http://www.opengis.net/ont/geosparql#wktLiteral"><xsl:value-of select="concat('POINT(', @lon, ' ', @lat, ')')"/></locn:geometry>
          </xsl:if>
        </rdf:Description>
      </xsl:for-each>
      <xsl:for-each select="way[tag]">
        <rdf:Description rdf:about="/way/{@id}#id">
          <xsl:call-template name="feature-tags"/>
          <xsl:if test="nd/@lat">
            <xsl:variable name="pts" select="string-join(for $n in nd return concat($n/@lon, ' ', $n/@lat), ', ')"/>
            <locn:geometry rdf:datatype="http://www.opengis.net/ont/geosparql#wktLiteral"><xsl:choose>
              <xsl:when test="nd[1]/@ref = nd[last()]/@ref"><xsl:value-of select="concat('POLYGON((', $pts, '))')"/></xsl:when>
              <xsl:otherwise><xsl:value-of select="concat('LINESTRING(', $pts, ')')"/></xsl:otherwise>
            </xsl:choose></locn:geometry>
          </xsl:if>
        </rdf:Description>
      </xsl:for-each>
      <xsl:for-each select="relation[tag]">
        <rdf:Description rdf:about="/relation/{@id}#id">
          <xsl:call-template name="feature-tags"/>
        </rdf:Description>
      </xsl:for-each>

    </rdf:RDF>
  </xsl:template>

  <xsl:template name="feature-tags">
    <xsl:for-each select="tag">
      <dc:subject rdf:resource="/tag/{encode-for-uri(@k)}={encode-for-uri(@v)}"/>
    </xsl:for-each>
  </xsl:template>

</xsl:stylesheet>
