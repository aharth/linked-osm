var OSM_TILE_LAYERS = {
    'Standard':      { url: 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',
                       attribution: '\u00a9 OpenStreetMap contributors' },
    'CyclOSM':       { url: 'https://{s}.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png',
                       attribution: '\u00a9 OpenStreetMap contributors, \u00a9 CyclOSM' },
    'Humanitarian':  { url: 'https://{s}.tile.openstreetmap.fr/hot/{z}/{x}/{y}.png',
                       attribution: '\u00a9 OpenStreetMap contributors, Humanitarian OpenStreetMap Team' },
    'OpenTopoMap':   { url: 'https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png',
                       attribution: '\u00a9 OpenStreetMap contributors, \u00a9 OpenTopoMap' },
    'CARTO Positron':   { url: 'https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}.png',
                          attribution: '\u00a9 OpenStreetMap contributors, \u00a9 CARTO' },
    'CARTO Dark Matter': { url: 'https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png',
                           attribution: '\u00a9 OpenStreetMap contributors, \u00a9 CARTO' },
    'Transport (\u00d6PNVKarte)': { url: 'https://tile.memomaps.de/tilegen/{z}/{x}/{y}.png',
                                    attribution: 'Map \u00a9 <a href="https://memomaps.de/">memomaps.de</a> CC-BY-SA, map data \u00a9 OpenStreetMap ODbL' }
};

function _updateTileStatus(mapId) {
    var state = window.maps[mapId];
    if (!state) return;
    var suffix = mapId.replace('map-', '');
    var layer = OSM_TILE_LAYERS[state.activeLayerName] || OSM_TILE_LAYERS['Standard'];
    var statusEl = document.getElementById('tile-status-' + suffix);
    if (statusEl) statusEl.textContent = state.activeLayerName;
    var sourceEl = document.getElementById('tile-source-' + suffix);
    if (sourceEl) sourceEl.textContent = 'Source: ' + layer.attribution;
    var tileEl = document.getElementById('tile-link-' + suffix);
    if (tileEl) {
        var c = state.map.getCenter();
        var z = state.map.getZoom();
        var pngUrl = _centerTileUrl(layer.url, c.lat, c.lng, z);
        tileEl.innerHTML = '<a href="' + escHtml(pngUrl) + '" target="_blank">PNG</a>';
    }
}

function switchOsmTileLayer(mapId, layerName) {
    var state = window.maps[mapId];
    if (!state) return;
    if (state.tileLayer) state.map.removeLayer(state.tileLayer);
    var layer = OSM_TILE_LAYERS[layerName] || OSM_TILE_LAYERS['Standard'];
    state.tileLayer = L.tileLayer(layer.url, { attribution: '' }).addTo(state.map);
    state.activeLayerName = layerName;
    _updateTileStatus(mapId);
}

function initOsmMap(id, lat, lon, zoom) {
    var m = L.map(id).setView([lat, lon], zoom);
    m.createPane('bboxPane');
    m.getPane('bboxPane').style.zIndex = 450;
    var tl = L.tileLayer(OSM_TILE_LAYERS['Standard'].url, { attribution: '' }).addTo(m);
    var fg = L.featureGroup().addTo(m);
    var suffix = id.replace('map-', '');
    var panelEl = document.getElementById('feature-panel-' + suffix);
    if (panelEl) {
        panelEl.addEventListener('click', function() {
            var st = window.maps[id];
            if (!st || st.currentIdx < 0) return;
            _navFeature(id, st.currentIdx);
        });
    }
    window.maps[id] = { map: m, tileLayer: tl, featureLayer: fg, fetchEpoch: 0, bboxLayer: null,
                           featurePanel: panelEl, highlightedLayer: null,
                           mapId: id, loadedFeatures: [], loadedUrl: null, currentIdx: -1,
                           activeLayerName: 'Standard' };

    m.on('click', function() {
        var st = window.maps[id];
        if (st.highlightedLayer) {
            if (st.highlightedLayer._origStyle && st.highlightedLayer.setStyle)
                st.highlightedLayer.setStyle(st.highlightedLayer._origStyle);
            st.highlightedLayer = null;
        }
    });

    function updateBbox() {
        var b = m.getBounds();
        var inputEl = document.getElementById('bbox-input-' + suffix);
        if (inputEl) inputEl.value = b.getWest().toFixed(6) + ',' + b.getSouth().toFixed(6) + ','
                                   + b.getEast().toFixed(6) + ',' + b.getNorth().toFixed(6);
        var zoomEl = document.getElementById('bbox-zoom-' + suffix);
        if (zoomEl) {
            var _b2 = m.getBounds();
            var _R = 6371;
            var _lat1 = _b2.getSouth() * Math.PI / 180;
            var _lat2 = _b2.getNorth() * Math.PI / 180;
            var _dLon = Math.abs(_b2.getEast() - _b2.getWest()) * Math.PI / 180;
            var _area = Math.abs(_R * _dLon * Math.cos((_lat1 + _lat2) / 2) * _R * Math.abs(_lat2 - _lat1));
            var _areaStr = _area >= 1
                ? _area.toFixed(_area >= 100 ? 0 : 1) + '\u00a0km\u00b2'
                : (_area * 1e6).toFixed(0) + '\u00a0m\u00b2';
            zoomEl.textContent = 'zoom: ' + m.getZoom() + ' \u00b7 ' + _areaStr;
        }
        var tileEl = document.getElementById('tile-link-' + suffix);
        if (tileEl) {
            var c = m.getCenter();
            var z = m.getZoom();
            var layerName = (window.maps[id] || {}).activeLayerName || 'Standard';
            var layerUrl = (OSM_TILE_LAYERS[layerName] || OSM_TILE_LAYERS['Standard']).url;
            var pngUrl = _centerTileUrl(layerUrl, c.lat, c.lng, z);
            tileEl.innerHTML = '<a href="' + escHtml(pngUrl) + '" target="_blank">PNG</a>';
        }
    }
    m.on('moveend', updateBbox);
    m.on('zoomend', updateBbox);
    updateBbox();
    _updateTileStatus(id);

    // Wire location preset select → bbox input
    var locSel  = document.getElementById('loc-' + suffix);
    var bboxInp = document.getElementById('bbox-input-' + suffix);
    if (locSel && bboxInp) {
        locSel.addEventListener('change', function() {
            if (this.value) bboxInp.value = this.value;
        });
        bboxInp.addEventListener('input', function() { locSel.value = ''; });
    }

    // Wire example dropdown ↔ node/way/relation inputs (bidirectional)
    var geoExSel = document.getElementById('geo-example-select');
    var geoForm  = document.forms['geo-form'];
    if (geoExSel && geoForm) {
        geoExSel.addEventListener('change', function() {
            var match = this.value && this.value.match(/^\/(node|way|relation)\/(\d+)$/);
            if (!match) return;
            ['node', 'way', 'relation'].forEach(function(t) {
                var el = geoForm.elements[t];
                if (el) el.value = (t === match[1]) ? match[2] : '';
            });
        });
        ['node', 'way', 'relation'].forEach(function(t) {
            var el = geoForm.elements[t];
            if (el) el.addEventListener('input', function() { geoExSel.value = ''; });
        });
    }
}

function getBboxString(mapId) {
    var state = window.maps[mapId];
    if (!state) return '';
    var b = state.map.getBounds();
    return b.getWest().toFixed(6) + ',' + b.getSouth().toFixed(6) + ','
         + b.getEast().toFixed(6) + ',' + b.getNorth().toFixed(6);
}


function setMapLocation(mapId, bboxStr) {
    var parts = bboxStr.split(',').map(Number);
    if (parts.length !== 4 || parts.some(isNaN)) return;
    var state = window.maps[mapId];
    if (!state) return;
    state.map.fitBounds([[parts[1], parts[0]], [parts[3], parts[2]]]);
}

function setOsmMapZoom(mapId, zoom) {
    var state = window.maps[mapId];
    if (!state) return;
    state.map.setZoom(zoom);
}

function _centerTileUrl(layerUrl, lat, lon, z) {
    var tx = Math.floor((lon + 180) / 360 * Math.pow(2, z));
    var ty = Math.floor((1 - Math.log(Math.tan(lat * Math.PI / 180)
           + 1 / Math.cos(lat * Math.PI / 180)) / Math.PI) / 2 * Math.pow(2, z));
    return layerUrl.replace('{s}', 'a').replace('{z}', z).replace('{x}', tx).replace('{y}', ty);
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

function _makeBboxLayer(map, west, south, east, north) {
    var rect = L.rectangle([[south, west], [north, east]], {
        color: '#888', weight: 3, fill: false, dashArray: '6 4', pane: 'bboxPane'
    }).addTo(map);
    rect.on('mouseover', function() {
        this.setStyle({ color: '#444', weight: 4, dashArray: '6 4' });
        this.getElement() && (this.getElement().style.cursor = 'pointer');
    });
    rect.on('mouseout', function() {
        this.setStyle({ color: '#888', weight: 3, dashArray: '6 4' });
    });
    rect.on('click', function(e) {
        L.DomEvent.stopPropagation(e);
        map.fitBounds(this.getBounds());
    });
    return rect;
}

function _attributionFor(url) {
    if (/\/search/.test(url)) return 'Nominatim \u00b7 \u00a9 OpenStreetMap contributors';
    return '\u00a9 OpenStreetMap contributors';
}

function _updateSource(mapId, attribution) {
    var suffix = mapId.replace('map-', '');
    var el = document.getElementById('source-' + suffix);
    if (!el) return;
    el.textContent = attribution ? 'Source: ' + attribution : '';
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
    var qi = url.indexOf('?');
    var jsonUrl = qi >= 0 ? url.slice(0, qi) + '.json' + url.slice(qi) : url + '.json';
    var html = '<a href="' + escHtml(jsonUrl) + '">JSON</a>';
    if (/^\/(map|node\/|way\/|relation\/)/.test(url)) {
        html += ' \u00b7 <a href="https://api.openstreetmap.org/api/0.6' + escHtml(url)
              + '" target="_blank">OSM XML</a>';
    } else if (sourceUrl) {
        var label = /nominatim/.test(sourceUrl) ? 'Source XML' : 'OSM XML';
        html += ' \u00b7 <a href="' + escHtml(sourceUrl) + '" target="_blank">' + label + '</a>';
    }
    html += ' \u00b7 <a href="' + escHtml(rdf) + '">RDF/XML</a>'
          + ' \u00b7 <a href="' + escHtml(_ttlUrl(url)) + '">Turtle</a>'
          + ' \u00b7 <a href="/sparql#from=' + encodeURIComponent(rdf) + '">SPARQL</a>';
    return html;
}

function _wrapGeometry(obj) {
    if (obj.type && obj.type !== 'Feature' && obj.type !== 'FeatureCollection') {
        return { type: 'Feature', geometry: obj, properties: {} };
    }
    return obj;
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
              + ' onclick="_navFeature(\'' + mapId + '\',' + (idx - 1) + ')">\u2039</button>'
              + ' ' + (idx + 1) + ' of ' + n + ' '
              + '<button type="button"' + (idx >= n - 1 ? ' disabled' : '')
              + ' onclick="_navFeature(\'' + mapId + '\',' + (idx + 1) + ')">\u203a</button>'
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
              + '<a href="' + base + '.json">JSON</a>'
              + ' \u00b7 <a href="https://api.openstreetmap.org/api/0.6/' + t + '/' + i
              + '" target="_blank">OSM XML</a>'
              + ' \u00b7 <a href="' + base + '.rdf">RDF/XML</a>'
              + ' \u00b7 <a href="' + base + '.ttl">Turtle</a>'
              + ' \u00b7 <a href="/sparql#from=' + base + '.rdf">SPARQL</a>'
              + '</p>';
    }
    html += Object.keys(props).length > 0 ? propsToHtml(props) : '<em>no properties</em>';
    html += formatBar;
    state.featurePanel.innerHTML = html;
    state.currentIdx = idx;
    // Sync node/way/relation input with the feature now shown in the panel; reset example dropdown
    if (props.osm_type && props.osm_id) {
        var geoForm = document.forms['geo-form'];
        if (geoForm) {
            ['node', 'way', 'relation'].forEach(function(t) {
                var el = geoForm.elements[t];
                if (el) el.value = (t === String(props.osm_type)) ? String(props.osm_id) : '';
            });
        }
        var geoExSel = document.getElementById('geo-example-select');
        if (geoExSel) geoExSel.value = '';
    }
}

function _navFeature(mapId, idx) {
    var state = window.maps[mapId];
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
    var polygonLayers = [], lineLayers = [], pointLayers = [];
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
                polygonLayers.push(layer);
            } else if (t.indexOf('Line') !== -1) {
                layer._origStyle = { color: '#ff3333', weight: 2 };
                lineLayers.push(layer);
            } else {
                layer._origStyle = { color: '#33cc33', weight: 2, fillColor: '#33cc33', fillOpacity: 0.5 };
                pointLayers.push(layer);
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
    });
    // Add in z-order: polygons first (bottom), lines middle, points on top so they stay clickable
    polygonLayers.forEach(function(l) { state.featureLayer.addLayer(l); });
    lineLayers.forEach(function(l) { state.featureLayer.addLayer(l); });
    pointLayers.forEach(function(l) { state.featureLayer.addLayer(l); });
}

// All status-bar and link-bar DOM updates for the fetch lifecycle live here.
// States: 'loading' | 'loaded' | 'idle'
// opts: { url, statusEl, count?, errorMsg?, sourceUrl? }
function _setFetchState(mapId, newState, opts) {
    var suffix = mapId.replace('map-', '');
    var linkEl = document.getElementById('geojson-link-' + suffix);
    if (newState === 'loading') {
        if (opts.statusEl) setStatus(opts.statusEl, 'Loading\u2026');
        if (linkEl) { linkEl.innerHTML = '<span class="spinner"></span>' + _formatLinksHtml(opts.url, null); linkEl.style.display = ''; }
    } else if (newState === 'loaded') {
        var c = opts.count;
        if (opts.statusEl) setStatus(opts.statusEl, c + (c === 1 ? ' feature' : ' features'));
        if (linkEl) { linkEl.innerHTML = _formatLinksHtml(opts.url, opts.sourceUrl); linkEl.style.display = ''; }
    } else if (newState === 'idle') {
        if (opts.statusEl) setStatus(opts.statusEl, opts.errorMsg ? 'Error: ' + opts.errorMsg : '');
        if (linkEl) { linkEl.innerHTML = opts.url ? _formatLinksHtml(opts.url, opts.sourceUrl) : ''; }
    }
}

function loadGeoJsonUrl(mapId, url) {
    var state = window.maps[mapId];
    if (!state) return;
    var statusEl = document.getElementById('status-' + mapId.replace('map-', ''));

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
        state.bboxLayer = _makeBboxLayer(state.map, bbox[0], bbox[1], bbox[2], bbox[3]);
    }
    _setFetchState(mapId, 'loading', { url: url, statusEl: statusEl });

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
            if (geojson.features.length > 0) { _navFeature(mapId, 0); }
            else { if (state.featurePanel) state.featurePanel.innerHTML = ''; }
            var count = state.featureLayer.getLayers().length;
            _setFetchState(mapId, 'loaded', { url: url, statusEl: statusEl, count: count, sourceUrl: sourceUrl });
            _updateSource(mapId, _attributionFor(url));
            if (count > 0) {
                try { state.map.fitBounds(state.featureLayer.getBounds(), { maxZoom: 16 }); } catch (e) {}
            }
        })
        .catch(function(err) {
            clearTimeout(timer);
            if (state.fetchEpoch !== epoch) return;
            var msg = err.name === 'AbortError' ? 'Client timeout (' + (FETCH_TIMEOUT / 1000) + 's)' : err.message;
            _setFetchState(mapId, 'idle', { url: url, statusEl: statusEl, errorMsg: msg, sourceUrl: sourceUrl });
        });
}
