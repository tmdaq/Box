package com.github.tvbox.osc.server;

import com.github.tvbox.osc.precache.SegmentResponse;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

import fi.iki.elonen.NanoHTTPD;

/**
 * 分片代理的 NanoHTTPD 响应。
 * 状态码原样返回（包括 NanoHTTPD 枚举里没有的 502、504），只带 Content-Type 和长度，
 * 不转发源站响应头；RemoteServer 认出这个类型后不对它做 gzip。
 * 响应关闭时一并关闭 body 和它背后的资源，资源最多关闭一次。
 */
final class SegmentProxyResponse extends NanoHTTPD.Response {

    private SegmentProxyResponse(int status, String contentType, InputStream data, long length) {
        super(new SegmentStatus(status), contentType, data, length);
    }

    /** 默认路径：把预缓存核心的结果转成响应。r 的所有权交给返回的响应。 */
    static SegmentProxyResponse from(SegmentResponse r) {
        return create(r.status, r.contentType, r.body, r.length, r);
    }

    /** 本端生成的空响应（403、500 等）：text/plain，body 为空。 */
    static SegmentProxyResponse empty(int status) {
        return create(status, NanoHTTPD.MIME_PLAINTEXT, null, 0, null);
    }

    /**
     * body 为 null 时是空的定长响应（忽略 length）；length >= 0 时是定长响应；否则是 chunked 响应。
     * resource 可以为 null；不为 null 时，响应关闭时一起关闭它。
     */
    static SegmentProxyResponse create(int status, String contentType, InputStream body, long length, Closeable resource) {
        InputStream data = body != null ? body : new ByteArrayInputStream(new byte[0]);
        long dataLength = body != null ? length : 0;
        return new SegmentProxyResponse(status, contentType, new ClosingStream(data, resource), dataLength);
    }

    /** 关闭时先关被包装的流，再关 resource；resource 最多关一次。 */
    private static final class ClosingStream extends FilterInputStream {
        private final Closeable resource;
        private boolean resourceClosed;

        ClosingStream(InputStream in, Closeable resource) {
            super(in);
            this.resource = resource;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                closeResourceOnce();
            }
        }

        private synchronized void closeResourceOnce() throws IOException {
            if (resourceClosed || resource == null) return;
            resourceClosed = true;
            resource.close();
        }
    }

    /** 自带原因短语的状态，不依赖 NanoHTTPD 的 Status 枚举。 */
    static final class SegmentStatus implements NanoHTTPD.Response.IStatus {
        private final int code;

        SegmentStatus(int code) {
            this.code = code;
        }

        @Override
        public int getRequestStatus() {
            return code;
        }

        /** NanoHTTPD 写状态行时直接用它，所以必须以状态码开头。 */
        @Override
        public String getDescription() {
            return code + " " + reasonPhrase(code);
        }

        private static String reasonPhrase(int code) {
            switch (code) {
                case 200: return "OK";
                case 400: return "Bad Request";
                case 403: return "Forbidden";
                case 404: return "Not Found";
                case 410: return "Gone";
                case 429: return "Too Many Requests";
                case 500: return "Internal Server Error";
                case 502: return "Bad Gateway";
                case 503: return "Service Unavailable";
                case 504: return "Gateway Timeout";
                default: return classPhrase(code);
            }
        }

        private static String classPhrase(int code) {
            switch (code / 100) {
                case 1: return "Informational";
                case 2: return "Successful";
                case 3: return "Redirection";
                case 4: return "Client Error";
                default: return "Server Error";
            }
        }
    }
}
