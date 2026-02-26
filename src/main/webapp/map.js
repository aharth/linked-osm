var FETCH_TIMEOUT = 30000;

window.osmMaps = {};

function escHtml(s) {
    return String(s)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

function _propsToHtml(props) {
    var html = '<table style="border-collapse:collapse;font-size:0.9em">';
    for (var k in props) {
        if (k === 'osm_type' && props.osm_id) continue;  // shown via osm_id row
        var v = props[k];
        var vHtml;
        if (k === 'osm_id' && props.osm_type) {
            var osmType = String(props.osm_type);
            var osmId = String(v);
            vHtml = '<a href="/' + escHtml(osmType) + '/' + escHtml(osmId) + '">' + escHtml(osmId) + '</a>'
                  + ' (<a href="/' + escHtml(osmType) + '/' + escHtml(osmId) + '.rdf">rdf</a>)'
                  + ' (<a href="/' + escHtml(osmType) + '/' + escHtml(osmId) + '.json">json</a>)'
                  + ' (<a href="https://www.openstreetmap.org/' + escHtml(osmType) + '/' + escHtml(osmId) + '" target="_blank">osm</a>)';
        } else {
            vHtml = escHtml(String(v));
        }
        html += '<tr>'
              + '<td style="padding:2px 8px 2px 0;font-weight:bold;vertical-align:top;white-space:nowrap">' + escHtml(k) + '</td>'
              + '<td style="padding:2px 0;word-break:break-word;max-width:300px">' + vHtml + '</td>'
              + '</tr>';
    }
    html += '</table>';
    return html;
}

function _sourceAttribution(url) {
    if (url.indexOf('/search') !== -1) {
        return 'Data: \u00a9 OpenStreetMap contributors via Nominatim (ODbL)';
    }
    if (url.indexOf('/poi') !== -1 || url.indexOf('/around') !== -1 || url.indexOf('/geo/overpass/') !== -1) {
        return 'Data: \u00a9 OpenStreetMap contributors via Overpass API (ODbL)';
    }
    return 'Data: \u00a9 OpenStreetMap contributors (ODbL)';
}

function initOsmMap(id, lat, lon, zoom) {
    var m = L.map(id).setView([lat, lon], zoom);
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', { attribution: '' }).addTo(m);
    var fg = L.featureGroup().addTo(m);
    var suffix = id.replace('map-', '');
    window.osmMaps[id] = { map: m, featureLayer: fg, fetchEpoch: 0, bboxLayer: null };

    // Register popupclose ONCE to restore original styles (avoids listener accumulation)
    m.on('popupclose', function() {
        fg.eachLayer(function(l) {
            if (l._origStyle && l.setStyle) l.setStyle(l._origStyle);
        });
    });

    function updateBbox() {
        var b = m.getBounds();
        var el = document.getElementById('bbox-' + suffix);
        if (el) {
            el.textContent = 'bbox: '
                + b.getWest().toFixed(6) + ',' + b.getSouth().toFixed(6) + ','
                + b.getEast().toFixed(6) + ',' + b.getNorth().toFixed(6);
        }
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


function _parseBbox(url) {
    var m = url.match(/[?&]bbox=([^&]+)/);
    if (!m) return null;
    var parts = decodeURIComponent(m[1]).split(',');
    if (parts.length !== 4) return null;
    var n = parts.map(Number);
    if (n.some(isNaN)) return null;
    return n; // [W, S, E, N]
}

function _wrapGeometry(obj) {
    if (obj.type && obj.type !== 'Feature' && obj.type !== 'FeatureCollection') {
        return { type: 'Feature', geometry: obj, properties: {} };
    }
    return obj;
}

function _renderFeatures(state, geojson) {
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
            var t = (feature.geometry && feature.geometry.type) || '';
            if (t.indexOf('Polygon') !== -1) {
                layer._origStyle = { color: '#3388ff', weight: 2, fillColor: '#3388ff', fillOpacity: 0.2 };
            } else if (t.indexOf('Line') !== -1) {
                layer._origStyle = { color: '#ff3333', weight: 2 };
            } else {
                layer._origStyle = { color: '#33cc33', weight: 2, fillColor: '#33cc33', fillOpacity: 0.5 };
            }
            var props = feature.properties || {};
            var html = Object.keys(props).length > 0 ? _propsToHtml(props) : 'no properties';
            layer.bindPopup(html, { maxWidth: 400, maxHeight: 300 });
            layer.on('click', function() {
                if (this.setStyle) this.setStyle({ color: '#ff0', weight: 4, fillColor: '#ff0', fillOpacity: 0.4 });
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
    if (state.bboxLayer) { state.map.removeLayer(state.bboxLayer); state.bboxLayer = null; }
    var bbox = _parseBbox(url);
    if (bbox) {
        state.bboxLayer = L.rectangle([[bbox[1], bbox[0]], [bbox[3], bbox[2]]], {
            color: '#888', weight: 3, fillOpacity: 0, dashArray: '6 4', interactive: false
        }).addTo(state.map);
    }
    if (statusEl) statusEl.textContent = 'Loading\u2026';

    var suffix = mapId.replace('map-', '');
    var linkEl = document.getElementById('geojson-link-' + suffix);
    if (linkEl) {
        linkEl.innerHTML = '<span class="spinner"></span><a href="' + escHtml(url) + '">' + escHtml(url) + '</a>';
        linkEl.style.display = '';
    }
    var srcEl  = document.getElementById('source-' + suffix);
    if (srcEl)  { srcEl.style.display  = 'none'; srcEl.textContent  = ''; }

    var controller = new AbortController();
    var timer = setTimeout(function() { controller.abort(); }, FETCH_TIMEOUT);

    fetch(url, { signal: controller.signal, headers: { 'Accept': 'application/geo+json' } })
        .then(function(r) {
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
            _renderFeatures(state, geojson);
            var count = state.featureLayer.getLayers().length;
            if (statusEl) statusEl.textContent = count + (count === 1 ? ' feature' : ' features');
            if (count > 0) {
                try { state.map.fitBounds(state.featureLayer.getBounds(), { maxZoom: 16 }); } catch (e) {}
            }
            if (linkEl) {
                linkEl.innerHTML = '<a href="' + escHtml(url) + '">' + escHtml(url) + '</a>';
                linkEl.style.display = '';
            }
            if (srcEl) {
                srcEl.textContent = _sourceAttribution(url);
                srcEl.style.display = '';
            }
        })
        .catch(function(err) {
            clearTimeout(timer);
            if (state.fetchEpoch !== epoch) return;
            var msg = err.name === 'AbortError' ? 'Request timed out' : err.message;
            if (statusEl) statusEl.textContent = 'Error: ' + msg;
            if (linkEl) { linkEl.innerHTML = '<a href="' + escHtml(url) + '">' + escHtml(url) + '</a>'; }
        });
}
