package com.github.tvbox.osc.precache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.List;

public class HlsPlaylistTest {

    private static final String URL = "https://cdn.example.com/vod/hls/index.m3u8";
    private static final String PREFIX = "http://127.0.0.1:9978/hlsseg?s=abc&i=";

    private static HlsPlaylist parse(String content) {
        HlsPlaylist playlist = HlsPlaylist.parse(URL, content);
        assertNotNull("playlist should be applicable", playlist);
        return playlist;
    }

    @Test
    public void replacesEachSegmentLineAndKeepsOtherLinesVerbatim() {
        String content = "#EXTM3U\n"
                + "#EXT-X-VERSION:3\n"
                + "#EXT-X-TARGETDURATION:10\n"
                + "#EXT-X-MEDIA-SEQUENCE:120\n"
                + "#EXTINF:9.009,\n"
                + "seg0.ts\n"
                + "#EXTINF:10.0,\n"
                + "\n"
                + "https://other.example.com/abs/seg1.ts\n"
                + "#EXT-X-DISCONTINUITY\n"
                + "#EXTINF:4.5,\n"
                + "../seg2.ts?token=x\n"
                + "#EXT-X-ENDLIST\n";

        String rendered = parse(content).render(PREFIX);

        assertEquals("#EXTM3U\n"
                + "#EXT-X-VERSION:3\n"
                + "#EXT-X-TARGETDURATION:10\n"
                + "#EXT-X-MEDIA-SEQUENCE:120\n"
                + "#EXTINF:9.009,\n"
                + PREFIX + "0\n"
                + "#EXTINF:10.0,\n"
                + "\n"
                + PREFIX + "1\n"
                + "#EXT-X-DISCONTINUITY\n"
                + "#EXTINF:4.5,\n"
                + PREFIX + "2\n"
                + "#EXT-X-ENDLIST\n", rendered);
    }

    @Test
    public void resolvesRelativeAndKeepsAbsoluteSegmentUrlsInOrder() {
        String content = "#EXTM3U\n"
                + "#EXTINF:9.009,\n"
                + "seg0.ts\n"
                + "#EXTINF:10.0,\n"
                + "https://other.example.com/abs/seg1.ts\n"
                + "#EXTINF:4.5,\n"
                + "../seg2.ts?token=x\n"
                + "#EXT-X-ENDLIST\n";

        List<HlsSegment> segments = parse(content).segments();

        assertEquals(3, segments.size());
        String[] urls = {
                "https://cdn.example.com/vod/hls/seg0.ts",
                "https://other.example.com/abs/seg1.ts",
                "https://cdn.example.com/vod/seg2.ts?token=x",
        };
        long[] durations = {9009, 10000, 4500};
        for (int i = 0; i < urls.length; i++) {
            assertEquals(i, segments.get(i).index);
            assertEquals(urls[i], segments.get(i).url);
            assertEquals(durations[i], segments.get(i).durationMs);
        }
    }

    @Test
    public void keyIsLowercaseSha1OfAbsoluteUrl() {
        String content = "#EXTM3U\n#EXTINF:5,\nseg0.ts\n#EXTINF:5,\nhttps://other.example.com/abs/seg1.ts\n#EXT-X-ENDLIST";

        List<HlsSegment> segments = parse(content).segments();

        assertEquals("f543732b83d9d1b392d9ab2ac8144415c6b6bc06", segments.get(0).key);
        assertEquals("564dcc6dfff2667d4c20cf73b3ce35c4d36959d0", segments.get(1).key);
    }

    @Test(expected = UnsupportedOperationException.class)
    public void segmentsAreUnmodifiable() {
        parse("#EXTM3U\n#EXTINF:5,\nseg0.ts\n#EXT-X-ENDLIST").segments().clear();
    }

    @Test
    public void keepsCrlfLineEndingsBlankLinesAndMissingFinalNewline() {
        String content = "#EXTM3U\r\n#EXTINF:5,\r\n  seg0.ts \r\n\r\n#EXT-X-DISCONTINUITY\r\n#EXTINF:5,\r\nseg1.ts\r\n#EXT-X-ENDLIST";

        HlsPlaylist playlist = parse(content);

        assertEquals("#EXTM3U\r\n#EXTINF:5,\r\n" + PREFIX + "0\r\n\r\n#EXT-X-DISCONTINUITY\r\n#EXTINF:5,\r\n"
                + PREFIX + "1\r\n#EXT-X-ENDLIST", playlist.render(PREFIX));
        assertEquals("https://cdn.example.com/vod/hls/seg0.ts", playlist.segments().get(0).url);
    }

    @Test
    public void resolvesUriOfEveryKeyAndMapLineAndKeepsOtherAttributes() {
        String content = "#EXTM3U\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"key1.key\",IV=0x00000000000000000000000000000001\n"
                + "#EXT-X-MAP:URI=\"init.mp4\",BYTERANGE=\"720@0\"\n"
                + "#EXTINF:6,\n"
                + "a.m4s\n"
                + "#EXT-X-KEY:METHOD=NONE\n"
                + "#EXTINF:6,\n"
                + "b.m4s\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"https://keys.example.com/k2\",KEYFORMAT=\"identity\"\n"
                + "#EXT-X-MAP:URI=\"/init2.mp4\"\n"
                + "#EXTINF:6,\n"
                + "c.m4s\n"
                + "#EXT-X-ENDLIST\n";

        String rendered = parse(content).render(PREFIX);

        assertEquals("#EXTM3U\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"https://cdn.example.com/vod/hls/key1.key\",IV=0x00000000000000000000000000000001\n"
                + "#EXT-X-MAP:URI=\"https://cdn.example.com/vod/hls/init.mp4\",BYTERANGE=\"720@0\"\n"
                + "#EXTINF:6,\n"
                + PREFIX + "0\n"
                + "#EXT-X-KEY:METHOD=NONE\n"
                + "#EXTINF:6,\n"
                + PREFIX + "1\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"https://keys.example.com/k2\",KEYFORMAT=\"identity\"\n"
                + "#EXT-X-MAP:URI=\"https://cdn.example.com/init2.mp4\"\n"
                + "#EXTINF:6,\n"
                + PREFIX + "2\n"
                + "#EXT-X-ENDLIST\n", rendered);
    }

    @Test
    public void replacesOnlyTheUriAttributeValueWhenSameTextAppearsElsewhere() {
        String content = "#EXTM3U\n"
                + "#EXT-X-KEY:METHOD=AES-128,KEYFORMAT=\"k.key\",URI=\"k.key\"\n"
                + "#EXTINF:6,\na.ts\n#EXT-X-ENDLIST\n";

        String rendered = parse(content).render(PREFIX);

        assertEquals("#EXTM3U\n"
                + "#EXT-X-KEY:METHOD=AES-128,KEYFORMAT=\"k.key\",URI=\"https://cdn.example.com/vod/hls/k.key\"\n"
                + "#EXTINF:6,\n" + PREFIX + "0\n#EXT-X-ENDLIST\n", rendered);
    }

    @Test
    public void durationFallsBackToTargetDurationThenTenSeconds() {
        Object[][] cases = {
                // name, content, expected duration of segment 0
                {"missing EXTINF uses TARGETDURATION",
                        "#EXTM3U\n#EXT-X-TARGETDURATION:6\nseg0.ts\n#EXT-X-ENDLIST", 6000L},
                {"unparsable EXTINF uses TARGETDURATION",
                        "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:abc,\nseg0.ts\n#EXT-X-ENDLIST", 6000L},
                {"missing EXTINF and TARGETDURATION uses 10s",
                        "#EXTM3U\nseg0.ts\n#EXT-X-ENDLIST", 10000L},
                {"EXTINF rounds to nearest millisecond",
                        "#EXTM3U\n#EXTINF:3.0006,\nseg0.ts\n#EXT-X-ENDLIST", 3001L},
                {"EXTINF without comma",
                        "#EXTM3U\n#EXTINF:7\nseg0.ts\n#EXT-X-ENDLIST", 7000L},
        };
        for (Object[] c : cases) {
            HlsPlaylist playlist = HlsPlaylist.parse(URL, (String) c[1]);
            assertNotNull((String) c[0], playlist);
            assertEquals((String) c[0], c[2], playlist.segments().get(0).durationMs);
        }
    }

    @Test
    public void extinfAppliesOnlyToTheNextSegment() {
        List<HlsSegment> segments = parse("#EXTM3U\n#EXT-X-TARGETDURATION:8\n#EXTINF:3,\nseg0.ts\nseg1.ts\n#EXT-X-ENDLIST")
                .segments();

        assertEquals(3000L, segments.get(0).durationMs);
        assertEquals(8000L, segments.get(1).durationMs);
    }

    @Test
    public void returnsNullWhenNotApplicable() {
        String media = "#EXTINF:5,\nseg0.ts\n";
        Object[][] cases = {
                // name, playlistUrl, content
                {"null content", URL, null},
                {"empty content", URL, ""},
                {"not starting with #EXTM3U", URL, "<html></html>"},
                {"leading whitespace before #EXTM3U", URL, " #EXTM3U\n" + media + "#EXT-X-ENDLIST"},
                {"BOM before #EXTM3U", URL, "﻿#EXTM3U\n" + media + "#EXT-X-ENDLIST"},
                {"master playlist", URL, "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nlow.m3u8\n#EXT-X-ENDLIST"},
                {"i-frame playlist", URL, "#EXTM3U\n#EXT-X-I-FRAME-STREAM-INF:URI=\"i.m3u8\"\n" + media + "#EXT-X-ENDLIST"},
                {"byterange", URL, "#EXTM3U\n#EXTINF:5,\n#EXT-X-BYTERANGE:100@0\nseg0.ts\n#EXT-X-ENDLIST"},
                {"indented byterange", URL, "#EXTM3U\n#EXTINF:5,\n  #EXT-X-BYTERANGE:100@0\nseg0.ts\n#EXT-X-ENDLIST"},
                {"ll-hls part", URL, "#EXTM3U\n#EXT-X-PART:DURATION=1,URI=\"p.ts\"\n" + media + "#EXT-X-ENDLIST"},
                {"ll-hls preload hint", URL, "#EXTM3U\n" + media + "#EXT-X-PRELOAD-HINT:TYPE=PART,URI=\"p.ts\"\n#EXT-X-ENDLIST"},
                {"live playlist without ENDLIST", URL, "#EXTM3U\n" + media},
                {"no segments", URL, "#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXT-X-ENDLIST\n"},
                {"null playlistUrl", null, "#EXTM3U\n" + media + "#EXT-X-ENDLIST"},
                {"relative playlistUrl", "index.m3u8", "#EXTM3U\n" + media + "#EXT-X-ENDLIST"},
                {"non-http playlistUrl", "ftp://cdn.example.com/index.m3u8", "#EXTM3U\n" + media + "#EXT-X-ENDLIST"},
        };
        for (Object[] c : cases) {
            assertNull((String) c[0], HlsPlaylist.parse((String) c[1], (String) c[2]));
        }
    }

    @Test
    public void acceptsHttpPlaylistUrl() {
        HlsPlaylist playlist = HlsPlaylist.parse("http://cdn.example.com/a/index.m3u8",
                "#EXTM3U\n#EXTINF:5,\nseg0.ts\n#EXT-X-ENDLIST");

        assertNotNull(playlist);
        assertEquals("http://cdn.example.com/a/seg0.ts", playlist.segments().get(0).url);
    }
}
