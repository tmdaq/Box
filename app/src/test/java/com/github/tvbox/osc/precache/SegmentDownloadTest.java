package com.github.tvbox.osc.precache;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class SegmentDownloadTest {

    private static final String URL = "https://cdn.example.com/v/seg0.ts";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final FakeSegmentDownloader origin = new FakeSegmentDownloader();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final AtomicInteger finishedCalls = new AtomicInteger();
    private final CountDownLatch finished = new CountDownLatch(1);
    private final HlsSegment segment = new HlsSegment(0, URL, 10_000);
    private File dir;
    private SegmentStore store;

    @Before
    public void setUp() {
        dir = new File(tmp.getRoot(), "s1");
        store = new SegmentStore(dir, "https://cdn.example.com/v/index.m3u8");
    }

    @After
    public void tearDown() {
        pool.shutdownNow();
    }

    private SegmentDownload newDownload(boolean prefetch) {
        return new SegmentDownload(segment, Collections.singletonMap("Referer", "r"), prefetch, origin, store, d -> {
            finishedCalls.incrementAndGet();
            finished.countDown();
        });
    }

    private SegmentDownload start() {
        SegmentDownload d = newDownload(false);
        pool.execute(d);
        return d;
    }

    private void awaitFinished() throws InterruptedException {
        assertTrue("download should finish", finished.await(5, TimeUnit.SECONDS));
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    private static byte[] readExactly(InputStream in, int count) throws IOException {
        byte[] data = new byte[count];
        int off = 0;
        while (off < count) {
            int n = in.read(data, off, count - off);
            if (n < 0) throw new IOException("eof at " + off);
            off += n;
        }
        return data;
    }

    private String[] filesInDir() {
        String[] names = dir.list();
        return names == null ? new String[0] : names;
    }

    @Test
    public void readersFollowTheDownloadAndGetSourceBytesFromOneRequest() throws Exception {
        byte[] source = FakeSegmentDownloader.bytesFor(URL, 200_000);
        CountDownLatch gate = new CountDownLatch(1);
        origin.on(URL).body(source).contentType("video/mp2t").holdBody(70_000, gate);
        SegmentDownload d = start();

        assertEquals(SegmentDownload.State.STREAMING, d.awaitHeaders());
        assertEquals(200, d.httpCode());
        assertEquals(200_000, d.contentLength());
        assertEquals("video/mp2t", d.contentType());
        InputStream first = d.openReader();
        InputStream second = d.openReader();
        byte[] head = readExactly(first, 70_000);
        Future<byte[]> secondAll = pool.submit(() -> readAll(second));
        gate.countDown();
        byte[] firstRest = readAll(first);
        awaitFinished();

        byte[] firstAll = new byte[head.length + firstRest.length];
        System.arraycopy(head, 0, firstAll, 0, head.length);
        System.arraycopy(firstRest, 0, firstAll, head.length, firstRest.length);
        assertArrayEquals(source, firstAll);
        assertArrayEquals(source, secondAll.get(5, TimeUnit.SECONDS));
        assertEquals(1, origin.requestCount(URL));
        assertEquals("r", origin.lastHeaders(URL).get("Referer"));
        assertEquals(SegmentDownload.State.COMPLETED, d.awaitHeaders());
        assertTrue(store.isCommitted(segment.key));
        assertEquals("video/mp2t", store.contentType(segment.key));
        assertEquals(200_000, store.committedBytes());
        assertEquals(1, finishedCalls.get());
    }

    @Test
    public void readerOpenedAfterCompletionReadsCommittedFile() throws Exception {
        byte[] source = FakeSegmentDownloader.bytesFor(URL, 1000);
        origin.on(URL).body(source);
        SegmentDownload d = start();
        awaitFinished();

        assertEquals(SegmentDownload.State.COMPLETED, d.awaitHeaders());
        assertArrayEquals(source, readAll(d.openReader()));
        assertArrayEquals(new String[]{segment.key + ".seg"}, filesInDir());
    }

    @Test
    public void unknownContentLengthIsAccepted() throws Exception {
        origin.on(URL).body(new byte[300]).contentLength(-1);
        SegmentDownload d = start();
        awaitFinished();

        assertEquals(-1, d.contentLength());
        assertEquals(SegmentDownload.State.COMPLETED, d.awaitHeaders());
        assertEquals(300, store.committedBytes());
    }

    @Test
    public void httpErrorIsNotWrittenToDisk() throws Exception {
        origin.on(URL).code(404).body(new byte[50]);
        SegmentDownload d = start();
        awaitFinished();

        assertEquals(SegmentDownload.State.HTTP_ERROR, d.awaitHeaders());
        assertEquals(404, d.httpCode());
        assertFalse(d.isThrottleSignal());
        assertFalse(store.isCommitted(segment.key));
        assertEquals(0, filesInDir().length);
    }

    @Test
    public void throttleSignalsAre403And429And503AndConnectionRefused() throws Exception {
        for (int code : new int[]{403, 429, 503}) {
            origin.on(URL).code(code);
            SegmentDownload d = newDownload(true);
            d.run();
            assertEquals(String.valueOf(code), SegmentDownload.State.HTTP_ERROR, d.awaitHeaders());
            assertTrue(String.valueOf(code), d.isThrottleSignal());
        }
        origin.on(URL).error(new ConnectException("refused"));
        SegmentDownload refused = newDownload(true);
        refused.run();
        assertEquals(SegmentDownload.State.NETWORK_ERROR, refused.awaitHeaders());
        assertTrue(refused.isThrottleSignal());
    }

    @Test
    public void otherIOExceptionIsNetworkErrorWithoutThrottleSignal() throws Exception {
        origin.on(URL).error(new IOException("timeout"));
        SegmentDownload d = start();
        awaitFinished();

        assertEquals(SegmentDownload.State.NETWORK_ERROR, d.awaitHeaders());
        assertFalse(d.isThrottleSignal());
        assertEquals(1, finishedCalls.get());
    }

    @Test
    public void lengthMismatchFailsAndReaderThrows() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        origin.on(URL).body(new byte[100]).contentLength(200).holdBody(50, gate);
        SegmentDownload d = start();
        assertEquals(SegmentDownload.State.STREAMING, d.awaitHeaders());
        InputStream reader = d.openReader();
        gate.countDown();

        try {
            readAll(reader);
            fail("reader should fail");
        } catch (IOException expected) {
        }
        awaitFinished();
        assertEquals(SegmentDownload.State.FAILED, d.awaitHeaders());
        assertFalse(store.isCommitted(segment.key));
        assertEquals(0, filesInDir().length);
    }

    @Test
    public void cancelFailsReadersAndNeverCommits() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        origin.on(URL).body(new byte[1000]).holdBody(500, gate);
        SegmentDownload d = start();
        assertEquals(SegmentDownload.State.STREAMING, d.awaitHeaders());
        InputStream reader = d.openReader();
        readExactly(reader, 500);

        d.cancel();
        d.cancel();
        gate.countDown();

        try {
            readAll(reader);
            fail("reader should fail after cancel");
        } catch (IOException expected) {
        }
        awaitFinished();
        assertEquals(SegmentDownload.State.CANCELLED, d.awaitHeaders());
        assertTrue(origin.wasCancelled(URL));
        assertFalse(store.isCommitted(segment.key));
        assertEquals(0, filesInDir().length);
        assertEquals(1, finishedCalls.get());
    }

    @Test
    public void cancelWhileWaitingForHeaders() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        origin.on(URL).body(new byte[10]).holdHeaders(gate);
        SegmentDownload d = start();
        long deadline = System.currentTimeMillis() + 5000;
        while (origin.requestCount(URL) == 0) {
            assertTrue("request should start", System.currentTimeMillis() < deadline);
            Thread.sleep(5);
        }

        d.cancel();

        assertEquals(SegmentDownload.State.CANCELLED, d.awaitHeaders());
        awaitFinished();
        assertTrue(origin.wasCancelled(URL));
        assertFalse(store.isCommitted(segment.key));
    }

    @Test
    public void cancelBeforeRunSkipsTheRequestButStillReportsFinished() throws Exception {
        SegmentDownload d = newDownload(true);
        d.cancel();

        d.run();

        assertEquals(SegmentDownload.State.CANCELLED, d.awaitHeaders());
        assertEquals(0, origin.totalRequests());
        assertEquals(1, finishedCalls.get());
    }

    @Test
    public void unwritableDirectoryIsDiskError() throws Exception {
        File plainFile = tmp.newFile("not-a-dir");
        store = new SegmentStore(new File(plainFile, "s1"), "u");
        SegmentDownload d = start();
        awaitFinished();

        assertEquals(SegmentDownload.State.DISK_ERROR, d.awaitHeaders());
        assertFalse(d.isThrottleSignal());
    }

    @Test
    public void cancelArrivingRightAfterTheCommitDoesNotUndoIt() throws Exception {
        byte[] source = FakeSegmentDownloader.bytesFor(URL, 1000);
        origin.on(URL).body(source);
        AtomicReference<SegmentDownload> download = new AtomicReference<>();
        // 提交（rename）一完成就到达的取消，比如跳转或会话结束
        store = new SegmentStore(dir, "u") {
            @Override
            void commit(String key, File temp, long size, String contentType) throws IOException {
                super.commit(key, temp, size, contentType);
                download.get().cancel();
            }
        };
        SegmentDownload d = newDownload(true);
        download.set(d);

        d.run();

        assertTrue(store.isCommitted(segment.key));
        assertEquals("a committed download is COMPLETED", SegmentDownload.State.COMPLETED, d.awaitHeaders());
        assertArrayEquals(source, readAll(d.openReader()));
        assertEquals(1, finishedCalls.get());
    }

    @Test
    public void remembersWhetherItIsPrefetch() {
        assertTrue(newDownload(true).isPrefetch());
        assertFalse(newDownload(false).isPrefetch());
    }
}
