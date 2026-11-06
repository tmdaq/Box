package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * M3u8PlayPlanner 承接 PlayFragment / PlayActivity 的 playUrl 判断。
 * precache=false 的用例锁住改动前的行为（characterization）。
 */
public class M3u8PlayPlannerTest {

    private static final int PORT = 9978;
    private static final String LOCAL = "http://127.0.0.1:9978/m3u8";
    private static final String URL = "https://cdn.a.com/path/index.m3u8";
    private static final String MEDIA = "#EXTM3U\n#EXTINF:5,\nseg0.ts\n#EXTINF:5,\nseg1.ts\n#EXT-X-ENDLIST\n";

    // ---------- shouldFetch（precache=false） ----------

    @Test
    public void shouldFetchIsFalseWhenPurifyOffEvenForM3u8() {
        assertFalse(M3u8PlayPlanner.shouldFetch(URL, false, false));
        assertFalse(M3u8PlayPlanner.shouldFetch("http://127.0.0.1/proxy?x=1", false, false));
        assertFalse(M3u8PlayPlanner.shouldFetch("https://cdn.a.com/v.mp4", false, false));
        // 对照：只打开去广告，同样的地址就要拉取
        assertTrue(M3u8PlayPlanner.shouldFetch(URL, true, false));
        assertTrue(M3u8PlayPlanner.shouldFetch("http://127.0.0.1/proxy?x=1", true, false));
    }

    @Test
    public void shouldFetchIsTrueWhenPurifyOnAndUrlContainsM3u8() {
        assertTrue(M3u8PlayPlanner.shouldFetch(URL, true, false));
        assertTrue(M3u8PlayPlanner.shouldFetch("https://cdn.a.com/play.m3u8?token=1", true, false));
    }

    @Test
    public void shouldFetchIsTrueWhenPurifyOnAndUrlIsLocalProxyWithoutPort() {
        assertTrue(M3u8PlayPlanner.shouldFetch("http://127.0.0.1/proxy?do=py", true, false));
    }

    @Test
    public void shouldFetchIsFalseWhenPurifyOnAndUrlIsLocalProxyWithPort() {
        assertFalse(M3u8PlayPlanner.shouldFetch("http://127.0.0.1:9978/proxy?do=py", true, false));
        // 对照：不带端口时要拉取
        assertTrue(M3u8PlayPlanner.shouldFetch("http://127.0.0.1/proxy?do=py", true, false));
    }

    @Test
    public void shouldFetchIsFalseWhenPurifyOnAndUrlIsPlainDirectLink() {
        assertFalse(M3u8PlayPlanner.shouldFetch("https://cdn.a.com/v.mp4", true, false));
        // 对照：同一主机上的 m3u8 要拉取
        assertTrue(M3u8PlayPlanner.shouldFetch("https://cdn.a.com/v.m3u8", true, false));
    }

    // ---------- isPlaylist ----------

    @Test
    public void isPlaylistOnlyWhenContentStartsWithExtm3u() {
        assertTrue(M3u8PlayPlanner.isPlaylist(MEDIA));
        assertFalse(M3u8PlayPlanner.isPlaylist("<html></html>"));
        assertFalse(M3u8PlayPlanner.isPlaylist(" #EXTM3U\n"));
        assertFalse(M3u8PlayPlanner.isPlaylist(""));
    }

    @Test(expected = NullPointerException.class)
    public void isPlaylistThrowsOnNullContent() {
        M3u8PlayPlanner.isPlaylist(null);
    }

    // ---------- findForwardUrl ----------

    @Test
    public void findForwardUrlReturnsEmptyForMediaPlaylist() {
        assertEquals("", M3u8PlayPlanner.findForwardUrl(URL, MEDIA));
        // 对照：同一地址下只含一个子列表的主列表能找到子列表
        assertEquals("https://cdn.a.com/path/720/index.m3u8",
                M3u8PlayPlanner.findForwardUrl(URL, "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\n720/index.m3u8\n"));
    }

    @Test
    public void findForwardUrlReturnsAbsoluteSubPlaylist() {
        String master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nhttps://other.b.com/hls/720/index.m3u8\n";
        assertEquals("https://other.b.com/hls/720/index.m3u8", M3u8PlayPlanner.findForwardUrl(URL, master));
    }

    @Test
    public void findForwardUrlResolvesRootRelativeSubPlaylistAgainstHost() {
        String master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\n/hls/720/index.m3u8\n";
        assertEquals("https://cdn.a.com/hls/720/index.m3u8", M3u8PlayPlanner.findForwardUrl(URL, master));
    }

    @Test
    public void findForwardUrlResolvesRelativeSubPlaylistAgainstDirectory() {
        String master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\n720/index.m3u8\n";
        assertEquals("https://cdn.a.com/path/720/index.m3u8", M3u8PlayPlanner.findForwardUrl(URL, master));
    }

    @Test
    public void findForwardUrlAcceptsSubPlaylistWithQuery() {
        String master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\n720/index.m3u8?sign=abc\n";
        assertEquals("https://cdn.a.com/path/720/index.m3u8?sign=abc", M3u8PlayPlanner.findForwardUrl(URL, master));
    }

    @Test
    public void findForwardUrlHandlesCrlfLineEndings() {
        String master = "#EXTM3U\r\n#EXT-X-STREAM-INF:BANDWIDTH=800000\r\n720/index.m3u8\r\n";
        assertEquals("https://cdn.a.com/path/720/index.m3u8", M3u8PlayPlanner.findForwardUrl(URL, master));
    }

    @Test
    public void findForwardUrlReturnsEmptyWhenOnlyUriLineIsNotM3u8() {
        String content = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nvideo.mp4\n";
        assertEquals("", M3u8PlayPlanner.findForwardUrl(URL, content));
        // 对照：同一行换成 m3u8 就能找到
        assertEquals("https://cdn.a.com/path/video.m3u8",
                M3u8PlayPlanner.findForwardUrl(URL, content.replace("video.mp4", "video.m3u8")));
    }

    @Test
    public void findForwardUrlReturnsEmptyForMasterWithTwoSubPlaylists() {
        String single = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\n720/index.m3u8\n";
        String master = single + "#EXT-X-STREAM-INF:BANDWIDTH=1600000\n1080/index.m3u8\n";
        assertEquals("", M3u8PlayPlanner.findForwardUrl(URL, master));
        // 对照：只有第一个子列表时能找到它
        assertEquals("https://cdn.a.com/path/720/index.m3u8", M3u8PlayPlanner.findForwardUrl(URL, single));
    }

    @Test
    public void findForwardUrlOnlyLooksAtFirstTenLines() {
        // 子列表在第 12 行：找不到
        assertEquals("", M3u8PlayPlanner.findForwardUrl(URL, masterWithSubPlaylistOnLine(12)));
        // 对照：子列表在第 9 行：能找到
        assertEquals("https://cdn.a.com/path/720/index.m3u8",
                M3u8PlayPlanner.findForwardUrl(URL, masterWithSubPlaylistOnLine(9)));
    }

    /** 第 1 行 #EXTM3U，中间是注释行，子列表 720/index.m3u8 在第 line 行（从 1 数起）。 */
    private static String masterWithSubPlaylistOnLine(int line) {
        StringBuilder master = new StringBuilder("#EXTM3U\n");
        for (int i = 2; i < line; i++) master.append("#EXT-X-COMMENT:").append(i).append('\n');
        return master.append("720/index.m3u8\n").toString();
    }

    // ---------- plan（precache=false） ----------

    @Test
    public void planPlaysLocalM3u8WhenPurifySucceeds() {
        M3u8PlayPlanner.Plan p = M3u8PlayPlanner.plan(URL, URL, MEDIA, null, true, false, PORT,
                fixedPurifier("PURIFIED"), forbiddenRewriter());

        assertEquals(LOCAL, p.playUrl);
        assertTrue(p.setM3u8Content);
        assertEquals("PURIFIED", p.m3u8Content);
        assertFalse(p.precached);
    }

    @Test
    public void planPlaysPlaylistUrlAndStoresNullWhenPurifyReturnsNull() {
        M3u8PlayPlanner.Plan p = M3u8PlayPlanner.plan(URL, URL, MEDIA, null, true, false, PORT,
                fixedPurifier(null), forbiddenRewriter());

        assertEquals(URL, p.playUrl);
        assertTrue(p.setM3u8Content);
        assertNull(p.m3u8Content);
        assertFalse(p.precached);
    }

    @Test
    public void planPassesPlaylistDirectoryAndContentToPurifier() {
        RecordingPurifier purifier = new RecordingPurifier("PURIFIED");

        String playlistUrl = "https://cdn.a.com/hls/720/index.m3u8?sign=a/b";
        M3u8PlayPlanner.plan(playlistUrl, playlistUrl, MEDIA, null, true, false, PORT,
                purifier, forbiddenRewriter());

        assertEquals("https://cdn.a.com/hls/720/index.m3u8?sign=a/", purifier.tsUrlPre);
        assertEquals(MEDIA, purifier.content);
    }

    @Test
    public void planBuildsLocalUrlFromPort() {
        M3u8PlayPlanner.Plan p = M3u8PlayPlanner.plan(URL, URL, MEDIA, null, true, false, 9981,
                fixedPurifier("PURIFIED"), forbiddenRewriter());

        assertEquals("http://127.0.0.1:9981/m3u8", p.playUrl);
    }

    @Test
    public void planPlaysPlaylistUrlWithoutTouchingContentWhenNothingApplies() {
        M3u8PlayPlanner.Plan p = M3u8PlayPlanner.plan(URL, URL, MEDIA, null, false, false, PORT,
                forbiddenPurifier(), forbiddenRewriter());

        assertEquals(URL, p.playUrl);
        assertFalse(p.setM3u8Content);
        assertNull(p.m3u8Content);
        assertFalse(p.precached);
    }

    // ---------- shouldFetch（precache=true） ----------

    @Test
    public void shouldFetchIsTrueWhenOnlyPrecacheOnAndUrlLooksLikeM3u8() {
        assertTrue(M3u8PlayPlanner.shouldFetch(URL, false, true));
        assertTrue(M3u8PlayPlanner.shouldFetch("http://127.0.0.1/proxy?do=py", false, true));
    }

    @Test
    public void shouldFetchIsFalseWhenOnlyPrecacheOnAndUrlIsPlainDirectLink() {
        assertFalse(M3u8PlayPlanner.shouldFetch("https://cdn.a.com/v.mp4", false, true));
        assertFalse(M3u8PlayPlanner.shouldFetch("http://127.0.0.1:9978/proxy?do=py", false, true));
        // 对照：只打开预缓存，m3u8 地址要拉取
        assertTrue(M3u8PlayPlanner.shouldFetch("https://cdn.a.com/v.m3u8", false, true));
    }

    // ---------- plan（precache=true） ----------

    @Test
    public void planRewritesPurifiedContentAndPlaysLocalM3u8() {
        RecordingRewriter rewriter = new RecordingRewriter("REWRITTEN");

        M3u8PlayPlanner.Plan p = M3u8PlayPlanner.plan(URL, URL, MEDIA, null, true, true, PORT,
                fixedPurifier("PURIFIED"), rewriter);

        assertEquals("PURIFIED", rewriter.content);
        assertEquals(LOCAL, p.playUrl);
        assertTrue(p.setM3u8Content);
        assertEquals("REWRITTEN", p.m3u8Content);
        assertTrue(p.precached);
    }

    @Test
    public void planRewritesOriginalContentWhenPurifyFails() {
        RecordingRewriter rewriter = new RecordingRewriter("REWRITTEN");

        M3u8PlayPlanner.Plan p = M3u8PlayPlanner.plan(URL, URL, MEDIA, null, true, true, PORT,
                fixedPurifier(null), rewriter);

        assertEquals(MEDIA, rewriter.content);
        assertEquals(LOCAL, p.playUrl);
        assertEquals("REWRITTEN", p.m3u8Content);
        assertTrue(p.precached);
    }

    @Test
    public void planRewritesOriginalContentWithoutPurifyingWhenPurifyOff() {
        RecordingRewriter rewriter = new RecordingRewriter("REWRITTEN");

        M3u8PlayPlanner.Plan p = M3u8PlayPlanner.plan(URL, URL, MEDIA, null, false, true, PORT,
                forbiddenPurifier(), rewriter);

        assertEquals(MEDIA, rewriter.content);
        assertEquals(LOCAL, p.playUrl);
        assertTrue(p.setM3u8Content);
        assertEquals("REWRITTEN", p.m3u8Content);
        assertTrue(p.precached);
    }

    @Test
    public void planPassesPlaylistUrlHeadersAndPortToRewriter() {
        RecordingRewriter rewriter = new RecordingRewriter("REWRITTEN");
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://site.c.com/");

        M3u8PlayPlanner.Plan p = M3u8PlayPlanner.plan(URL, URL, MEDIA, headers, false, true, 9981,
                forbiddenPurifier(), rewriter);

        assertEquals(URL, rewriter.playlistUrl);
        assertSame(headers, rewriter.headers);
        assertEquals(9981, rewriter.port);
        assertEquals("http://127.0.0.1:9981/m3u8", p.playUrl);
    }

    @Test
    public void planFallsBackToPurifiedLocalM3u8WhenRewriteNotApplicable() {
        M3u8PlayPlanner.Plan p = M3u8PlayPlanner.plan(URL, URL, MEDIA, null, true, true, PORT,
                fixedPurifier("PURIFIED"), new RecordingRewriter(null));

        assertEquals(LOCAL, p.playUrl);
        assertTrue(p.setM3u8Content);
        assertEquals("PURIFIED", p.m3u8Content);
        assertFalse(p.precached);
    }

    @Test
    public void planFallsBackToPlaylistUrlWhenPurifyFailsAndRewriteNotApplicable() {
        M3u8PlayPlanner.Plan p = M3u8PlayPlanner.plan(URL, URL, MEDIA, null, true, true, PORT,
                fixedPurifier(null), new RecordingRewriter(null));

        assertEquals(URL, p.playUrl);
        assertTrue(p.setM3u8Content);
        assertNull(p.m3u8Content);
        assertFalse(p.precached);
    }

    @Test
    public void planFallsBackToPlaylistUrlWhenPurifyOffAndRewriteNotApplicable() {
        M3u8PlayPlanner.Plan p = M3u8PlayPlanner.plan(URL, URL, MEDIA, null, false, true, PORT,
                forbiddenPurifier(), new RecordingRewriter(null));

        assertEquals(URL, p.playUrl);
        assertFalse(p.setM3u8Content);
        assertNull(p.m3u8Content);
        assertFalse(p.precached);
    }

    // ---------- 重定向：请求地址与最终地址不同 ----------

    private static final String REDIRECTED = "https://edge.b.com/v/9/index.m3u8";

    @Test
    public void planRewritesAgainstRedirectedUrlButPurifiesAgainstRequestedDirectory() {
        RecordingPurifier purifier = new RecordingPurifier("PURIFIED");
        RecordingRewriter rewriter = new RecordingRewriter("REWRITTEN");

        M3u8PlayPlanner.Plan p = M3u8PlayPlanner.plan(URL, REDIRECTED, MEDIA, null, true, true, PORT,
                purifier, rewriter);

        assertEquals("https://cdn.a.com/path/", purifier.tsUrlPre);
        assertEquals(REDIRECTED, rewriter.playlistUrl);
        assertEquals(LOCAL, p.playUrl);
        assertTrue(p.precached);
    }

    @Test
    public void planPlaysRequestedUrlWhenRewriteNotApplicableAfterRedirect() {
        M3u8PlayPlanner.Plan p = M3u8PlayPlanner.plan(URL, REDIRECTED, MEDIA, null, false, true, PORT,
                forbiddenPurifier(), new RecordingRewriter(null));

        assertEquals(URL, p.playUrl);
        assertFalse(p.setM3u8Content);
    }

    @Test
    public void planIgnoresRedirectedUrlWhenPrecacheOff() {
        RecordingPurifier purifier = new RecordingPurifier(null);

        M3u8PlayPlanner.Plan p = M3u8PlayPlanner.plan(URL, REDIRECTED, MEDIA, null, true, false, PORT,
                purifier, forbiddenRewriter());

        assertEquals("https://cdn.a.com/path/", purifier.tsUrlPre);
        assertEquals(URL, p.playUrl);
    }

    @Test
    public void finalUrlIsTheUrlOfTheLastRequestAfterRedirects() {
        okhttp3.Response raw = new okhttp3.Response.Builder()
                .request(new okhttp3.Request.Builder().url(REDIRECTED).build())
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .build();

        assertEquals(REDIRECTED, M3u8PlayPlanner.finalUrl(raw, URL));
    }

    @Test
    public void finalUrlFallsBackToRequestedUrlWithoutRawResponse() {
        assertEquals(URL, M3u8PlayPlanner.finalUrl(null, URL));
    }

    // ---------- 测试替身 ----------

    static M3u8PlayPlanner.Purifier fixedPurifier(final String result) {
        return new RecordingPurifier(result);
    }

    static M3u8PlayPlanner.Purifier forbiddenPurifier() {
        return new M3u8PlayPlanner.Purifier() {
            @Override
            public String purify(String tsUrlPre, String content) {
                fail("purifier must not be called");
                return null;
            }
        };
    }

    static M3u8PlayPlanner.Rewriter forbiddenRewriter() {
        return new M3u8PlayPlanner.Rewriter() {
            @Override
            public String rewrite(String playlistUrl, String content, Map<String, String> headers, int port) {
                fail("rewriter must not be called");
                return null;
            }
        };
    }

    static final class RecordingRewriter implements M3u8PlayPlanner.Rewriter {
        private final String result;
        String playlistUrl;
        String content;
        Map<String, String> headers;
        int port;

        RecordingRewriter(String result) {
            this.result = result;
        }

        @Override
        public String rewrite(String playlistUrl, String content, Map<String, String> headers, int port) {
            this.playlistUrl = playlistUrl;
            this.content = content;
            this.headers = headers;
            this.port = port;
            return result;
        }
    }

    static final class RecordingPurifier implements M3u8PlayPlanner.Purifier {
        private final String result;
        String tsUrlPre;
        String content;

        RecordingPurifier(String result) {
            this.result = result;
        }

        @Override
        public String purify(String tsUrlPre, String content) {
            this.tsUrlPre = tsUrlPre;
            this.content = content;
            return result;
        }
    }
}
