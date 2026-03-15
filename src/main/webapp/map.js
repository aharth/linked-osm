function cancelLoad(mapId, si) {
    var state = window.maps[mapId];
    if (!state || !state.controllerBySi[si]) return;
    state.controllerBySi[si].abort();
}

function clearLoad(mapId, si) {
    var state = window.maps[mapId];
    if (!state) return;
    if (state.highlightedLayer) {
        state.featureLayers[si].eachLayer(function(l) {
            if (l === state.highlightedLayer) state.highlightedLayer = null;
        });
    }
    state.featureLayers[si].clearLayers();
    state.loadedFeaturesBySi[si] = [];
    state.loadedUrlBySi[si] = null;
    state.currentIdxBySi[si] = -1;
    if (state.featurePanels[si]) state.featurePanels[si].innerHTML = '';
    var suffix = mapId.replace('map-', '');
    var statusEl = document.getElementById('status-' + suffix + '-' + si);
    if (statusEl) setStatus(statusEl, '');
    var linkEl = document.getElementById('geojson-link-' + suffix + '-' + si);
    if (linkEl) linkEl.innerHTML = '';
    _updateSource(mapId, null, si);
}

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
                                    attribution: 'Map \u00a9 <a href="https://memomaps.de/">memomaps.de</a> CC-BY-SA, map data \u00a9 OpenStreetMap ODbL' },
    'EOX OSM':   { type: 'wms', url: 'https://tiles.maps.eox.at/wms',
                   wmsLayers: 'osm_3857', format: 'image/jpeg',
                   attribution: '\u00a9 OpenStreetMap contributors, Rendering \u00a9 <a href="https://eox.at">EOX</a>' }
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
        var pngUrl = layer.type === 'wms'
            ? _centerTileWmsUrl(layer.url, layer.wmsLayers, layer.format, c.lat, c.lng, z)
            : _centerTileUrl(layer.url, c.lat, c.lng, z);
        tileEl.innerHTML = '<a href="' + escHtml(pngUrl) + '" target="_blank">PNG</a>';
    }
}

function switchOsmTileLayer(mapId, layerName) {
    var state = window.maps[mapId];
    if (!state) return;
    if (state.tileLayer) state.map.removeLayer(state.tileLayer);
    var layer = OSM_TILE_LAYERS[layerName] || OSM_TILE_LAYERS['Standard'];
    if (layer.type === 'wms') {
        state.tileLayer = L.tileLayer.wms(layer.url,
            { layers: layer.wmsLayers, format: layer.format || 'image/jpeg',
              transparent: false, attribution: '' }).addTo(state.map);
    } else {
        state.tileLayer = L.tileLayer(layer.url, { attribution: '' }).addTo(state.map);
    }
    state.activeLayerName = layerName;
    _updateTileStatus(mapId);
}

function initOsmMap(id, lat, lon, zoom) {
    var m = L.map(id).setView([lat, lon], zoom);
    m.createPane('bboxPane');
    m.getPane('bboxPane').style.zIndex = 450;
    var tl = L.tileLayer(OSM_TILE_LAYERS['Standard'].url, { attribution: '' }).addTo(m);
    var suffix = id.replace('map-', '');

    var featureLayers = [0, 1, 2].map(function() { return L.featureGroup().addTo(m); });

    var featurePanels = [0, 1, 2].map(function(si) {
        return document.getElementById('feature-panel-' + suffix + '-' + si) || null;
    });

    [0, 1, 2].forEach(function(si) {
        (function(capturedSi) {
            var panelEl = featurePanels[capturedSi];
            if (panelEl) {
                panelEl.addEventListener('click', function() {
                    var st = window.maps[id];
                    if (!st || st.currentIdxBySi[capturedSi] < 0) return;
                    _navFeature(id, st.currentIdxBySi[capturedSi], capturedSi);
                });
            }
        })(si);
    });

    window.maps[id] = { map: m, tileLayer: tl, featureLayers: featureLayers,
                        featurePanels: featurePanels, fetchEpochBySi: [0, 0, 0],
                        controllerBySi: [null, null, null],
                        bboxLayer: null, highlightedLayer: null, mapId: id,
                        loadedFeaturesBySi: [[], [], []], loadedUrlBySi: [null, null, null],
                        currentIdxBySi: [-1, -1, -1], activeLayerName: 'Standard' };

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
        var bboxStr = b.getWest().toFixed(6) + ',' + b.getSouth().toFixed(6) + ','
                    + b.getEast().toFixed(6) + ',' + b.getNorth().toFixed(6);
        var inputEl = document.getElementById('bbox-input-' + suffix);
        if (inputEl) inputEl.value = bboxStr;
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
            var _degArea = Math.abs(_b2.getEast() - _b2.getWest()) * Math.abs(_b2.getNorth() - _b2.getSouth());
            var _degAreaStr = _degArea.toFixed(4) + '\u00a0deg\u00b2';
            zoomEl.textContent = 'zoom: ' + m.getZoom() + ' \u00b7 ' + _areaStr + ' \u00b7 ' + _degAreaStr;
        }
        var tileEl = document.getElementById('tile-link-' + suffix);
        if (tileEl) {
            var layerName = (window.maps[id] || {}).activeLayerName || 'Standard';
            var activeLayer = OSM_TILE_LAYERS[layerName] || OSM_TILE_LAYERS['Standard'];
            var c = m.getCenter();
            var z = m.getZoom();
            var pngUrl = activeLayer.type === 'wms'
                ? _centerTileWmsUrl(activeLayer.url, activeLayer.wmsLayers, activeLayer.format, c.lat, c.lng, z)
                : _centerTileUrl(activeLayer.url, c.lat, c.lng, z);
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

    // Wire example dropdown ↔ node/way/relation inputs (bidirectional) — si=1 only
    var geoExSel = document.getElementById('osm-example-select');
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

    // Wire overpass example dropdown → node/way/relation inputs — si=2
    var ovpExSel = document.getElementById('overpass-example-select');
    var ovpForm  = document.forms['overpass-form'];
    if (ovpExSel && ovpForm) {
        ovpExSel.addEventListener('change', function() {
            var match = this.value && this.value.match(/^\/(node|way|relation)\/(\d+)$/);
            if (!match) return;
            ['node', 'way', 'relation'].forEach(function(t) {
                var el = ovpForm.elements[t];
                if (el) el.value = (t === match[1]) ? match[2] : '';
            });
        });
        ['node', 'way', 'relation'].forEach(function(t) {
            var el = ovpForm.elements[t];
            if (el) el.addEventListener('input', function() { ovpExSel.value = ''; });
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

function _centerTileWmsUrl(wmsUrl, wmsLayers, format, lat, lon, z) {
    var tx = Math.floor((lon + 180) / 360 * Math.pow(2, z));
    var ty = Math.floor((1 - Math.log(Math.tan(lat * Math.PI / 180)
           + 1 / Math.cos(lat * Math.PI / 180)) / Math.PI) / 2 * Math.pow(2, z));
    var R = 6378137, n = Math.pow(2, z);
    var tileSize = 2 * Math.PI * R / n;
    var minx = tx * tileSize - Math.PI * R;
    var maxy = Math.PI * R - ty * tileSize;
    var maxx = minx + tileSize;
    var miny = maxy - tileSize;
    return wmsUrl + '?SERVICE=WMS&VERSION=1.1.1&REQUEST=GetMap'
        + '&LAYERS=' + encodeURIComponent(wmsLayers)
        + '&SRS=EPSG:3857'
        + '&BBOX=' + minx.toFixed(2) + ',' + miny.toFixed(2) + ',' + maxx.toFixed(2) + ',' + maxy.toFixed(2)
        + '&WIDTH=256&HEIGHT=256'
        + '&FORMAT=' + encodeURIComponent(format || 'image/jpeg');
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

function _updateSource(mapId, attribution, si) {
    var suffix = mapId.replace('map-', '');
    var el = document.getElementById('source-' + suffix + '-' + si);
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
    if (/^\/osm\/(map|node\/|way\/|relation\/)/.test(url)) {
        html += ' \u00b7 <a href="https://api.openstreetmap.org/api/0.6' + escHtml(url.replace(/^\/osm/, ''))
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

function _renderFeaturePanel(state, idx, si) {
    var panelEl = state.featurePanels[si];
    if (!panelEl) return;
    var n = state.loadedFeaturesBySi[si].length;
    var feature = state.loadedFeaturesBySi[si][idx];
    if (!feature) return;
    var html = '';
    if (n > 1) {
        var mapId = escHtml(state.mapId);
        html += '<p>'
              + '<button type="button"' + (idx <= 0 ? ' disabled' : '')
              + ' onclick="_navFeature(\'' + mapId + '\',' + (idx - 1) + ',' + si + ')">\u2039</button>'
              + ' ' + (idx + 1) + ' of ' + n + ' '
              + '<button type="button"' + (idx >= n - 1 ? ' disabled' : '')
              + ' onclick="_navFeature(\'' + mapId + '\',' + (idx + 1) + ',' + si + ')">\u203a</button>'
              + '</p>';
    }
    var props = feature.properties || {};
    var formatBar = '';
    if (props.osm_id && props.osm_type) {
        var t = escHtml(String(props.osm_type));
        var i = escHtml(String(props.osm_id));
        var base = (si === 2 ? '/overpass/' : '/osm/') + t + '/' + i;
        html += '<p><a href="' + base + '">' + base + '</a></p>';
        var xmlLink;
        if (si === 2) {
            var ovpQ;
            if (t === 'node')     ovpQ = '[out:xml][timeout:60]; node(' + i + '); out body;';
            else if (t === 'way') ovpQ = '[out:xml][timeout:60]; way(' + i + '); out body; >; out skel qt;';
            else                  ovpQ = '[out:xml][timeout:60]; relation(' + i + '); out body; >>; out skel qt;';
            xmlLink = '<a href="https://overpass-api.de/api/interpreter?data=' + encodeURIComponent(ovpQ) + '" target="_blank">Overpass XML</a>';
        } else {
            xmlLink = '<a href="https://api.openstreetmap.org/api/0.6/' + t + '/' + i + '" target="_blank">OSM XML</a>';
        }
        formatBar = '<p>'
              + '<a href="' + base + '.json">JSON</a>'
              + ' \u00b7 ' + xmlLink
              + ' \u00b7 <a href="' + base + '.rdf">RDF/XML</a>'
              + ' \u00b7 <a href="' + base + '.ttl">Turtle</a>'
              + ' \u00b7 <a href="/sparql#from=' + base + '.rdf">SPARQL</a>'
              + '</p>';
    }
    html += Object.keys(props).length > 0 ? propsToHtml(props) : '<em>no properties</em>';
    html += formatBar;
    panelEl.innerHTML = html;
    state.currentIdxBySi[si] = idx;
    // Sync node/way/relation input with the feature now shown in the panel — si=1 only
    if (si === 1 && props.osm_type && props.osm_id) {
        var geoForm = document.forms['geo-form'];
        if (geoForm) {
            ['node', 'way', 'relation'].forEach(function(tp) {
                var el = geoForm.elements[tp];
                if (el) el.value = (tp === String(props.osm_type)) ? String(props.osm_id) : '';
            });
        }
        var geoExSel = document.getElementById('osm-example-select');
        if (geoExSel) geoExSel.value = '';
    }
}

function _navFeature(mapId, idx, si) {
    var state = window.maps[mapId];
    if (!state || idx < 0 || idx >= state.loadedFeaturesBySi[si].length) return;
    if (state.highlightedLayer) {
        if (state.highlightedLayer._origStyle && state.highlightedLayer.setStyle)
            state.highlightedLayer.setStyle(state.highlightedLayer._origStyle);
        state.highlightedLayer = null;
    }
    state.featureLayers[si].eachLayer(function(l) {
        if (l._featureIdx === idx) {
            state.highlightedLayer = l;
            if (l.setStyle) l.setStyle({ color: '#ff0', weight: 4, fillColor: '#ff0', fillOpacity: 0.4 });
        }
    });
    _renderFeaturePanel(state, idx, si);
}

function _renderFeatures(state, geojson, si) {
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
            layer.on('click', (function(capturedSi) {
                return function(e) {
                    L.DomEvent.stopPropagation(e);
                    var st = state;
                    if (st.highlightedLayer && st.highlightedLayer !== this) {
                        if (st.highlightedLayer._origStyle && st.highlightedLayer.setStyle)
                            st.highlightedLayer.setStyle(st.highlightedLayer._origStyle);
                    }
                    st.highlightedLayer = this;
                    if (this.setStyle) this.setStyle({ color: '#ff0', weight: 4, fillColor: '#ff0', fillOpacity: 0.4 });
                    _renderFeaturePanel(st, this._featureIdx, capturedSi);
                };
            })(si));
        }
    });
    // Add in z-order: polygons first (bottom), lines middle, points on top so they stay clickable
    polygonLayers.forEach(function(l) { state.featureLayers[si].addLayer(l); });
    lineLayers.forEach(function(l) { state.featureLayers[si].addLayer(l); });
    pointLayers.forEach(function(l) { state.featureLayers[si].addLayer(l); });
}

// All status-bar and link-bar DOM updates for the fetch lifecycle live here.
// States: 'loading' | 'loaded' | 'idle'
// opts: { url, statusEl, count?, errorMsg?, sourceUrl? }
function _setFetchState(mapId, newState, opts, si) {
    var suffix = mapId.replace('map-', '');
    var linkEl = document.getElementById('geojson-link-' + suffix + '-' + si);
    var mid = escHtml(mapId);
    if (newState === 'loading') {
        if (opts.statusEl) setStatusHtml(opts.statusEl,
            'Loading\u2026 <button type="button" onclick="cancelLoad(\'' + mid + '\',' + si + ')">Cancel</button>');
        if (linkEl) { linkEl.innerHTML = '<span class="spinner"></span>' + _formatLinksHtml(opts.url, null); linkEl.style.display = ''; }
    } else if (newState === 'loaded') {
        var c = opts.count;
        if (opts.statusEl) setStatusHtml(opts.statusEl,
            c + (c === 1 ? ' feature' : ' features') + ' <button type="button" onclick="clearLoad(\'' + mid + '\',' + si + ')">Clear</button>');
        if (linkEl) { linkEl.innerHTML = _formatLinksHtml(opts.url, opts.sourceUrl); linkEl.style.display = ''; }
    } else if (newState === 'idle') {
        if (opts.statusEl) setStatus(opts.statusEl, opts.errorMsg ? 'Error: ' + opts.errorMsg : '');
        if (linkEl) { linkEl.innerHTML = opts.url ? _formatLinksHtml(opts.url, opts.sourceUrl) : ''; }
    }
}

function loadGeoJsonUrl(mapId, url, si) {
    var state = window.maps[mapId];
    if (!state) return;
    var suffix = mapId.replace('map-', '');
    var statusEl = document.getElementById('status-' + suffix + '-' + si);

    state.fetchEpochBySi[si] += 1;
    var epoch = state.fetchEpochBySi[si];

    state.featureLayers[si].clearLayers();
    state.highlightedLayer = null;
    state.loadedFeaturesBySi[si] = [];
    state.loadedUrlBySi[si] = null;
    state.currentIdxBySi[si] = -1;
    if (state.featurePanels[si]) state.featurePanels[si].innerHTML = '';
    if (state.bboxLayer) { state.map.removeLayer(state.bboxLayer); state.bboxLayer = null; }
    _updateSource(mapId, null, si);
    var bbox = _parseBbox(url);
    if (bbox) {
        state.bboxLayer = _makeBboxLayer(state.map, bbox[0], bbox[1], bbox[2], bbox[3]);
    }
    _setFetchState(mapId, 'loading', { url: url, statusEl: statusEl }, si);

    var controller = new AbortController();
    state.controllerBySi[si] = controller;
    var timer = setTimeout(function() { controller.abort(); }, FETCH_TIMEOUT);
    var sourceUrl = null;

    fetch(url, { signal: controller.signal, headers: { 'Accept': 'application/geo+json' } })
        .then(function(r) {
            sourceUrl = r.headers.get('X-Upstream-Source');
            clearTimeout(timer);
            state.controllerBySi[si] = null;
            if (!r.ok) {
                var status = r.status;
                return r.text().then(function(body) {
                    var msg = String(status);
                    try {
                        var j = JSON.parse(body);
                        if (j.error) msg = status + ' \u2014 ' + j.error;
                    } catch (e) {}
                    throw new Error(msg);
                });
            }
            return r.json();
        })
        .then(function(data) {
            if (state.fetchEpochBySi[si] !== epoch) return;
            var geojson = _wrapGeometry(data);
            if (geojson.type !== 'FeatureCollection') {
                geojson = { type: 'FeatureCollection', features: [geojson] };
            }
            state.loadedFeaturesBySi[si] = geojson.features;
            state.loadedUrlBySi[si] = url;
            state.currentIdxBySi[si] = -1;
            _renderFeatures(state, geojson, si);
            if (geojson.features.length > 0) { _navFeature(mapId, 0, si); }
            else { if (state.featurePanels[si]) state.featurePanels[si].innerHTML = ''; }
            var count = state.featureLayers[si].getLayers().length;
            _setFetchState(mapId, 'loaded', { url: url, statusEl: statusEl, count: count, sourceUrl: sourceUrl }, si);
            _updateSource(mapId, geojson.attribution || _attributionFor(url), si);
            if (count > 0) {
                try { state.map.fitBounds(state.featureLayers[si].getBounds(), { maxZoom: 16 }); } catch (e) {}
            }
        })
        .catch(function(err) {
            clearTimeout(timer);
            state.controllerBySi[si] = null;
            if (state.fetchEpochBySi[si] !== epoch) return;
            var msg = err.name === 'AbortError' ? 'Client timeout (' + (FETCH_TIMEOUT / 1000) + 's)' : err.message;
            _setFetchState(mapId, 'idle', { url: url, statusEl: statusEl, errorMsg: msg, sourceUrl: sourceUrl }, si);
        });
}
