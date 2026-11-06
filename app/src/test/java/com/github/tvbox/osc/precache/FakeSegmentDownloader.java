package com.github.tvbox.osc.precache;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 可控的回源替身：按 URL 预设状态码、字节或异常，可以用闸门卡住响应头或 body，记录每个 URL 的请求。
 * 没有预设的 URL 返回 200，body 是 {@link #bytesFor(String, int)} 生成的 defaultSize 个字节。
 */
final class FakeSegmentDownloader implements SegmentDownloader {

    /** 一个 URL 的预设响应。 */
    static final class Plan {
        int code = 200;
        byte[] body;
        /** null 表示取 body.length。 */
        Long contentLength;
        String contentType;
        IOException error;
        CountDownLatch headersGate;
        CountDownLatch bodyGate;
        int bodyGateAfter;

        Plan code(int code) {
            this.code = code;
            return this;
        }

        Plan body(byte[] body) {
            this.body = body;
            return this;
        }

        Plan contentLength(long contentLength) {
            this.contentLength = contentLength;
            return this;
        }

        Plan contentType(String contentType) {
            this.contentType = contentType;
            return this;
        }

        Plan error(IOException error) {
            this.error = error;
            return this;
        }

        /** execute() 阻塞到 gate 打开。 */
        Plan holdHeaders(CountDownLatch gate) {
            this.headersGate = gate;
            return this;
        }

        /** body 读到第 afterBytes 个字节时阻塞到 gate 打开。 */
        Plan holdBody(int afterBytes, CountDownLatch gate) {
            this.bodyGateAfter = afterBytes;
            this.bodyGate = gate;
            return this;
        }
    }

    private final Map<String, Plan> plans = new ConcurrentHashMap<>();
    private final List<String> requested = new ArrayList<>();
    private final Map<String, Map<String, String>> lastHeaders = new HashMap<>();
    private final List<String> cancelled = new ArrayList<>();
    private volatile int defaultSize = 100;
    private volatile CountDownLatch defaultBodyGate;
    private volatile int defaultBodyGateAfter;

    /** 预设一个 URL 的响应，返回的 Plan 可以继续设置。 */
    Plan on(String url) {
        Plan plan = new Plan();
        plans.put(url, plan);
        return plan;
    }

    void defaultSize(int size) {
        defaultSize = size;
    }

    /** 没有预设的 URL，body 读到 afterBytes 时阻塞到 gate 打开。 */
    void holdDefaultBody(int afterBytes, CountDownLatch gate) {
        defaultBodyGateAfter = afterBytes;
        defaultBodyGate = gate;
    }

    /** 没有预设的 URL 返回的字节：按 URL 生成，不同 URL 的内容不同。 */
    static byte[] bytesFor(String url, int size) {
        byte[] seed = url.getBytes(Charset.forName("UTF-8"));
        byte[] out = new byte[size];
        for (int i = 0; i < size; i++) out[i] = (byte) (seed[i % seed.length] + i / seed.length);
        return out;
    }

    synchronized int requestCount(String url) {
        return Collections.frequency(requested, url);
    }

    synchronized int totalRequests() {
        return requested.size();
    }

    synchronized List<String> requestedUrls() {
        return new ArrayList<>(requested);
    }

    synchronized Map<String, String> lastHeaders(String url) {
        return lastHeaders.get(url);
    }

    synchronized boolean wasCancelled(String url) {
        return cancelled.contains(url);
    }

    @Override
    public Call newCall(String url, Map<String, String> headers) {
        return new FakeCall(url, headers == null ? null : new HashMap<>(headers));
    }

    private Plan planFor(String url) {
        Plan plan = plans.get(url);
        if (plan != null) return plan;
        Plan def = new Plan().body(bytesFor(url, defaultSize));
        if (defaultBodyGate != null) def.holdBody(defaultBodyGateAfter, defaultBodyGate);
        return def;
    }

    private final class FakeCall implements Call {
        private final String url;
        private final Map<String, String> headers;
        private volatile boolean canceled;

        FakeCall(String url, Map<String, String> headers) {
            this.url = url;
            this.headers = headers;
        }

        @Override
        public Source execute() throws IOException {
            synchronized (FakeSegmentDownloader.this) {
                requested.add(url);
                lastHeaders.put(url, headers);
            }
            Plan plan = planFor(url);
            await(plan.headersGate);
            if (plan.error != null) throw plan.error;
            byte[] body = plan.body == null ? new byte[0] : plan.body;
            long length = plan.contentLength == null ? body.length : plan.contentLength;
            return new Source(plan.code, new GatedStream(body, plan), length, plan.contentType);
        }

        @Override
        public void cancel() {
            if (canceled) return;
            canceled = true;
            synchronized (FakeSegmentDownloader.this) {
                cancelled.add(url);
            }
        }

        private void checkCanceled() throws IOException {
            if (canceled) throw new IOException("Canceled");
        }

        /** 等闸门打开；等待期间被取消时抛 IOException。 */
        private void await(CountDownLatch gate) throws IOException {
            checkCanceled();
            if (gate == null) return;
            try {
                while (!gate.await(20, TimeUnit.MILLISECONDS)) checkCanceled();
            } catch (InterruptedException e) {
                throw new InterruptedIOException();
            }
            checkCanceled();
        }

        private final class GatedStream extends InputStream {
            private final byte[] data;
            private final Plan plan;
            private int pos;

            GatedStream(byte[] data, Plan plan) {
                this.data = data;
                this.plan = plan;
            }

            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) == -1 ? -1 : one[0] & 0xff;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (plan.bodyGate != null && pos >= plan.bodyGateAfter) await(plan.bodyGate);
                checkCanceled();
                if (pos >= data.length) return -1;
                int end = data.length;
                if (plan.bodyGate != null && pos < plan.bodyGateAfter) end = Math.min(end, plan.bodyGateAfter);
                int n = Math.min(len, end - pos);
                System.arraycopy(data, pos, b, off, n);
                pos += n;
                return n;
            }
        }
    }
}
