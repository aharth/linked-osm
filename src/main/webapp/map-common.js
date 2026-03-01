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

function propsToHtml(props) {
    var html = '<dl>';
    for (var k in props) {
        var v = props[k] !== null && props[k] !== undefined ? String(props[k]) : '';
        var val = v.match(/^https?:\/\//)
            ? '<a href="' + escHtml(v) + '" target="_blank">' + escHtml(v) + '</a>'
            : escHtml(v);
        html += '<dt>' + escHtml(k) + '</dt><dd>' + val + '</dd>';
    }
    html += '</dl>';
    return html;
}
