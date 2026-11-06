package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class M3U8PurifyTest {

    private static final String TS_URL_PRE = "https://cdn.a.com/v/";

    @Before
    public void setUp() {
        VideoParseRuler.clearRule();
    }

    @Test
    public void returnsNullForNonM3u8Content() {
        assertNull(M3U8.purify(TS_URL_PRE, null));
        assertNull(M3U8.purify(TS_URL_PRE, ""));
        assertNull(M3U8.purify(TS_URL_PRE, "<html></html>"));
    }

    @Test
    public void removesSegmentsWhosePrefixIsTheMinority() {
        StringBuilder m3u8 = new StringBuilder("#EXTM3U\n");
        for (int i = 0; i < 10; i++) {
            m3u8.append("#EXTINF:5,\n").append("https://cdn.a.com/v/seg000").append(i).append(".ts\n");
        }
        m3u8.append("#EXTINF:3,\n").append("https://ad.b.com/x/ad0001.ts\n");

        String result = M3U8.purify(TS_URL_PRE, m3u8.toString());

        assertFalse(result.contains("ad.b.com"));
        assertTrue(result.contains("https://cdn.a.com/v/seg0009.ts"));
        assertFalse(result.contains("#EXTINF:3,"));
        assertEquals(1, M3U8.currentAdCount);
    }

    @Test
    public void resolvesKeptRelativeSegmentsAgainstTsUrlPre() {
        StringBuilder m3u8 = new StringBuilder("#EXTM3U\n");
        for (int i = 0; i < 10; i++) {
            m3u8.append("#EXTINF:5,\n").append("seg000").append(i).append(".ts\n");
        }
        m3u8.append("#EXTINF:3,\n").append("ad0001.ts\n");

        String result = M3U8.purify(TS_URL_PRE, m3u8.toString());

        assertTrue(result.contains("https://cdn.a.com/v/seg0000.ts"));
        assertFalse(result.contains("ad0001.ts"));
    }

    @Test
    public void returnsNullWhenSingleSourceAndNoHostRegex() {
        StringBuilder m3u8 = new StringBuilder("#EXTM3U\n");
        for (int i = 0; i < 10; i++) {
            m3u8.append("#EXTINF:5,\n").append("https://cdn.a.com/v/seg000").append(i).append(".ts\n");
        }

        assertNull(M3U8.purify(TS_URL_PRE, m3u8.toString()));
    }
}
