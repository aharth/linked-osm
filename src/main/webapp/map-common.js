// map-common.js — shared between linked-osm and linked-inspire.
// Canonical: linked-osm/src/main/webapp/map-common.js
// Symlinked:  linked-inspire/src/main/webapp/map-common.js
/* rem not em: Leaflet sets font-size:12px on .leaflet-container, so em
   would resolve to 12px there instead of the body 16px. */

var FETCH_TIMEOUT = 130000; // slightly above the server's 120 s upstream timeout

window.maps = {};

function escHtml(s) {
    return String(s)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

function setStatus(el, text, spinning) {
    el.textContent = text;
    el.classList.toggle('spinner', !!spinning);
}

function setStatusHtml(el, html, spinning) {
    el.innerHTML = html;
    el.classList.toggle('spinner', !!spinning);
}

var _CURIE_PREFIXES = {
    'rdf':  'http://www.w3.org/1999/02/22-rdf-syntax-ns#',
    'rdfs': 'http://www.w3.org/2000/01/rdf-schema#',
    'owl':  'http://www.w3.org/2002/07/owl#',
    'dct':  'http://purl.org/dc/terms/',
    'skos': 'http://www.w3.org/2004/02/skos/core#',
};

function _expandCurie(k) {
    var m = k.match(/^([a-z]+):([^\/].*)$/);
    if (m && _CURIE_PREFIXES[m[1]]) return _CURIE_PREFIXES[m[1]] + m[2];
    return null;
}

function propsToHtml(props) {
    var html = '<dl>';
    for (var k in props) {
        var rawV = props[k];
        var vals = Array.isArray(rawV) ? rawV : [rawV];
        var dt;
        if (k.match(/^https?:\/\//)) {
            var label = k.includes('#') ? k.split('#').pop() : k.split('/').filter(Boolean).pop();
            dt = '<a href="' + escHtml(k) + '" target="_blank">' + escHtml(label) + '</a>';
        } else if (k.match(/^\//)) {
            var label = k.includes('#') ? k.split('#').pop() : k.split('/').filter(Boolean).pop();
            dt = '<a href="' + escHtml(k) + '">' + escHtml(label) + '</a>';
        } else {
            var expanded = _expandCurie(k);
            dt = expanded
                ? '<a href="' + escHtml(expanded) + '" target="_blank">' + escHtml(k) + '</a>'
                : escHtml(k);
        }
        html += '<dt>' + dt + '</dt>';
        vals.forEach(function(rawVal) {
            var v = rawVal !== null && rawVal !== undefined ? String(rawVal) : '';
            var val;
            if (k === '_droppedGeometry') {
                val = escHtml(v) + ' <span class="prop-note">(geometry type not rendered)</span>';
            } else if (v.match(/^https?:\/\//)) {
                val = '<a href="' + escHtml(v) + '" target="_blank">' + escHtml(v) + '</a>';
            } else if (v.match(/^\//)) {
                val = '<a href="' + escHtml(v) + '">' + escHtml(v) + '</a>';
            } else {
                val = escHtml(v);
            }
            html += '<dd>' + val + '</dd>';
        });
    }
    html += '</dl>';
    return html;
}

function updateVocabLink(sel, vocabBase, spanId) {
    var span = document.getElementById(spanId);
    if (!span) return;
    var v = sel.value;
    var svc = sel.dataset.svc || '';
    if (!svc) { span.innerHTML = ''; return; }
    if (v && v !== '*') {
        span.innerHTML = '<a href="' + vocabBase + '?typename=' + encodeURIComponent(v)
            + '&svc=' + encodeURIComponent(svc) + '" target="_blank">Vocabulary</a>';
    } else if (v === '*') {
        span.innerHTML = '<a href="' + vocabBase + '?svc=' + encodeURIComponent(svc)
            + '" target="_blank">Vocabularies</a>';
    } else {
        span.innerHTML = '';
    }
}
