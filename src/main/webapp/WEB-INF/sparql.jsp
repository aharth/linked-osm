<%@ page contentType="text/html; charset=UTF-8" %>
<%@ page import="com.ontologycentral.osmwrap.webapp.Listener,
                 jakarta.json.JsonArray, jakarta.json.JsonObject" %>
<%
JsonArray _examples = (JsonArray) application.getAttribute(Listener.SPARQL_EXAMPLES);
String _defaultQuery = (_examples != null && _examples.size() > 0)
    ? _examples.getJsonObject(0).getString("query", "")
    : "";
%>
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <title>SPARQL &mdash; Linked OSM</title>
  <link rel="stylesheet" href="map.css">
</head>
<body>
<p><a href="/">Home</a></p>
<h1>SPARQL</h1>
<form method="get" action="sparql">
<dl>
  <dt>Query</dt>
<% if (_examples != null && _examples.size() > 0) {
     java.util.LinkedHashSet<String> _sources = new java.util.LinkedHashSet<>();
     for (int _i = 0; _i < _examples.size(); _i++)
         _sources.add(_examples.getJsonObject(_i).getString("source", "Examples"));
     for (String _src : _sources) { %>
  <dd>
  <label><%= _src %><br>
  <select onchange="loadExample(this)">
    <option value="">&mdash; choose example &mdash;</option>
<%    for (int _i = 0; _i < _examples.size(); _i++) {
          JsonObject _e = _examples.getJsonObject(_i);
          if (!_src.equals(_e.getString("source", "Examples"))) continue;
          String _eName = _e.getString("name", "Example " + (_i + 1)); %>
    <option value="<%= _i %>"><%= _eName %></option>
<%    } %>
  </select>
  </label>
  </dd>
<%   }
   } %>
  <dd><textarea id="query" name="query" style="width:80%;height:25vh;box-sizing:border-box"><%= _defaultQuery.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") %></textarea></dd>
  <dd><input type="submit">
      <small>&mdash; <a id="get-url" href="#">SPARQL query</a> link (copy for <code>curl</code>; the <code>#</code> in <code>FROM</code> IRIs becomes <code>%23</code>)</small></dd>
</dl>
</form>
<h2>FROM &mdash; selecting the data</h2>
<p>Every query needs at least one <code>FROM</code> (or <code>FROM NAMED</code>);
the endpoint fetches those documents and queries over them. Only THIS wrapper's
documents are permitted &mdash; an external graph IRI is rejected &mdash; and
relative IRIs resolve against the wrapper root, so the same query works on any
deployment:</p>
<ul>
  <li><code>FROM &lt;/overpass/features.rdf?bbox=W,S,E,N&gt;</code>
      &mdash; every tagged element in a bounding box, with geometry
      (<code>locn:geometry</code> as a <code>geo:wktLiteral</code>: POINT for
      nodes, LINESTRING or POLYGON for ways). Narrow it with
      <code>&amp;filter=highway</code> / <code>&amp;type=way</code></li>
  <li><code>FROM &lt;/overpass/poi.rdf?bbox=W,S,E,N&amp;filter=amenity&gt;</code>
      &mdash; points of interest only (<code>&amp;limit=</code> caps the count)</li>
  <li><code>FROM &lt;/overpass/around.rdf?&hellip;&gt;</code>
      &mdash; a radius around a point</li>
  <li><code>FROM &lt;/overpass/node/{id}.rdf&gt;</code>,
      <code>&hellip;/overpass/way/{id}.rdf</code>,
      <code>&hellip;/overpass/relation/{id}.rdf</code>
      &mdash; one element's document</li>
  <li><code>FROM &lt;/nominatim/search.rdf?&hellip;&gt;</code>
      &mdash; a geocoding answer</li>
  <li><code>FROM &lt;/changeset/{id}&gt;</code> &mdash; one changeset</li>
</ul>
<p>Several <code>FROM</code> clauses merge into one graph. A <code>#</code>
inside a FROM IRI must be written <code>%23</code>.</p>
<p>Feature bodies carry <code>dc:subject</code> links to the <code>/tag/{k}={v}</code>
SKOS concepts and <code>locn:geometry</code>; the document node itself carries the
<code>prov:</code> and licensing statements.</p>
<h2>GeoSPARQL functions</h2>
<p>The endpoint registers the GeoSPARQL 1.1 function suite
(<a href="https://docs.ogc.org/is/22-047r1/22-047r1.html">OGC 22-047r1</a>).
Functions operate on <code>geo:wktLiteral</code> typed literals &mdash; the form
<code>locn:geometry</code> is emitted in. Common functions:</p>
<ul>
  <li><code>geof:metricArea(?g)</code> &mdash; geodesic area in m&sup2;</li>
  <li><code>geof:metricLength(?g)</code> &mdash; geodesic length in m</li>
  <li><code>geof:metricDistance(?a, ?b)</code> &mdash; geodesic distance in m</li>
  <li><code>geof:centroid(?g)</code> &mdash; returns a <code>geo:wktLiteral</code> POINT</li>
</ul>
<p>Topological relations (DE-9IM / Simple Features) return a boolean for a pair of
geometries:</p>
<ul>
  <li><code>geof:sfEquals(?a, ?b)</code> &mdash; topologically equal</li>
  <li><code>geof:sfTouches(?a, ?b)</code> &mdash; boundaries meet, interiors do not</li>
  <li><code>geof:sfCrosses(?a, ?b)</code> &mdash; interiors cross</li>
  <li><code>geof:sfIntersects(?a, ?b)</code> &mdash; not disjoint</li>
  <li><code>geof:sfContains(?a, ?b)</code> &mdash; <code>?a</code> contains <code>?b</code></li>
</ul>
<p>Use <code>PREFIX geof: &lt;http://www.opengis.net/def/function/geosparql/&gt;</code>.</p>
<p>A non-standard helper returns a geometry's topological dimension as an integer
(<code>0</code>&nbsp;point, <code>1</code>&nbsp;curve, <code>2</code>&nbsp;surface):</p>
<ul>
  <li><code>fn:dimension(?g)</code> &mdash; mirrors JTS <code>getDimension()</code>;
  separates nodes from open ways from closed ways with
  <code>fn:dimension(?g) = 0 / 1 / 2</code></li>
</ul>
<p>Use <code>PREFIX fn: &lt;https://osmwrap.ontologycentral.com/vocab/fn#&gt;</code>
&mdash; absolute by necessity: the functions are registered under that exact IRI.</p>
<h2>Result formats</h2>
<p>By URL suffix &mdash; <code>/sparql.json</code>, <code>.xml</code>,
<code>.tsv</code> for SELECT/ASK; <code>.ttl</code>, <code>.rdf</code> for
CONSTRUCT/DESCRIBE &mdash; or by <code>Accept</code> header on <code>/sparql</code>.</p>
<% if (_examples != null && _examples.size() > 0) { %>
<script>
var SPARQL_EXAMPLES = [
<% for (int _i = 0; _i < _examples.size(); _i++) {
       JsonObject _ex = _examples.getJsonObject(_i);
       String _eName  = _ex.getString("name",  "").replace("\\", "\\\\").replace("\"", "\\\"");
       String _eQuery = _ex.getString("query", "").replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"); %>
    {name: "<%= _eName %>", query: "<%= _eQuery %>"}<%= _i < _examples.size() - 1 ? "," : "" %>
<% } %>
];
// Loading an example keeps whatever FROM the box currently holds, so a bbox the
// user arrived with (or pasted) survives switching examples. Behaviour carried
// over from the static sparql.html this page replaced.
function loadExample(sel) {
  var i = sel.value;
  if (i === '') return;
  var ta = document.getElementById('query');
  var m = ta.value.match(/FROM\s+<([^>]*)>/i);
  var q = SPARQL_EXAMPLES[i].query;
  if (m) q = q.replace(/FROM\s+<[^>]*>/, 'FROM <' + m[1] + '>');
  ta.value = q;
  updateGetUrl();
}
</script>
<% } %>
<script>
function updateGetUrl() {
  var q = document.getElementById('query').value;
  document.getElementById('get-url').href = location.origin + '/sparql?query=' + encodeURIComponent(q);
}
// Two deep links: #query=<whole query>, and #from=<graph IRI> which swaps only
// the FROM of the prefilled example (how the map view hands off a viewport).
var _ta = document.getElementById('query');
var _h = location.hash.slice(1), _m = _h.match(/^query=(.+)$/);
if (_m) {
  _ta.value = decodeURIComponent(_m[1]);
} else {
  var _fm = location.hash.match(/[#&]from=([^&]+)/);
  if (_fm) _ta.value = _ta.value.replace(/FROM\s+<[^>]+>/, 'FROM <' + decodeURIComponent(_fm[1]) + '>');
}
_ta.addEventListener('input', updateGetUrl);
updateGetUrl();
</script>
</body>
</html>
