package com.github.tvbox.osc.precache;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.AbstractMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HlsPrecacheTest {

    private static final String URL_A = "https://cdn.example.com/a/index.m3u8";
    private static final String URL_B = "https://cdn.example.com/b/index.m3u8";
    private static final String M3U8 = "#EXTM3U\n#EXT-X-TARGETDURATION:10\n"
            + "#EXTINF:10,\nseg0.ts\n#EXTINF:10,\nseg1.ts\n#EXTINF:10,\nseg2.ts\n#EXT-X-ENDLIST\n";
    private static final Pattern PROXY_LINE = Pattern.compile("http://127\\.0\\.0\\.1:9978/hlsseg\\?s=([0-9a-z]+)&i=(\\d+)");

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final FakeSegmentDownloader origin = new FakeSegmentDownloader();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    /** 已提交、还没跑完的下载数，包括 onFinished 里的 pump。 */
    private final AtomicInteger running = new AtomicInteger();
    private final Executor executor = r -> {
        running.incrementAndGet();
        pool.execute(() -> {
            try {
                r.run();
            } finally {
                running.decrementAndGet();
            }
        });
    };
    private HlsPrecache precache;
    private File root;

    @Before
    public void setUp() {
        root = new File(tmp.getRoot(), HlsPrecache.CACHE_DIR_NAME);
        precache = new HlsPrecache(PrecacheConfig.DEFAULT, origin, executor, Runnable::run);
        precache.setCacheRoot(root);
    }

    @After
    public void tearDown() {
        precache.endSession();
        pool.shutdownNow();
    }

    private String rewrite(String url) {
        return precache.rewrite(url, M3U8, null, 9978);
    }

    private static String sessionId(String rewritten) {
        Matcher m = PROXY_LINE.matcher(rewritten);
        assertTrue("rewritten playlist has proxy urls: " + rewritten, m.find());
        return m.group(1);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    private byte[] play(String id, int index) throws IOException {
        SegmentResponse r = precache.openSegment(id, String.valueOf(index));
        assertEquals(200, r.status);
        return readAll(r.body);
    }

    private void awaitIdle() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (running.get() != 0) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("downloads did not finish");
            Thread.sleep(10);
        }
    }

    private File[] sessionDirs() {
        File[] dirs = root.listFiles();
        return dirs == null ? new File[0] : dirs;
    }

    // ---- 改写与代理地址 ----

    @Test
    public void rewritesSegmentsToLoopbackProxyUrlsInOrder() {
        String rewritten = rewrite(URL_A);

        Matcher m = PROXY_LINE.matcher(rewritten);
        String id = null;
        for (int i = 0; i < 3; i++) {
            assertTrue(m.find());
            if (id == null) id = m.group(1);
            assertEquals(id, m.group(1));
            assertEquals(String.valueOf(i), m.group(2));
        }
        assertFalse(m.find());
        assertTrue(rewritten.startsWith("#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10,\nhttp://127.0.0.1:9978"
                + HlsPrecache.SEGMENT_PATH + "?" + HlsPrecache.PARAM_SESSION + "=" + id + "&" + HlsPrecache.PARAM_INDEX + "=0\n"));
    }

    @Test
    public void everyRewriteGetsANewSessionIdAndOldIdBecomes410() {
        String first = sessionId(rewrite(URL_A));
        String second = sessionId(rewrite(URL_A));

        assertNotEquals(first, second);
        assertEquals(410, precache.openSegment(first, "0").status);
        assertEquals(0, origin.totalRequests());
    }

    @Test
    public void returnsNullWithoutCacheRoot() {
        HlsPrecache fresh = new HlsPrecache(PrecacheConfig.DEFAULT, origin, executor, Runnable::run);

        assertNull(fresh.rewrite(URL_A, M3U8, null, 9978));
    }

    @Test
    public void singletonIsCreatedOnce() {
        assertNotNull(HlsPrecache.get());
        assertSame(HlsPrecache.get(), HlsPrecache.get());
    }

    // ---- openSegment 的状态码 ----

    @Test
    public void servesSegmentWithHeadersCopiedAtRewrite() throws IOException {
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://site.example.com/");
        String id = sessionId(precache.rewrite(URL_A, M3U8, headers, 9978));
        headers.put("Referer", "changed later");

        byte[] body = play(id, 0);

        String seg0 = "https://cdn.example.com/a/seg0.ts";
        assertArrayEquals(FakeSegmentDownloader.bytesFor(seg0, 100), body);
        assertEquals("https://site.example.com/", origin.lastHeaders(seg0).get("Referer"));
    }

    @Test
    public void badParametersAre400() {
        String id = sessionId(rewrite(URL_A));
        String[][] cases = {
                {null, "0"}, {id, null}, {null, null}, {id, ""}, {id, "-1"}, {id, "+1"}, {id, " 1"},
                {id, "1.0"}, {id, "abc"}, {id, "0x1"},
        };
        for (String[] c : cases) {
            assertEquals(c[0] + "/" + c[1], 400, precache.openSegment(c[0], c[1]).status);
        }
    }

    @Test
    public void indexBeyondSegmentCountIs404() {
        String id = sessionId(rewrite(URL_A));

        assertEquals(404, precache.openSegment(id, "3").status);
        assertEquals(404, precache.openSegment(id, "99999999999999999999").status);
        assertEquals(0, origin.totalRequests());
    }

    @Test
    public void noSessionOrStaleSessionIs410() {
        assertEquals(410, precache.openSegment("abc", "0").status);
        String id = sessionId(rewrite(URL_A));
        assertEquals(410, precache.openSegment(id + "x", "0").status);

        precache.endSession();

        assertEquals(410, precache.openSegment(id, "0").status);
        assertEquals(0, origin.totalRequests());
    }

    // ---- 会话复用与清空 ----

    @Test
    public void rewritingAnotherPlaylistCancelsDownloadsOfThePreviousSession() throws Exception {
        assertPreviousDownloadsCancelledBy(() -> rewrite(URL_B));
    }

    @Test
    public void rewritingTheSamePlaylistCancelsDownloadsOfThePreviousSession() throws Exception {
        assertPreviousDownloadsCancelledBy(() -> rewrite(URL_A));
    }

    @Test
    public void endSessionCancelsDownloadsOfTheSession() throws Exception {
        assertPreviousDownloadsCancelledBy(() -> precache.endSession());
    }

    /** 会话 A 有 3 个下载卡在 body 上（播放器的 seg0 和预缓存的 seg1、seg2），执行 action 后它们都要被取消。 */
    private void assertPreviousDownloadsCancelledBy(Runnable action) throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        origin.holdDefaultBody(10, gate);
        try {
            String id = sessionId(rewrite(URL_A));
            SegmentResponse first = precache.openSegment(id, "0");
            assertEquals(200, first.status);
            String[] segs = {"https://cdn.example.com/a/seg0.ts", "https://cdn.example.com/a/seg1.ts",
                    "https://cdn.example.com/a/seg2.ts"};
            awaitRequested(segs);

            action.run();

            for (String seg : segs) assertTrue(seg + " cancelled", origin.wasCancelled(seg));
            first.close();
        } finally {
            gate.countDown();
        }
    }

    /** 等到这些地址都已发出请求：下载已经建好 Call，之后的 cancel 一定会落到 Call 上。 */
    private void awaitRequested(String... urls) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        for (String url : urls) {
            while (origin.requestCount(url) == 0) {
                if (System.currentTimeMillis() > deadline) throw new AssertionError(url + " was not requested");
                Thread.sleep(10);
            }
        }
    }

    @Test
    public void samePlaylistUrlReusesDownloadedSegmentsAndKeepsDirectory() throws Exception {
        String first = sessionId(rewrite(URL_A));
        play(first, 0);
        awaitIdle();
        File[] dirs = sessionDirs();
        assertEquals(1, dirs.length);

        String second = sessionId(rewrite(URL_A));
        play(second, 0);

        assertEquals("hit, no second origin request", 1, origin.requestCount("https://cdn.example.com/a/seg0.ts"));
        assertArrayEquals(dirs, sessionDirs());
        assertTrue(dirs[0].isDirectory());
    }

    @Test
    public void differentPlaylistUrlDeletesOldDirectory() throws Exception {
        String first = sessionId(rewrite(URL_A));
        play(first, 0);
        awaitIdle();
        File oldDir = sessionDirs()[0];

        String second = sessionId(rewrite(URL_B));

        assertFalse(oldDir.exists());
        assertEquals(410, precache.openSegment(first, "0").status);
        play(second, 0);
        assertEquals(1, origin.requestCount("https://cdn.example.com/b/seg0.ts"));
    }

    @Test
    public void endSessionDeletesDirectoryAndCanBeRepeated() throws Exception {
        String id = sessionId(rewrite(URL_A));
        play(id, 0);
        awaitIdle();
        File dir = sessionDirs()[0];

        precache.endSession();
        precache.endSession();

        assertFalse(dir.exists());
        assertEquals(410, precache.openSegment(id, "0").status);
    }

    @Test
    public void notApplicablePlaylistEndsPreviousSessionAndDeletesItsDirectory() throws Exception {
        String id = sessionId(rewrite(URL_A));
        play(id, 0);
        awaitIdle();
        File dir = sessionDirs()[0];

        assertNull(precache.rewrite(URL_A, "#EXTM3U\n#EXTINF:10,\nseg0.ts\n", null, 9978));

        assertEquals(410, precache.openSegment(id, "0").status);
        assertFalse(dir.exists());
    }

    @Test
    public void exceptionInsideRewriteReturnsNullAndEndsPreviousSession() throws Exception {
        String id = sessionId(rewrite(URL_A));
        play(id, 0);
        awaitIdle();
        File dir = sessionDirs()[0];
        Map<String, String> broken = new AbstractMap<String, String>() {
            @Override
            public Set<Entry<String, String>> entrySet() {
                throw new IllegalStateException("broken headers");
            }
        };

        assertNull(precache.rewrite(URL_A, M3U8, broken, 9978));

        assertEquals(410, precache.openSegment(id, "0").status);
        assertFalse(dir.exists());
    }
}
