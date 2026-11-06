package com.github.tvbox.osc.precache;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class PrecacheSessionTest {

    private static final String BASE = "https://cdn.example.com/v/";
    private static final int SIZE = 100;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final FakeSegmentDownloader origin = new FakeSegmentDownloader();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    /** 提交次数：pump 在 open() 和 onFinished 里同步提交下载，所以提交次数可以精确断言。 */
    private final AtomicInteger submitted = new AtomicInteger();
    /** 已提交、还没跑完的下载数，包括 onFinished 里的 pump。为 0 时会话不会再自己开始新的下载。 */
    private final AtomicInteger running = new AtomicInteger();
    private final Executor executor = r -> {
        submitted.incrementAndGet();
        running.incrementAndGet();
        pool.execute(() -> {
            try {
                r.run();
            } finally {
                running.decrementAndGet();
            }
        });
    };
    private final List<CountDownLatch> gates = new ArrayList<>();
    private SegmentStore store;
    private PrecacheSession session;

    @Before
    public void setUp() {
        origin.defaultSize(SIZE);
        store = new SegmentStore(new File(tmp.getRoot(), "s1"), BASE + "index.m3u8");
    }

    @After
    public void tearDown() {
        for (CountDownLatch gate : gates) gate.countDown();
        if (session != null) session.close();
        pool.shutdownNow();
    }

    private static PrecacheConfig config(int maxConcurrency, long capBytes) {
        return new PrecacheConfig(120_000, 30_000, maxConcurrency, 1, 3, capBytes, SIZE);
    }

    private static String url(int i) {
        return BASE + "seg" + i + ".ts";
    }

    private static HlsPlaylist playlist(int count) {
        StringBuilder m3u8 = new StringBuilder("#EXTM3U\n#EXT-X-TARGETDURATION:10\n");
        for (int i = 0; i < count; i++) m3u8.append("#EXTINF:10,\nseg").append(i).append(".ts\n");
        return HlsPlaylist.parse(BASE + "index.m3u8", m3u8.append("#EXT-X-ENDLIST\n").toString());
    }

    private PrecacheSession newSession(int segments, PrecacheConfig config) {
        session = new PrecacheSession("abc", playlist(segments), Collections.singletonMap("Referer", "r"), store,
                config, origin, executor);
        return session;
    }

    private PrecacheSession newSession(int segments) {
        return newSession(segments, config(3, Long.MAX_VALUE));
    }

    private CountDownLatch gate() {
        CountDownLatch gate = new CountDownLatch(1);
        gates.add(gate);
        return gate;
    }

    private static String key(int i) {
        return new HlsSegment(i, url(i), 0).key;
    }

    private static byte[] bytes(int i) {
        return FakeSegmentDownloader.bytesFor(url(i), SIZE);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    private byte[] play(int index) throws IOException {
        SegmentResponse r = session.open(index);
        assertEquals("segment " + index, 200, r.status);
        return readAll(r.body);
    }

    private Set<String> keys(int from, int to) {
        Set<String> keys = new HashSet<>();
        for (int i = from; i <= to; i++) keys.add(key(i));
        return keys;
    }

    private interface Condition {
        boolean met();
    }

    private static void eventually(String what, Condition condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.met()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(10);
        }
    }

    /** 等到这些分片都已向源站发出请求：下载已经建好 Call，之后的 cancel 一定会落到 Call 上。 */
    private void awaitRequested(int... indexes) throws InterruptedException {
        for (int i : indexes) eventually("seg" + i + " requested", () -> origin.requestCount(url(i)) == 1);
    }

    /** 等到所有下载都已跑完（包括 onFinished 里的 pump）。 */
    private void awaitIdle() throws InterruptedException {
        eventually("idle", () -> running.get() == 0);
    }

    // ---- 命中与未命中 ----

    @Test
    public void missDownloadsFromOriginThenHitServesFromDiskWithoutOrigin() throws Exception {
        newSession(5);

        assertArrayEquals(bytes(0), play(0));
        eventually("seg0 committed", () -> store.isCommitted(key(0)));
        SegmentResponse hit = session.open(0);

        assertEquals(200, hit.status);
        assertEquals(SIZE, hit.length);
        assertEquals("video/mp2t", hit.contentType);
        assertArrayEquals(bytes(0), readAll(hit.body));
        assertEquals(1, origin.requestCount(url(0)));
        assertEquals("r", origin.lastHeaders(url(0)).get("Referer"));
    }

    @Test
    public void missStreamsBytesBeforeTheDownloadFinishes() throws Exception {
        CountDownLatch gate = gate();
        byte[] source = FakeSegmentDownloader.bytesFor(url(0), 1000);
        origin.on(url(0)).body(source).contentType("video/MP2T").holdBody(400, gate);
        newSession(5);

        SegmentResponse r = session.open(0);

        assertEquals(200, r.status);
        assertEquals(1000, r.length);
        assertEquals("video/MP2T", r.contentType);
        byte[] head = new byte[400];
        int off = 0;
        while (off < 400) off += r.body.read(head, off, 400 - off);
        gate.countDown();
        byte[] rest = readAll(r.body);
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        all.write(head);
        all.write(rest);
        assertArrayEquals(source, all.toByteArray());
    }

    @Test
    public void unknownContentLengthIsReportedAsUnknown() throws Exception {
        origin.on(url(0)).body(bytes(0)).contentLength(-1);
        newSession(5);

        SegmentResponse r = session.open(0);

        assertEquals(SegmentResponse.UNKNOWN_LENGTH, r.length);
        assertArrayEquals(bytes(0), readAll(r.body));
    }

    @Test
    public void concurrentRequestsForOneSegmentShareOneOriginRequest() throws Exception {
        CountDownLatch gate = gate();
        origin.on(url(0)).body(bytes(0)).holdHeaders(gate);
        newSession(5);

        Future<byte[]> first = pool.submit(() -> play(0));
        Future<byte[]> second = pool.submit(() -> play(0));
        eventually("origin requested", () -> origin.requestCount(url(0)) == 1);
        gate.countDown();

        assertArrayEquals(bytes(0), first.get(5, TimeUnit.SECONDS));
        assertArrayEquals(bytes(0), second.get(5, TimeUnit.SECONDS));
        assertEquals(1, origin.requestCount(url(0)));
    }

    @Test
    public void playerRequestJoinsARunningPrefetch() throws Exception {
        CountDownLatch gate = gate();
        origin.on(url(1)).body(bytes(1)).holdBody(10, gate);
        newSession(5);
        play(0);
        eventually("seg1 prefetch started", () -> origin.requestCount(url(1)) == 1);

        // 预缓存卡在第 10 个字节上，不可能已经提交：播放器只能跟读这次下载，先拿到前 10 个字节，再放行
        Future<SegmentResponse> seg1 = pool.submit(() -> session.open(1));
        SegmentResponse r = seg1.get(5, TimeUnit.SECONDS);
        assertEquals(200, r.status);
        byte[] head = new byte[10];
        int off = 0;
        while (off < head.length) off += r.body.read(head, off, head.length - off);
        gate.countDown();
        byte[] rest = readAll(r.body);

        ByteArrayOutputStream all = new ByteArrayOutputStream();
        all.write(head);
        all.write(rest);
        assertArrayEquals(bytes(1), all.toByteArray());
        assertEquals(1, origin.requestCount(url(1)));
    }

    @Test
    public void prefetchCompletingWhileThePlayerLooksItUpIsServedWithoutASecondRequest() throws Exception {
        CountDownLatch gate = gate();
        origin.on(url(1)).body(bytes(1)).holdBody(10, gate);
        AtomicInteger lookups = new AtomicInteger();
        // 播放器查磁盘时还没提交；查完、找到正在进行的下载之前，这个预缓存下载完成并提交
        store = new SegmentStore(new File(tmp.getRoot(), "s1"), BASE + "index.m3u8") {
            @Override
            File committedFile(String key) {
                File file = super.committedFile(key);
                if (key.equals(key(1)) && lookups.getAndIncrement() == 0) {
                    gate.countDown();
                    awaitQuietly(() -> isCommitted(key(1)));
                }
                return file;
            }
        };
        newSession(5);
        play(0);
        eventually("seg1 prefetch started", () -> origin.requestCount(url(1)) == 1);

        assertArrayEquals(bytes(1), play(1));

        assertEquals("served from the prefetch, not a new download", 1, origin.requestCount(url(1)));
    }

    /** 在钩子里等条件成立；超时由调用它的测试的断言暴露。 */
    private static void awaitQuietly(Condition condition) {
        try {
            eventually("hook condition", condition);
        } catch (InterruptedException | AssertionError ignored) {
            // 钩子不抛异常
        }
    }

    @Test
    public void externallyDeletedFileIsDownloadedAgain() throws Exception {
        newSession(5);
        play(0);
        eventually("seg0 committed", () -> store.isCommitted(key(0)));
        assertTrue(store.committedFile(key(0)).delete());

        assertArrayEquals(bytes(0), play(0));
        assertEquals(2, origin.requestCount(url(0)));
    }

    @Test
    public void rejectedDownloadDoesNotLeaveLaterRequestsWaitingForever() throws Exception {
        AtomicInteger rejections = new AtomicInteger(1);
        Executor rejectsFirst = r -> {
            if (rejections.getAndDecrement() > 0) throw new RejectedExecutionException("pool is full");
            executor.execute(r);
        };
        session = new PrecacheSession("abc", playlist(5), null, store, config(3, Long.MAX_VALUE), origin, rejectsFirst);

        Future<SegmentResponse> first = pool.submit(() -> session.open(0));
        try {
            first.get(5, TimeUnit.SECONDS).close();
        } catch (ExecutionException ignored) {
            // 第一次请求怎么失败都可以，只要不卡住
        }
        Future<byte[]> again = pool.submit(() -> play(0));

        assertArrayEquals(bytes(0), again.get(5, TimeUnit.SECONDS));
    }

    // ---- 源站错误 ----

    @Test
    public void originHttpErrorIsReturnedAsIsAndNotCached() throws Exception {
        origin.on(url(0)).code(404).body(new byte[10]);
        newSession(5);

        SegmentResponse r = session.open(0);

        assertEquals(404, r.status);
        assertNull(r.body);
        assertEquals(0, r.length);
        assertEquals("text/plain", r.contentType);
        awaitIdle();
        assertFalse(store.isCommitted(key(0)));
    }

    @Test
    public void unreachableOriginIs502() {
        origin.on(url(0)).error(new IOException("timeout"));
        newSession(5);

        assertEquals(502, session.open(0).status);
    }

    @Test
    public void failedPrefetchIsNotRetriedByPumpButPlayerRequestRetries() throws Exception {
        origin.on(url(1)).code(500);
        newSession(5);
        play(0);
        awaitIdle();
        assertEquals(1, origin.requestCount(url(1)));
        play(0);
        awaitIdle();
        assertEquals(1, origin.requestCount(url(1)));

        origin.on(url(1)).body(bytes(1));
        assertArrayEquals(bytes(1), play(1));
        assertEquals(2, origin.requestCount(url(1)));
    }

    // ---- 降级 ----

    @Test
    public void diskFailureDegradesToPureForwarding() throws Exception {
        File plainFile = tmp.newFile("not-a-dir");
        store = new SegmentStore(new File(plainFile, "s1"), BASE + "index.m3u8");
        newSession(20);

        assertArrayEquals(bytes(0), play(0));
        awaitIdle();
        int before = submitted.get();
        int requestsBefore = origin.requestCount(url(10));

        assertArrayEquals(bytes(10), play(10));
        assertEquals(404, statusAfterPlan(11, 404));

        assertEquals("no download is scheduled after degrading", before, submitted.get());
        assertEquals(requestsBefore + 1, origin.requestCount(url(10)));
        assertTrue(store.committedKeys().isEmpty());
    }

    @Test
    public void writeFailureOfAPrefetchDegradesToPureForwarding() throws Exception {
        store = failingStore(key(1), false);
        assertDegradesAfterPrefetchOfSegment1Fails();
    }

    @Test
    public void commitFailureOfAPrefetchDegradesToPureForwarding() throws Exception {
        store = failingStore(key(1), true);
        assertDegradesAfterPrefetchOfSegment1Fails();
    }

    /**
     * 只有 failingKey 的分片写盘失败：临时文件是个目录，打开写入流就失败；或者 rename 提交失败。
     * 其余分片正常读写。
     */
    private SegmentStore failingStore(String failingKey, boolean failCommit) {
        return new SegmentStore(new File(tmp.getRoot(), "s1"), BASE + "index.m3u8") {
            @Override
            File newTempFile(String key) throws IOException {
                if (failCommit || !key.equals(failingKey)) return super.newTempFile(key);
                File dir = new File(tmp.getRoot(), "temp-is-a-directory");
                assertTrue(dir.mkdirs() || dir.isDirectory());
                return dir;
            }

            @Override
            void commit(String key, File temp, long size, String contentType) throws IOException {
                if (failCommit && key.equals(failingKey)) throw new IOException("rename failed");
                super.commit(key, temp, size, contentType);
            }
        };
    }

    private void assertDegradesAfterPrefetchOfSegment1Fails() throws Exception {
        newSession(20);
        assertArrayEquals(bytes(0), play(0));
        awaitIdle();
        assertEquals("prefetch of seg1 was attempted", 1, origin.requestCount(url(1)));
        int before = submitted.get();

        // seg15 在第 0 片的窗口之外，降级前不可能被预缓存
        assertArrayEquals(bytes(15), play(15));
        assertArrayEquals(bytes(0), play(0));

        assertEquals("no download is scheduled after degrading", before, submitted.get());
        assertEquals("forwarded to origin", 1, origin.requestCount(url(15)));
        assertFalse("forwarding does not write to disk", store.isCommitted(key(15)));
        assertEquals("committed segments are not read any more", 2, origin.requestCount(url(0)));
    }

    private int statusAfterPlan(int index, int code) {
        origin.on(url(index)).code(code);
        return session.open(index).status;
    }

    @Test
    public void unreadableCommittedFileDegradesToPureForwarding() throws Exception {
        newSession(20, config(3, Long.MAX_VALUE));
        play(0);
        awaitIdle();
        File committed = store.committedFile(key(1));
        assumeTrue("needs a file system that honours permissions", committed.setReadable(false) && !committed.canRead());
        int before = submitted.get();

        assertArrayEquals(bytes(1), play(1));

        assertEquals(2, origin.requestCount(url(1)));
        awaitIdle();
        assertEquals("no download is scheduled after degrading", before, submitted.get());
    }

    // ---- 窗口与并发 ----

    @Test
    public void prefetchesWindowWithAtMostThreeConcurrentDownloadsAndStopsWhenFull() throws Exception {
        CountDownLatch gate = gate();
        origin.holdDefaultBody(10, gate);
        newSession(40);

        SegmentResponse first = session.open(0);
        eventually("3 downloads in flight", () -> origin.inFlight() == 3);

        assertEquals(new HashSet<>(java.util.Arrays.asList(url(0), url(1), url(2))),
                new HashSet<>(origin.requestedUrls()));
        gate.countDown();
        readAll(first.body);
        awaitIdle();

        assertEquals(keys(0, 11), store.committedKeys());
        assertEquals("stops once the window is full", 12, origin.totalRequests());
        assertEquals(12, submitted.get());
        assertEquals(3, origin.maxInFlight());
    }

    @Test
    public void prefetchStartsOnlyAfterTheFirstRequest() throws Exception {
        newSession(40);

        assertEquals("no prefetch before the first request", 0, submitted.get());
        assertEquals(0, origin.totalRequests());

        play(5);
        // 对照：请求过第一个分片之后，从它的下一个分片开始预取
        eventually("prefetch after the first request", () -> origin.requestCount(url(6)) == 1);
        assertEquals("prefetch does not start from segment 0", 0, origin.requestCount(url(0)));
    }

    @Test
    public void sequentialPlaybackMovesWindowAndEvictsBehindRange() throws Exception {
        newSession(40);

        for (int i = 0; i <= 10; i++) {
            play(i);
            awaitIdle();
        }

        assertEquals(keys(7, 21), store.committedKeys());
        play(8);
        assertEquals("behind segment is a hit", 1, origin.requestCount(url(8)));
    }

    @Test
    public void jumpCancelsPrefetchOutsideNewWindowButKeepsPlayerDownload() throws Exception {
        CountDownLatch gate = gate();
        origin.holdDefaultBody(10, gate);
        newSession(100);
        SegmentResponse first = session.open(0);
        awaitRequested(0, 1, 2);

        SegmentResponse jumped = session.open(50);

        assertTrue(origin.wasCancelled(url(1)));
        assertTrue(origin.wasCancelled(url(2)));
        assertFalse(origin.wasCancelled(url(0)));
        eventually("prefetch from new anchor", () -> origin.requestCount(url(51)) == 1);
        gate.countDown();
        assertArrayEquals(bytes(0), readAll(first.body));
        assertArrayEquals(bytes(50), readAll(jumped.body));
    }

    @Test
    public void jumpDeletesCommittedSegmentsOutsideWindowAndBehindRange() throws Exception {
        newSession(100);
        play(0);
        awaitIdle();
        assertEquals(keys(0, 11), store.committedKeys());

        play(50);
        awaitIdle();

        assertEquals(keys(50, 61), store.committedKeys());
    }

    @Test
    public void evictsKeysThatAreNotInTheCurrentPlaylist() throws Exception {
        File temp = store.newTempFile("stale");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(new byte[5]);
        }
        store.commit("stale", temp, 5, null);
        newSession(5);

        play(0);

        assertFalse(store.isCommitted("stale"));
    }

    // ---- 磁盘上限 ----

    @Test
    public void capEvictsBehindSegmentsFirstThenShortensWindow() throws Exception {
        newSession(40, config(10, 350));

        play(0);
        awaitIdle();
        assertEquals("window shrinks to what fits", keys(0, 2), store.committedKeys());

        play(1);
        awaitIdle();
        assertEquals("behind segment evicted to make room", keys(1, 3), store.committedKeys());
        assertEquals(0, origin.requestCount(url(4)));
    }

    @Test
    public void playerMissIsDownloadedAndCachedEvenOverTheCap() throws Exception {
        // 上限比一个分片还小：预缓存一个都放不下
        newSession(40, config(3, 50));

        assertArrayEquals(bytes(0), play(0));
        awaitIdle();
        assertEquals("a player miss is still written to disk", keys(0, 0), store.committedKeys());

        // 已经超出上限，再来一个未命中照样回源、写盘（seg0 作为回看分片被淘汰，给上限腾地方）
        assertArrayEquals(bytes(1), play(1));
        awaitIdle();
        assertTrue(store.isCommitted(key(1)));
        assertEquals(1, origin.requestCount(url(1)));
        assertEquals("no prefetch over the cap", 0, origin.requestCount(url(2)));
    }

    // ---- 限流 ----

    @Test
    public void threeThrottleSignalsDropConcurrencyToOneForGood() throws Exception {
        CountDownLatch playerGate = gate();
        CountDownLatch prefetchGate = gate();
        origin.on(url(0)).body(bytes(0)).holdBody(10, playerGate);
        origin.on(url(1)).code(429);
        origin.on(url(2)).code(503);
        origin.on(url(3)).error(new ConnectException("refused"));
        // 其余分片都卡在 body 上，结束的只有 3 个限流信号，信号到达的先后不影响结果
        origin.holdDefaultBody(10, prefetchGate);
        newSession(40);

        SegmentResponse first = session.open(0);
        // 前两个信号各空出一个名额，补上 seg3、seg4；第 3 个信号之后降到 1 个，不再补。
        // running 在 seg1–3 都跑完之前一直是 3，所以等到 2 就说明 3 个信号都已到达。
        eventually("three throttle signals arrived", () -> running.get() == 2);
        assertEquals(1, origin.requestCount(url(4)));
        assertEquals("throttled before the window fills", 0, origin.requestCount(url(5)));

        prefetchGate.countDown();
        eventually("seg4 committed", () -> store.isCommitted(key(4)));
        origin.resetMaxInFlight();
        playerGate.countDown();
        readAll(first.body);
        awaitIdle();
        assertEquals(1, origin.maxInFlight());
        assertTrue(store.committedKeys().containsAll(keys(4, 11)));

        origin.resetMaxInFlight();
        readAll(session.open(4).body);
        awaitIdle();
        assertEquals(1, origin.requestCount(url(15)));
        assertEquals("does not recover", 1, origin.maxInFlight());
    }

    // ---- 关闭 ----

    @Test
    public void closedSessionReturns410AndCancelsDownloads() throws Exception {
        CountDownLatch gate = gate();
        origin.holdDefaultBody(10, gate);
        newSession(40);
        SegmentResponse first = session.open(0);
        awaitRequested(0, 1, 2);

        session.close();
        session.close();

        assertTrue(origin.wasCancelled(url(0)));
        assertTrue(origin.wasCancelled(url(1)));
        assertEquals(410, session.open(3).status);
        first.close();
        awaitIdle();
        assertEquals(3, origin.totalRequests());
    }

    // ---- Content-Type ----

    @Test
    public void contentTypeFallsBackToExtension() {
        Object[][] cases = {
                {"https://a/b/seg.ts", "video/mp2t"},
                {"https://a/b/SEG.TS?x=1.mp4", "video/mp2t"},
                {"https://a/b/seg.m4s", "video/mp4"},
                {"https://a/b/seg.mp4", "video/mp4"},
                {"https://a/b/seg.m4v", "video/mp4"},
                {"https://a/b/seg.cmfv", "video/mp4"},
                {"https://a/b/seg.aac", "audio/aac"},
                {"https://a/b/sub.vtt#t", "text/vtt"},
                {"https://a/b/seg", "application/octet-stream"},
                {"https://a.ts/b/seg.jpg", "application/octet-stream"},
        };
        for (Object[] c : cases) {
            assertEquals((String) c[0], c[1], PrecacheSession.contentType(null, (String) c[0]));
        }
        assertEquals("video/x", PrecacheSession.contentType("video/x", "https://a/b/seg.ts"));
    }
}
