package com.github.tvbox.osc.precache;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/**
 * 分片代理的结果，不依赖 NanoHTTPD。字段语义见 add-hls-precache-core 的 design.md「跨 change 接口」。
 */
public final class SegmentResponse implements Closeable {
    /** length 取这个值，表示长度未知。 */
    public static final long UNKNOWN_LENGTH = -1L;

    /** HTTP 状态码。成功时固定为 200。 */
    public final int status;
    /** 只在 status == 200 时不为 null。 */
    public final InputStream body;
    /** body 的字节数；未知时为 UNKNOWN_LENGTH；错误响应时为 0。 */
    public final long length;
    /** 一定不为 null；错误响应时为 "text/plain"。 */
    public final String contentType;

    SegmentResponse(int status, InputStream body, long length, String contentType) {
        this.status = status;
        this.body = body;
        this.length = length;
        this.contentType = contentType;
    }

    static SegmentResponse error(int status) {
        return new SegmentResponse(status, null, 0, "text/plain");
    }

    /** 关闭 body。可以重复调用，不抛异常。 */
    @Override
    public void close() {
        if (body == null) return;
        try {
            body.close();
        } catch (IOException ignored) {
        }
    }
}
