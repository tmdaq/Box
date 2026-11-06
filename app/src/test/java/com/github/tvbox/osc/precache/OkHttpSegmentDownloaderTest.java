package com.github.tvbox.osc.precache;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;

public class OkHttpSegmentDownloaderTest {

    private final OkHttpClient client = new OkHttpClient();
    private final SegmentDownloader downloader = new OkHttpSegmentDownloader(() -> client);
    private MockWebServer server;

    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @After
    public void tearDown() throws IOException {
        server.shutdown();
    }

    private String url() {
        return server.url("/seg0.ts").toString();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }

    @Test
    public void sendsTrimmedHeadersAndDropsHopByHopAndEncodingHeaders() throws Exception {
        server.enqueue(new MockResponse().setBody("x"));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(" User-Agent ", "  Mozilla/5.0 (TV)  ");
        headers.put("Referer", "https://site.example.com/ \t");
        headers.put("accept-encoding", "identity");
        headers.put("RANGE", "bytes=100-");
        headers.put("Host", "evil.example.com");
        headers.put("Connection", "close");
        headers.put("Content-Length", "5");
        headers.put("  ", "blank name");
        headers.put("X-Bad", "line\nbreak");
        headers.put("X-Null", null);
        headers.put("X-Kept", "ok");

        downloader.newCall(url(), headers).execute().close();

        RecordedRequest request = server.takeRequest();
        assertEquals("Mozilla/5.0 (TV)", request.getHeader("User-Agent"));
        assertEquals("https://site.example.com/", request.getHeader("Referer"));
        assertEquals("gzip", request.getHeader("Accept-Encoding"));
        assertNull(request.getHeader("Range"));
        assertEquals(server.getHostName() + ":" + server.getPort(), request.getHeader("Host"));
        assertEquals("Keep-Alive", request.getHeader("Connection"));
        assertNull(request.getHeader("Content-Length"));
        assertNull(request.getHeader("X-Bad"));
        assertNull(request.getHeader("X-Null"));
        assertEquals("ok", request.getHeader("X-Kept"));
    }

    @Test
    public void acceptsNullHeaders() throws Exception {
        server.enqueue(new MockResponse().setBody("x"));

        try (SegmentDownloader.Source source = downloader.newCall(url(), null).execute()) {
            assertEquals(200, source.code);
        }
    }

    @Test
    public void returnsBodyLengthAndContentTypeOn2xx() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(206).setHeader("Content-Type", "video/mp2t").setBody("abcdef"));

        try (SegmentDownloader.Source source = downloader.newCall(url(), null).execute()) {
            assertEquals(206, source.code);
            assertEquals(6, source.contentLength);
            assertEquals("video/mp2t", source.contentType);
            assertArrayEquals("abcdef".getBytes("UTF-8"), readAll(source.body));
        }
    }

    @Test
    public void contentLengthIsUnknownForChunkedBodyAndContentTypeMayBeNull() throws Exception {
        server.enqueue(new MockResponse().setChunkedBody("abcdefghij", 3).removeHeader("Content-Type"));

        try (SegmentDownloader.Source source = downloader.newCall(url(), null).execute()) {
            assertEquals(-1, source.contentLength);
            assertNull(source.contentType);
            assertArrayEquals("abcdefghij".getBytes("UTF-8"), readAll(source.body));
        }
    }

    @Test
    public void returnsNon2xxCodeWithoutThrowing() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(403).setBody("forbidden"));

        SegmentDownloader.Source source = downloader.newCall(url(), null).execute();

        assertEquals(403, source.code);
        source.close();
        source.close();
    }

    @Test
    public void decompressesGzipTransparently() throws Exception {
        byte[] plain = "segment bytes segment bytes segment bytes".getBytes("UTF-8");
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gz)) {
            out.write(plain);
        }
        server.enqueue(new MockResponse().setHeader("Content-Encoding", "gzip").setBody(new Buffer().write(gz.toByteArray())));

        try (SegmentDownloader.Source source = downloader.newCall(url(), null).execute()) {
            assertArrayEquals(plain, readAll(source.body));
            assertEquals(-1, source.contentLength);
        }
    }

    @Test
    public void cancelMakesBlockedExecuteFail() throws Exception {
        server.enqueue(new MockResponse().setHeadersDelay(2, TimeUnit.SECONDS).setBody("late"));
        SegmentDownloader.Call call = downloader.newCall(url(), null);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        new Thread(() -> {
            try {
                call.execute().close();
            } catch (Throwable t) {
                thrown.set(t);
            }
            done.countDown();
        }).start();
        server.takeRequest(5, TimeUnit.SECONDS);

        call.cancel();
        call.cancel();

        assertTrue("execute should fail before the delayed headers arrive", done.await(1, TimeUnit.SECONDS));
        assertTrue(String.valueOf(thrown.get()), thrown.get() instanceof IOException);
    }

    @Test
    public void cancelMakesBodyReadFail() throws Exception {
        server.enqueue(new MockResponse().setBody(new Buffer().write(new byte[256 * 1024]))
                .throttleBody(16 * 1024, 200, TimeUnit.MILLISECONDS));
        SegmentDownloader.Call call = downloader.newCall(url(), null);
        SegmentDownloader.Source source = call.execute();

        call.cancel();

        try {
            readAll(source.body);
            fail("read should fail after cancel");
        } catch (IOException expected) {
        } finally {
            source.close();
        }
    }

    @Test(expected = IOException.class)
    public void executeAfterCancelFails() throws Exception {
        server.enqueue(new MockResponse().setBody("x"));
        SegmentDownloader.Call call = downloader.newCall(url(), null);
        call.cancel();

        call.execute();
    }

    @Test(expected = ConnectException.class)
    public void connectionRefusedThrowsConnectException() throws Exception {
        String url = url();
        server.shutdown();

        downloader.newCall(url, null).execute();
    }

    @Test
    public void invalidUrlFailsAtExecuteWithIOException() {
        SegmentDownloader.Call call = downloader.newCall("http://", null);

        try {
            call.execute();
            fail("expected IOException");
        } catch (IOException expected) {
        }
        call.cancel();
    }
}
