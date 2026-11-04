package com.ontologycentral.osmwrap;

import org.junit.Test;

import static org.junit.Assert.*;

public class TaginfoConverterTest {

    // wbsearchentities for "amenity=bar": the exact item first, a longer label after
    static final String SEARCH = "{\"searchinfo\":{\"search\":\"amenity=bar\"},\"search\":["
        + "{\"id\":\"Q4892\",\"label\":\"amenity=bar\",\"description\":\"An establishment in a modern style serving "
        + "alcoholic drinks, mainly to customers standing around the bar.\"},"
        + "{\"id\":\"Q9999\",\"label\":\"amenity=bar;pub\",\"description\":\"something else\"}],\"success\":1}";

    @Test
    public void dataItemExactLabel() {
        TaginfoConverter.WikiItem item = TaginfoConverter.dataItem(SEARCH, "amenity=bar");
        assertNotNull(item);
        assertEquals("Q4892", item.id());
        assertTrue(item.description().startsWith("An establishment in a modern style"));
    }

    @Test
    public void dataItemNoExactLabel() {
        assertNull(TaginfoConverter.dataItem(SEARCH, "amenity=pub"));
    }

    @Test
    public void dataItemEmptyDescription() {
        String json = "{\"search\":[{\"id\":\"Q1\",\"label\":\"amenity=bar\",\"description\":\"\"}]}";
        assertNull(TaginfoConverter.dataItem(json, "amenity=bar"));
    }

    @Test
    public void dataItemNoSearch() {
        assertNull(TaginfoConverter.dataItem("{\"error\":{\"code\":\"x\"}}", "amenity=bar"));
    }
}
