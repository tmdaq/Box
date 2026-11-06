package com.github.tvbox.osc.precache;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 用 OkHttp 回源（design.md D4）。请求头的名字和值去掉首尾空白，丢弃会干扰分片原样下载的头；
 * 不带 Accept-Encoding，交给 OkHttp 自己加 gzip 并透明解压。不重试。
 */
final class OkHttpSegmentDownloader implements SegmentDownloader {

    /** 每次请求时取客户端。不用 Supplier，因为 minSdk 21。 */
    interface ClientProvider {
        OkHttpClient client();
    }

    private static final Set<String> DROPPED_HEADERS = new HashSet<>(Arrays.asList(
            "accept-encoding", "range", "host", "connection", "content-length"));

    private final ClientProvider provider;

    OkHttpSegmentDownloader(ClientProvider provider) {
        this.provider = provider;
    }

    @Override
    public Call newCall(String url, Map<String, String> headers) {
        Request request;
        try {
            request = request(url, headers);
        } catch (IllegalArgumentException e) {
            return new FailedCall(new IOException("invalid url: " + url, e));
        }
        return new OkCall(provider.client().newCall(request));
    }

    private static Request request(String url, Map<String, String> headers) {
        Request.Builder builder = new Request.Builder().url(url);
        if (headers == null) return builder.build();
        for (Map.Entry<String, String> h : headers.entrySet()) {
            if (h.getKey() == null || h.getValue() == null) continue;
            String name = h.getKey().trim();
            if (name.isEmpty() || DROPPED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) continue;
            try {
                builder.header(name, h.getValue().trim());
            } catch (IllegalArgumentException ignored) {
                // OkHttp 认为不合法的头，跳过这一个
            }
        }
        return builder.build();
    }

    private static final class OkCall implements Call {
        private final okhttp3.Call call;

        OkCall(okhttp3.Call call) {
            this.call = call;
        }

        @Override
        public Source execute() throws IOException {
            Response response = call.execute();
            ResponseBody body = response.body();
            MediaType type = body.contentType();
            return new Source(response.code(), body.byteStream(), body.contentLength(),
                    type == null ? null : type.toString());
        }

        @Override
        public void cancel() {
            call.cancel();
        }
    }

    /** 地址不合法时，把错误推迟到 execute()，和其他拿不到响应的情况一样处理。 */
    private static final class FailedCall implements Call {
        private final IOException error;

        FailedCall(IOException error) {
            this.error = error;
        }

        @Override
        public Source execute() throws IOException {
            throw error;
        }

        @Override
        public void cancel() {
        }
    }
}
