var FETCH_TIMEOUT = 130000; // slightly above the server's 120s upstream timeout

window.osmMaps = {};

function escHtml(s) {
    return String(s)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

function _propsToHtml(props) {
    var html = '<dl>';
    for (var k in props) {
        if ((k === 'osm_id' || k === 'osm_type') && props.osm_id && props.osm_type) continue;
        html += '<dt>' + escHtml(k) + '</dt><dd>' + escHtml(String(props[k])) + '</dd>';
    }
    html += '</dl>';
    return html;
}


function initOsmMap(id, lat, lon, zoom) {
    var m = L.map(id).setView([lat, lon], zoom);
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', { attribution: '' }).addTo(m);
    var fg = L.featureGroup().addTo(m);
    var suffix = id.replace('map-', '');
    var panelEl = document.getElementById('feature-panel-' + suffix);
    window.osmMaps[id] = { map: m, featureLayer: fg, fetchEpoch: 0, bboxLayer: null,
                           featurePanel: panelEl, highlightedLayer: null,
                           mapId: id, loadedFeatures: [], loadedUrl: null, currentIdx: -1 };

    m.on('click', function() {
        var st = window.osmMaps[id];
        if (st.highlightedLayer) {
            if (st.highlightedLayer._origStyle && st.highlightedLayer.setStyle)
                st.highlightedLayer.setStyle(st.highlightedLayer._origStyle);
            st.highlightedLayer = null;
        }
        st.currentIdx = -1;
        _renderDefaultPanel(st);
    });

    function updateBbox() {
        var b = m.getBounds();
        var inputEl = document.getElementById('bbox-input-' + suffix);
        if (inputEl) inputEl.value = b.getWest().toFixed(6) + ',' + b.getSouth().toFixed(6) + ','
                                   + b.getEast().toFixed(6) + ',' + b.getNorth().toFixed(6);
        var zoomEl = document.getElementById('bbox-zoom-' + suffix);
        if (zoomEl) zoomEl.textContent = 'zoom: ' + m.getZoom();
    }
    m.on('moveend', updateBbox);
    m.on('zoomend', updateBbox);
    updateBbox();
}

function getBboxString(mapId) {
    var state = window.osmMaps[mapId];
    if (!state) return '';
    var b = state.map.getBounds();
    return b.getWest().toFixed(6) + ',' + b.getSouth().toFixed(6) + ','
         + b.getEast().toFixed(6) + ',' + b.getNorth().toFixed(6);
}


function setOsmMapLocation(mapId, bboxStr) {
    var parts = bboxStr.split(',').map(Number);
    if (parts.length !== 4 || parts.some(isNaN)) return;
    var state = window.osmMaps[mapId];
    if (!state) return;
    state.map.fitBounds([[parts[1], parts[0]], [parts[3], parts[2]]]);
}

function setOsmMapZoom(mapId, zoom) {
    var state = window.osmMaps[mapId];
    if (!state) return;
    state.map.setZoom(zoom);
}

function _parseBbox(url) {
    var m = url.match(/[?&]bbox=([^&]+)/);
    if (!m) return null;
    var parts = decodeURIComponent(m[1]).split(',');
    if (parts.length !== 4) return null;
    var n = parts.map(Number);
    if (n.some(isNaN)) return null;
    return n; // [W, S, E, N]
}

function setStatus(el, text, spinning) {
    el.textContent = text;
    el.classList.toggle('spinner', !!spinning);
}

function _attributionFor(url) {
    if (/\/search/.test(url)) return 'Nominatim \u00b7 \u00a9 OpenStreetMap contributors';
    return '\u00a9 OpenStreetMap contributors';
}

function _updateSource(mapId, attribution) {
    var suffix = mapId.replace('map-', '');
    var el = document.getElementById('source-' + suffix);
    if (!el) return;
    if (attribution) {
        el.textContent = 'Source: ' + attribution;
        el.style.display = '';
    } else {
        el.style.display = 'none';
    }
}

function _rdfUrl(url) {
    var q = url.indexOf('?');
    if (q === -1) return url + '.rdf';
    return url.slice(0, q) + '.rdf' + url.slice(q);
}

function _ttlUrl(url) {
    var q = url.indexOf('?');
    if (q === -1) return url + '.ttl';
    return url.slice(0, q) + '.ttl' + url.slice(q);
}

function _formatLinksHtml(url, sourceUrl) {
    var rdf = _rdfUrl(url);
    var html = '';
    if (/^\/(map|node\/|way\/|relation\/)/.test(url)) {
        html += '<a href="https://api.openstreetmap.org/api/0.6' + escHtml(url)
              + '" target="_blank">OSM XML</a> \u00b7 ';
    } else if (sourceUrl) {
        var label = /nominatim/.test(sourceUrl) ? 'Source XML' : 'OSM XML';
        html += '<a href="' + escHtml(sourceUrl) + '" target="_blank">'
              + label + '</a> \u00b7 ';
    }
    html += '<a href="' + escHtml(rdf) + '">RDF/XML</a>'
          + ' \u00b7 <a href="' + escHtml(_ttlUrl(url)) + '">Turtle</a>'
          + ' \u00b7 <a href="' + escHtml(url) + '">JSON</a>'
          + ' \u00b7 <a href="/sparql#from=' + encodeURIComponent(rdf) + '">SPARQL</a>';
    return html;
}

function _wrapGeometry(obj) {
    if (obj.type && obj.type !== 'Feature' && obj.type !== 'FeatureCollection') {
        return { type: 'Feature', geometry: obj, properties: {} };
    }
    return obj;
}

function _renderSummaryPanel(state) {
    if (!state.featurePanel) return;
    state.featurePanel.innerHTML = '';
}

function _renderFeaturePanel(state, idx) {
    if (!state.featurePanel) return;
    var n = state.loadedFeatures.length;
    var feature = state.loadedFeatures[idx];
    if (!feature) return;
    var html = '';
    if (n > 1) {
        var mapId = escHtml(state.mapId);
        html += '<p>'
              + '<button type="button"' + (idx <= 0 ? ' disabled' : '')
              + ' onclick="_osmNavFeature(\'' + mapId + '\',' + (idx - 1) + ')">\u2039</button>'
              + ' ' + (idx + 1) + ' of ' + n + ' '
              + '<button type="button"' + (idx >= n - 1 ? ' disabled' : '')
              + ' onclick="_osmNavFeature(\'' + mapId + '\',' + (idx + 1) + ')">\u203a</button>'
              + '</p>';
    }
    var props = feature.properties || {};
    var formatBar = '';
    if (props.osm_id && props.osm_type) {
        var t = escHtml(String(props.osm_type));
        var i = escHtml(String(props.osm_id));
        var base = '/' + t + '/' + i;
        html += '<p><a href="' + base + '">' + base + '</a></p>';
        formatBar = '<p>'
              + '<a href="https://api.openstreetmap.org/api/0.6/' + t + '/' + i
              + '" target="_blank">OSM XML</a>'
              + ' \u00b7 <a href="' + base + '.rdf">RDF/XML</a>'
              + ' \u00b7 <a href="' + base + '.ttl">Turtle</a>'
              + ' \u00b7 <a href="' + base + '.json">JSON</a>'
              + ' \u00b7 <a href="/sparql#from=' + base + '.rdf">SPARQL</a>'
              + '</p>';
    }
    html += Object.keys(props).length > 0 ? _propsToHtml(props) : '<em>no properties</em>';
    html += formatBar;
    state.featurePanel.innerHTML = html;
    state.currentIdx = idx;
}

function _renderDefaultPanel(state) {
    if (!state.featurePanel) return;
    var n = state.loadedFeatures.length;
    if (n === 0) { state.featurePanel.innerHTML = ''; }
    else if (n === 1) { _renderFeaturePanel(state, 0); }
    else { _renderSummaryPanel(state); }
}

function _osmNavFeature(mapId, idx) {
    var state = window.osmMaps[mapId];
    if (!state || idx < 0 || idx >= state.loadedFeatures.length) return;
    if (state.highlightedLayer) {
        if (state.highlightedLayer._origStyle && state.highlightedLayer.setStyle)
            state.highlightedLayer.setStyle(state.highlightedLayer._origStyle);
        state.highlightedLayer = null;
    }
    state.featureLayer.eachLayer(function(l) {
        if (l._featureIdx === idx) {
            state.highlightedLayer = l;
            if (l.setStyle) l.setStyle({ color: '#ff0', weight: 4, fillColor: '#ff0', fillOpacity: 0.4 });
        }
    });
    _renderFeaturePanel(state, idx);
}

function _renderFeatures(state, geojson) {
    var featureIdx = 0;
    L.geoJSON(geojson, {
        style: function(feature) {
            var t = (feature.geometry && feature.geometry.type) || '';
            if (t.indexOf('Polygon') !== -1) return { color: '#3388ff', weight: 2, fillColor: '#3388ff', fillOpacity: 0.2 };
            if (t.indexOf('Line') !== -1)    return { color: '#ff3333', weight: 2 };
            return { color: '#33cc33', weight: 2 };
        },
        pointToLayer: function(feature, latlng) {
            return L.circleMarker(latlng, { radius: 5, color: '#33cc33', weight: 2, fillColor: '#33cc33', fillOpacity: 0.5 });
        },
        onEachFeature: function(feature, layer) {
            var idx = featureIdx++;
            layer._featureIdx = idx;
            var t = (feature.geometry && feature.geometry.type) || '';
            if (t.indexOf('Polygon') !== -1) {
                layer._origStyle = { color: '#3388ff', weight: 2, fillColor: '#3388ff', fillOpacity: 0.2 };
            } else if (t.indexOf('Line') !== -1) {
                layer._origStyle = { color: '#ff3333', weight: 2 };
            } else {
                layer._origStyle = { color: '#33cc33', weight: 2, fillColor: '#33cc33', fillOpacity: 0.5 };
            }
            layer.on('click', function(e) {
                L.DomEvent.stopPropagation(e);
                var st = state;
                if (st.highlightedLayer && st.highlightedLayer !== this) {
                    if (st.highlightedLayer._origStyle && st.highlightedLayer.setStyle)
                        st.highlightedLayer.setStyle(st.highlightedLayer._origStyle);
                }
                st.highlightedLayer = this;
                if (this.setStyle) this.setStyle({ color: '#ff0', weight: 4, fillColor: '#ff0', fillOpacity: 0.4 });
                _renderFeaturePanel(st, this._featureIdx);
            });
        }
    }).eachLayer(function(l) { state.featureLayer.addLayer(l); });
}

function loadGeoJsonUrl(mapId, url, statusEl) {
    var state = window.osmMaps[mapId];
    if (!state) return;

    state.fetchEpoch += 1;
    var epoch = state.fetchEpoch;

    state.featureLayer.clearLayers();
    state.highlightedLayer = null;
    state.loadedFeatures = [];
    state.loadedUrl = null;
    state.currentIdx = -1;
    if (state.featurePanel) state.featurePanel.innerHTML = '';
    if (state.bboxLayer) { state.map.removeLayer(state.bboxLayer); state.bboxLayer = null; }
    _updateSource(mapId, null);
    var bbox = _parseBbox(url);
    if (bbox) {
        state.bboxLayer = L.rectangle([[bbox[1], bbox[0]], [bbox[3], bbox[2]]], {
            color: '#888', weight: 3, fillOpacity: 0, dashArray: '6 4', interactive: false
        }).addTo(state.map);
    }
    if (statusEl) setStatus(statusEl, 'Loading\u2026');

    var suffix = mapId.replace('map-', '');
    var linkEl = document.getElementById('geojson-link-' + suffix);
    if (linkEl) {
        linkEl.innerHTML = '<span class="spinner"></span><a href="' + escHtml(url) + '">' + escHtml(url) + '</a>';
        linkEl.style.display = '';
    }

    var controller = new AbortController();
    var timer = setTimeout(function() { controller.abort(); }, FETCH_TIMEOUT);
    var sourceUrl = null;

    fetch(url, { signal: controller.signal, headers: { 'Accept': 'application/geo+json' } })
        .then(function(r) {
            sourceUrl = r.headers.get('X-Upstream-Source');
            clearTimeout(timer);
            if (!r.ok) throw new Error('HTTP ' + r.status);
            return r.json();
        })
        .then(function(data) {
            if (state.fetchEpoch !== epoch) return;
            var geojson = _wrapGeometry(data);

            if (geojson.type !== 'FeatureCollection') {
                geojson = { type: 'FeatureCollection', features: [geojson] };
            }
            state.loadedFeatures = geojson.features;
            state.loadedUrl = url;
            state.currentIdx = -1;
            _renderFeatures(state, geojson);
            _renderDefaultPanel(state);
            var count = state.featureLayer.getLayers().length;
            if (statusEl) setStatus(statusEl, count + (count === 1 ? ' feature' : ' features'));
            _updateSource(mapId, _attributionFor(url));
            if (count > 0) {
                try { state.map.fitBounds(state.featureLayer.getBounds(), { maxZoom: 16 }); } catch (e) {}
            }
            if (linkEl) {
                linkEl.innerHTML = _formatLinksHtml(url, sourceUrl);
                linkEl.style.display = '';
            }

        })
        .catch(function(err) {
            clearTimeout(timer);
            if (state.fetchEpoch !== epoch) return;
            var msg = err.name === 'AbortError' ? 'Client timeout (' + (FETCH_TIMEOUT / 1000) + 's)' : err.message;
            if (statusEl) setStatus(statusEl, 'Error: ' + msg);
            if (linkEl) { linkEl.innerHTML = _formatLinksHtml(url, sourceUrl); }
        });
}
