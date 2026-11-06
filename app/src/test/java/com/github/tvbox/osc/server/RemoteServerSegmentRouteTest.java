package com.github.tvbox.osc.server;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import com.github.tvbox.osc.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Method;
import fi.iki.elonen.NanoHTTPD.Response;

/**
 * 分片代理路由 GET /hlsseg：loopback 判定、测试接缝 openSegment、异常兜底，
 * 以及真实 HTTP 字节流上的响应头和 body 关闭行为。见 design.md D1、D2、D5、D6、D7。
 */
// 用空 Application 代替 App，避免测试启动时初始化整个应用
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class RemoteServerSegmentRouteTest {

    private static final String TS = "video/mp2t";
    private static final Map<String, String> ACCEPT_GZIP = Collections.singletonMap("Accept-Encoding", "gzip");
    private static final String SEGMENT_URL = "/hlsseg?s=abc&i=3";
    private static final long WAIT_MS = 5000;

    private SeamServer server;

    @Before
    public void setUp() {
        server = new SeamServer();
    }

    @After
    public void tearDown() {
        server.stop();
    }

    // ---- mock session ----

    @Test
    public void loopbackRequestsReachTheSeamWithRawParams() {
        for (String ip : Arrays.asList("127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "::ffff:127.0.0.1")) {
            SeamServer loopbackServer = new SeamServer();
            Response seamResponse = SegmentProxyResponse.empty(200);
            loopbackServer.handler = (s, i) -> seamResponse;

            Response response = loopbackServer.serve(segmentSession(Method.GET, ip, params("abc", "3")));

            assertSame(ip, seamResponse, response);
            assertEquals(ip, 1, loopbackServer.calls.size());
            assertArrayEquals(ip, new String[]{"abc", "3"}, loopbackServer.calls.get(0));
        }
    }

    @Test
    public void nonLoopbackRequestsGet403WithoutReachingTheSeam() throws IOException {
        for (String ip : Arrays.asList("192.168.1.20", "10.0.0.2", "127.0.0.2", "", null)) {
            Response response = server.serve(segmentSession(Method.GET, ip, params("abc", "3")));

            assertEquals(ip, 403, response.getStatus().getRequestStatus());
            assertEquals(ip, NanoHTTPD.MIME_PLAINTEXT, response.getMimeType());
            assertEquals(ip, 0, readAll(response.getData()).length);
        }
        assertTrue(server.calls.isEmpty());
    }

    @Test
    public void forgedLoopbackHeadersFromLanStillGet403WithoutReachingTheSeam() {
        Map<String, String> headers = new HashMap<>();
        headers.put("x-forwarded-for", "127.0.0.1");
        headers.put("remote-addr", "127.0.0.1");

        Response response = server.serve(segmentSession(Method.GET, "192.168.1.20", params("abc", "3"), headers));

        assertEquals(403, response.getStatus().getRequestStatus());
        assertTrue(server.calls.isEmpty());
    }

    @Test
    public void missingParamsReachTheSeamAsNulls() {
        server.serve(segmentSession(Method.GET, "127.0.0.1", new HashMap<String, String>()));

        assertEquals(1, server.calls.size());
        assertArrayEquals(new String[]{null, null}, server.calls.get(0));
    }

    @Test
    public void seamFailureBecomes500() {
        server.handler = (s, i) -> {
            throw new RuntimeException("boom");
        };

        Response response = server.serve(segmentSession(Method.GET, "127.0.0.1", params("abc", "3")));

        assertEquals(500, response.getStatus().getRequestStatus());
    }

    @Test
    public void postFallsBackToDefaultPageWithoutReachingTheSeam() throws IOException {
        Response response = server.serve(segmentSession(Method.POST, "127.0.0.1", params("abc", "3")));

        assertDefaultPage(response);
        assertTrue(server.calls.isEmpty());
    }

    // ---- 真实 socket，请求都带 Accept-Encoding: gzip ----

    @Test
    public void knownLengthIsSentAsFixedLengthWithoutEncoding() throws IOException {
        byte[] segment = randomBytes(4096);
        server.handler = (s, i) -> SegmentProxyResponse.create(200, TS, new ByteArrayInputStream(segment), segment.length, null);

        RawHttpClient.Response response = getSegment();

        assertEquals("HTTP/1.1 200 OK", response.statusLine);
        assertEquals(String.valueOf(segment.length), response.header("Content-Length"));
        assertNull(response.header("Transfer-Encoding"));
        assertNull(response.header("Content-Encoding"));
        assertArrayEquals(segment, response.body);
    }

    @Test
    public void unknownLengthIsSentChunkedWithoutEncoding() throws IOException {
        byte[] segment = randomBytes(100_000);
        server.handler = (s, i) -> SegmentProxyResponse.create(200, TS, new ByteArrayInputStream(segment), -1, null);

        RawHttpClient.Response response = getSegment();

        assertEquals("chunked", response.header("Transfer-Encoding"));
        assertNull(response.header("Content-Encoding"));
        assertArrayEquals(segment, response.body);
    }

    @Test
    public void textSegmentIsNotGzipped() throws IOException {
        byte[] segment = "WEBVTT\n\n00:00.000 --> 00:01.000\nhello\n".getBytes(StandardCharsets.UTF_8);
        server.handler = (s, i) -> SegmentProxyResponse.create(200, NanoHTTPD.MIME_PLAINTEXT, new ByteArrayInputStream(segment), segment.length, null);

        RawHttpClient.Response response = getSegment();

        assertNull(response.header("Content-Encoding"));
        assertArrayEquals(segment, response.body);
    }

    @Test
    public void statusOutsideNanoHttpdEnumIsWrittenAsIs() throws IOException {
        server.handler = (s, i) -> SegmentProxyResponse.create(502, NanoHTTPD.MIME_PLAINTEXT, null, 0, null);

        RawHttpClient.Response response = getSegment();

        assertEquals("HTTP/1.1 502 Bad Gateway", response.statusLine);
        assertEquals("0", response.header("Content-Length"));
        assertNull(response.header("Content-Encoding"));
    }

    @Test
    public void nullBodyIsSentAsZeroLengthWhateverLengthIsGiven() throws IOException {
        for (long length : new long[]{123, -1}) {
            SeamServer nullBodyServer = new SeamServer();
            nullBodyServer.handler = (s, i) -> SegmentProxyResponse.create(502, NanoHTTPD.MIME_PLAINTEXT, null, length, null);
            nullBodyServer.start();
            try {
                RawHttpClient.Response response = RawHttpClient.get(nullBodyServer.getListeningPort(), SEGMENT_URL, ACCEPT_GZIP);

                assertEquals("length " + length, "0", response.header("Content-Length"));
                assertNull("length " + length, response.header("Transfer-Encoding"));
                assertEquals("length " + length, 0, response.body.length);
            } finally {
                nullBodyServer.stop();
            }
        }
    }

    @Test
    public void resourceIsClosedOnceAfterSending() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        byte[] segment = randomBytes(1024);
        server.handler = (s, i) -> SegmentProxyResponse.create(200, TS, new ByteArrayInputStream(segment), segment.length, closes::incrementAndGet);

        getSegment();

        awaitTrue(() -> closes.get() > 0);
        assertEquals(1, closes.get());
    }

    @Test
    public void resourceIsClosedWhenClientDisconnectsMidway() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        server.handler = (s, i) -> SegmentProxyResponse.create(200, TS, new EndlessStream(), -1, closes::incrementAndGet);
        server.start();

        try (Socket socket = new Socket("127.0.0.1", server.getListeningPort())) {
            socket.setSoTimeout((int) WAIT_MS);
            RawHttpClient.writeRequest(socket.getOutputStream(), SEGMENT_URL, ACCEPT_GZIP);
            InputStream in = socket.getInputStream();
            assertEquals("HTTP/1.1 200 OK", RawHttpClient.readLine(in).trim());
            while (!RawHttpClient.readLine(in).isEmpty()) {
                // 读完响应头就断开
            }
        }

        awaitTrue(() -> closes.get() > 0);
        assertEquals(1, closes.get());
    }

    // ---- helpers ----

    private RawHttpClient.Response getSegment() throws IOException {
        server.start();
        return RawHttpClient.get(server.getListeningPort(), SEGMENT_URL, ACCEPT_GZIP);
    }

    /** 同 RemoteServerRoutesTest：默认页是 R.raw.index 的原始字节。 */
    private static void assertDefaultPage(Response response) throws IOException {
        assertEquals(Response.Status.OK, response.getStatus());
        assertEquals(NanoHTTPD.MIME_HTML + "; charset=utf-8", response.getMimeType());
        try (InputStream index = ApplicationProvider.getApplicationContext().getResources().openRawResource(R.raw.index)) {
            assertArrayEquals(readAll(index), readAll(response.getData()));
        }
    }

    private static Map<String, String> params(String session, String index) {
        Map<String, String> params = new HashMap<>();
        params.put("s", session);
        params.put("i", index);
        return params;
    }

    private static IHTTPSession segmentSession(Method method, String remoteIp, Map<String, String> params) {
        return segmentSession(method, remoteIp, params, new HashMap<String, String>());
    }

    /** headers 的键用小写，和 NanoHTTPD 解析后的一样。 */
    private static IHTTPSession segmentSession(Method method, String remoteIp, Map<String, String> params,
                                               Map<String, String> headers) {
        IHTTPSession session = mock(IHTTPSession.class);
        when(session.getMethod()).thenReturn(method);
        when(session.getUri()).thenReturn("/hlsseg");
        when(session.getParms()).thenReturn(params);
        when(session.getHeaders()).thenReturn(headers);
        when(session.getRemoteIpAddress()).thenReturn(remoteIp);
        return session;
    }

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        new Random(42).nextBytes(bytes);
        return bytes;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static void awaitTrue(Condition condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + WAIT_MS;
        while (!condition.holds()) {
            if (System.currentTimeMillis() > deadline) fail("condition not met within " + WAIT_MS + " ms");
            Thread.sleep(10);
        }
    }

    private interface Condition {
        boolean holds();
    }

    private interface SegmentHandler {
        Response open(String sessionId, String index);
    }

    /** 覆盖测试接缝 openSegment：记录参数，按 handler 返回响应或抛异常。 */
    private static final class SeamServer extends RemoteServer {
        final List<String[]> calls = new CopyOnWriteArrayList<>();
        volatile SegmentHandler handler = (s, i) -> SegmentProxyResponse.empty(200);

        SeamServer() {
            super(0, ApplicationProvider.getApplicationContext());
        }

        @Override
        Response openSegment(String sessionId, String index) {
            calls.add(new String[]{sessionId, index});
            return handler.open(sessionId, index);
        }
    }

    /** 永不结束的 body：服务端会一直写，直到写失败。 */
    private static final class EndlessStream extends InputStream {
        @Override
        public int read() {
            return 0x47;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            Arrays.fill(b, off, off + len, (byte) 0x47);
            return len;
        }
    }
}
