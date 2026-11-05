package com.ontologycentral.osmwrap.webapp;

import static org.junit.Assert.assertEquals;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.Test;

/**
 * Content-Location is the sibling file name, and a .ttl variant is parsed
 * against its public document URL (synced from linked-adv 2026-11-04).
 */
public class MultiViewsFilterLocationTest {

    @Test
    public void contentLocationIsTheSiblingFileName() {
        assertEquals("3.3.rdf", MultiViewsFilter.contentLocation("/inspire/base/3.3.rdf"));
        assertEquals("index.html", MultiViewsFilter.contentLocation("/index.html"));
    }

    @Test
    public void ttlBaseIsThePublicDocumentUrl() {
        Map<String, String> h = Map.of("X-Forwarded-Proto", "https",
                "X-Forwarded-Host", "wunderfacts.com", "X-Forwarded-Prefix", "/w");
        HttpServletRequest req = (HttpServletRequest) java.lang.reflect.Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(), new Class<?>[] {HttpServletRequest.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "getHeader" -> h.get((String) args[0]);
                    case "getScheme" -> "http";
                    case "getServerName" -> "localhost";
                    case "getServerPort" -> 8080;
                    case "getContextPath" -> "/ctx";
                    case "isSecure" -> false;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        assertEquals("https://wunderfacts.com/w/vocab/x", MultiViewsFilter.docBase(req, "/vocab/x.ttl"));
    }
}
