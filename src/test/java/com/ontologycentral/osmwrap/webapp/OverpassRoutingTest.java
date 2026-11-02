package com.ontologycentral.osmwrap.webapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import com.ontologycentral.osmwrap.ApiConstants;
import com.ontologycentral.osmwrap.BuildInfo;

public class OverpassRoutingTest {

    @Test
    public void redactRemovesTheTracestrackKey() {
        String key = BuildInfo.getTracestrackApiKey();
        String url = String.format(ApiConstants.TRACESTRACK_OVERPASS_TEMPLATE,
                key.isEmpty() ? "k" : key) + "?data=x";
        String shown = OverpassRouting.redact(url);
        if (key.isEmpty()) assertEquals(url, shown);
        else {
            assertFalse(shown.contains(key));
            assertEquals("https://api.tracestrack.com/overpass/redacted/interpreter?data=x", shown);
        }
    }

    @Test
    public void redactLeavesOtherUrlsAndNull() {
        assertEquals("https://overpass-api.de/api/interpreter",
                OverpassRouting.redact("https://overpass-api.de/api/interpreter"));
        assertNull(OverpassRouting.redact(null));
    }
}
