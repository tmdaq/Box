package com.github.tvbox.osc.server;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

import fi.iki.elonen.NanoHTTPD;

public class SegmentProxyResponseTest {

    private static final String TS = "video/mp2t";

    @Test
    public void knownStatusesCarryCodeAndReasonPhrase() {
        assertStatus(200, "200 OK");
        assertStatus(400, "400 Bad Request");
        assertStatus(403, "403 Forbidden");
        assertStatus(404, "404 Not Found");
        assertStatus(410, "410 Gone");
        assertStatus(429, "429 Too Many Requests");
        assertStatus(500, "500 Internal Server Error");
        assertStatus(502, "502 Bad Gateway");
        assertStatus(503, "503 Service Unavailable");
        assertStatus(504, "504 Gateway Timeout");
    }

    @Test
    public void unlistedStatusesFallBackToTheirClassPhrase() {
        assertStatus(101, "101 Informational");
        assertStatus(206, "206 Successful");
        assertStatus(302, "302 Redirection");
        assertStatus(418, "418 Client Error");
        assertStatus(599, "599 Server Error");
    }

    @Test
    public void mimeTypeIsTheGivenContentType() {
        assertEquals(TS, SegmentProxyResponse.create(200, TS, null, 0, null).getMimeType());
    }

    @Test
    public void bodyIsPassedThrough() throws IOException {
        byte[] bytes = {0x47, 0x40, 0x00, 0x10};

        SegmentProxyResponse response = SegmentProxyResponse.create(200, TS, new ByteArrayInputStream(bytes), bytes.length, null);

        assertArrayEquals(bytes, readAll(response.getData()));
    }

    @Test
    public void nullBodyReadsAsEmpty() throws IOException {
        SegmentProxyResponse response = SegmentProxyResponse.create(502, NanoHTTPD.MIME_PLAINTEXT, null, 123, null);

        assertEquals(0, readAll(response.getData()).length);
    }

    @Test
    public void closingResponseClosesBodyAndResourceOnce() throws IOException {
        TrackingStream body = new TrackingStream();
        CountingCloseable resource = new CountingCloseable();
        SegmentProxyResponse response = SegmentProxyResponse.create(200, TS, body, -1, resource);

        response.close();
        response.close();

        assertTrue(body.closed);
        assertEquals(1, resource.closes);
    }

    @Test
    public void closingWithoutResourceDoesNotThrow() throws IOException {
        SegmentProxyResponse.create(200, TS, new TrackingStream(), -1, null).close();
    }

    @Test
    public void emptyResponseIsPlainTextWithoutBody() throws IOException {
        SegmentProxyResponse response = SegmentProxyResponse.empty(403);

        assertEquals(403, response.getStatus().getRequestStatus());
        assertEquals(NanoHTTPD.MIME_PLAINTEXT, response.getMimeType());
        assertEquals(0, readAll(response.getData()).length);
    }

    private static void assertStatus(int code, String description) {
        NanoHTTPD.Response.IStatus status = SegmentProxyResponse.create(code, TS, null, 0, null).getStatus();
        assertEquals(code, status.getRequestStatus());
        assertEquals(description, status.getDescription());
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static final class TrackingStream extends ByteArrayInputStream {
        boolean closed;

        TrackingStream() {
            super(new byte[]{1, 2, 3});
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    private static final class CountingCloseable implements Closeable {
        int closes;

        @Override
        public void close() {
            closes++;
        }
    }
}
