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
    'locn': 'http://www.w3.org/ns/locn#',
    'foaf': 'http://xmlns.com/foaf/0.1/',
};

function _expandCurie(k) {
    var m = k.match(/^([a-z]+):([^\/].*)$/);
    if (m && _CURIE_PREFIXES[m[1]]) return _CURIE_PREFIXES[m[1]] + m[2];
    return null;
}

function _isBnodeObj(obj) {
    var ks = Object.keys(obj);
    return !(ks.length === 1 && ks[0] === '@id');
}

function _renderNestedObject(obj, ctx) {
    if (typeof obj !== 'object' || obj === null) {
        var v = String(obj);
        if (v.match(/^https?:\/\//)) {
            return '<a href="' + escHtml(v) + '" target="_blank">' + escHtml(v) + '</a>';
        } else if (v.match(/^\//)) {
            return '<a href="' + escHtml(v) + '">' + escHtml(v) + '</a>';
        } else if (ctx && ctx.svc && typeof obj === 'string' && obj.match(/^urn:adv:oid:(.+)$/)) {
            var oid = obj.match(/^urn:adv:oid:(.+)$/)[1];
            return escHtml(obj) + ' <button type="button" onclick="setWfsByIdInput(\''
                + escHtml(ctx.mapId) + '\',\'' + escHtml(String(ctx.fi)) + '\',\'' + escHtml(oid) + '\')">Set</button>';
        }
        return escHtml(v);
    }

    if (Array.isArray(obj)) {
        var items = obj.map(function(item) { return _renderNestedObject(item, ctx); });
        return items.join(', ');
    }

    // IRI reference: {"@id": "..."} — render as a plain link, no nested dl
    if (!_isBnodeObj(obj)) {
        var href = String(obj['@id']);
        if (href.match(/^https?:\/\//)) return '<a href="' + escHtml(href) + '" target="_blank">' + escHtml(href) + '</a>';
        if (href.match(/^\//)) return '<a href="' + escHtml(href) + '">' + escHtml(href) + '</a>';
        return escHtml(href);
    }

    // Blank node
    var html = '<dl class="bnode-props" style="margin-left: 1em;">';
    for (var k in obj) {
        var label = k;
        var dt = '';
        if (k.match(/^https?:\/\//)) {
            label = k.includes('#') ? k.split('#').pop() : k.split('/').filter(Boolean).pop();
            dt = '<a href="' + escHtml(k) + '" target="_blank">' + escHtml(label) + '</a>';
        } else if (k.match(/^\//)) {
            label = k.includes('#') ? k.split('#').pop() : k.split('/').filter(Boolean).pop();
            dt = '<a href="' + escHtml(k) + '">' + escHtml(label) + '</a>';
        } else {
            var expanded = _expandCurie(k);
            dt = expanded ? '<a href="' + escHtml(expanded) + '" target="_blank">' + escHtml(k) + '</a>' : escHtml(k);
        }
        html += '<dt>' + dt + '</dt>';
        var v = obj[k];
        if (Array.isArray(v)) {
            v.forEach(function(item) { html += '<dd>' + _renderNestedObject(item, ctx) + '</dd>'; });
        } else {
            html += '<dd>' + _renderNestedObject(v, ctx) + '</dd>';
        }
    }
    html += '</dl>';
    return html;
}

function propsToHtml(props, ctx) {
    var html = '<dl>';
    for (var k in props) {
        if (k === 'locn:geometry' || k === 'http://www.w3.org/ns/locn#geometry') continue;
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
            if (k === '_droppedGeometry') {
                html += '<dd>' + escHtml(v) + ' <span class="prop-note">(geometry type not rendered)</span></dd>';
            } else if (ctx && ctx.svc && k !== 'identifier' && v.match(/^urn:adv:oid:(.+)$/)) {
                var oid = v.match(/^urn:adv:oid:(.+)$/)[1];
                html += '<dd>' + escHtml(v) + ' <button type="button" onclick="setWfsByIdInput(\''
                    + escHtml(ctx.mapId) + '\',\''
                    + escHtml(String(ctx.fi)) + '\',\''
                    + escHtml(oid) + '\')">Set</button></dd>';
            } else if (v.match(/^https?:\/\//)) {
                html += '<dd><a href="' + escHtml(v) + '" target="_blank">' + escHtml(v) + '</a></dd>';
            } else if (v.match(/^\//)) {
                html += '<dd><a href="' + escHtml(v) + '">' + escHtml(v) + '</a></dd>';
            } else if (typeof rawVal === 'object' && rawVal !== null) {
                if (!_isBnodeObj(rawVal)) {
                    var _href = String(rawVal['@id']);
                    if (ctx && ctx.svc && k !== 'identifier' && _href.match(/^urn:adv:oid:(.+)$/)) {
                        var oid = _href.match(/^urn:adv:oid:(.+)$/)[1];
                        html += '<dd>' + escHtml(_href) + ' <button type="button" onclick="setWfsByIdInput(\''
                            + escHtml(ctx.mapId) + '\',\''
                            + escHtml(String(ctx.fi)) + '\',\''
                            + escHtml(oid) + '\')">Set</button></dd>';
                    } else {
                        html += '<dd>' + _renderNestedObject(rawVal, ctx) + '</dd>';
                    }
                } else {
                    html += '<dd>' + _renderNestedObject(rawVal, ctx) + '</dd>';
                }
            } else {
                html += '<dd>' + (v === '' ? '&nbsp;' : escHtml(v)) + '</dd>';
            }
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
