# test/

Smoke tests for the deployed osmwrap service.

## smoke-rdf.sh

Validates RDF output (Turtle and RDF/XML) and runs SPARQL queries against the live server.

**Dependencies:** `raptor2-utils`, `rasqal-utils`

```
apt install raptor2-utils rasqal-utils
```

**Usage:**

```
./test/smoke-rdf.sh [BASE]
```

`BASE` defaults to `https://osmwrap.ontologycentral.com`.

**Checks:**
- Turtle parses cleanly (rapper)
- No absolute osmwrap URIs in Turtle output (relative refs only)
- RDF/XML parses cleanly and uses `https://` scheme
- SPARQL queries over local Turtle (type, coordinates, prov attribution, tags, geometry, label)
- SPARQL queries via `/sparql` endpoint using `FROM </osm/...>` clauses
