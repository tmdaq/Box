package com.github.tvbox.osc.precache;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/** 回源抽象（design.md D4）。实现负责请求头的清洗，调用方传原始请求头即可。 */
interface SegmentDownloader {

    /** 不发请求，也不抛异常；请求在 {@link Call#execute()} 时才发出。 */
    Call newCall(String url, Map<String, String> headers);

    interface Call {
        /** 阻塞到拿到响应头。拿不到 HTTP 响应时抛 IOException，连接被拒时抛 java.net.ConnectException。 */
        Source execute() throws IOException;

        /** 任意线程都可以调用，不阻塞，可以重复调用。会让正在进行的 execute() 或 body 读取抛 IOException。 */
        void cancel();
    }

    /** 源站的响应。非 2xx 时调用方不读 body，只关闭。 */
    final class Source implements Closeable {
        final int code;
        final InputStream body;
        /** -1 表示未知。 */
        final long contentLength;
        /** 可能为 null。 */
        final String contentType;

        Source(int code, InputStream body, long contentLength, String contentType) {
            this.code = code;
            this.body = body;
            this.contentLength = contentLength;
            this.contentType = contentType;
        }

        /** 可以重复调用，不抛异常。 */
        @Override
        public void close() {
            try {
                body.close();
            } catch (IOException ignored) {
            }
        }
    }
}
