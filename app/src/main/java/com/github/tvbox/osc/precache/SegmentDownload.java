package com.github.tvbox.osc.precache;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.util.Map;

/**
 * 一次分片下载（design.md D7）：边下边写临时文件，多个读者跟读，读完、长度校验通过后提交到 store。
 * 状态只在本对象的锁里改变；结束时在锁外回调 {@link Listener#onFinished}，恰好一次。
 */
final class SegmentDownload implements Runnable {

    interface Listener {
        void onFinished(SegmentDownload d);
    }

    enum State { PENDING, STREAMING, COMPLETED, HTTP_ERROR, NETWORK_ERROR, DISK_ERROR, FAILED, CANCELLED }

    private static final int CHUNK = 64 * 1024;
    private static final long READER_WAIT_MS = 500;

    private final HlsSegment seg;
    private final Map<String, String> headers;
    private final boolean prefetch;
    private final SegmentDownloader downloader;
    private final SegmentStore store;
    private final Listener listener;

    // 以下字段由本对象的锁保护
    private State state = State.PENDING;
    private SegmentDownloader.Call call;
    private File temp;
    private long bytesWritten;
    private int httpCode;
    private long contentLength = -1;
    private String contentType;
    private boolean throttleSignal;

    SegmentDownload(HlsSegment seg, Map<String, String> headers, boolean prefetch,
                    SegmentDownloader downloader, SegmentStore store, Listener listener) {
        this.seg = seg;
        this.headers = headers;
        this.prefetch = prefetch;
        this.downloader = downloader;
        this.store = store;
        this.listener = listener;
    }

    HlsSegment segment() {
        return seg;
    }

    /** 在 downloadExecutor 上运行。 */
    @Override
    public void run() {
        try {
            download();
        } finally {
            deleteTemp();
            listener.onFinished(this);
        }
    }

    private void download() {
        SegmentDownloader.Call c;
        synchronized (this) {
            if (state != State.PENDING) return;
            c = call = downloader.newCall(seg.url, headers);
        }
        SegmentDownloader.Source source;
        try {
            source = c.execute();
        } catch (IOException e) {
            finish(State.NETWORK_ERROR, e instanceof ConnectException);
            return;
        }
        try {
            receive(source);
        } finally {
            source.close();
        }
    }

    private void receive(SegmentDownloader.Source source) {
        synchronized (this) {
            httpCode = source.code;
            contentLength = source.contentLength;
            contentType = source.contentType;
        }
        if (!isSuccess(source.code)) {
            finish(State.HTTP_ERROR, isThrottleCode(source.code));
            return;
        }
        File file;
        try {
            file = store.newTempFile(seg.key);
        } catch (IOException e) {
            finish(State.DISK_ERROR, false);
            return;
        }
        synchronized (this) {
            temp = file;
            if (state != State.PENDING) return;
            state = State.STREAMING;
            notifyAll();
        }
        finish(copy(source.body, file), false);
    }

    /** 把源站 body 写进临时文件并提交，返回终态。 */
    private State copy(InputStream body, File file) {
        try (OutputStream out = new FileOutputStream(file, true)) {
            byte[] buf = new byte[CHUNK];
            while (true) {
                int n;
                try {
                    n = body.read(buf);
                } catch (IOException e) {
                    return State.FAILED;
                }
                if (n == -1) break;
                out.write(buf, 0, n);
                synchronized (this) {
                    bytesWritten += n;
                    notifyAll();
                }
            }
        } catch (IOException e) {
            return State.DISK_ERROR;
        }
        return commit(file);
    }

    /** 提交和改为 COMPLETED 在同一个锁里完成；已取消的下载一定不会提交。 */
    private synchronized State commit(File file) {
        if (state == State.CANCELLED) return State.CANCELLED;
        if (contentLength >= 0 && bytesWritten != contentLength) return State.FAILED;
        try {
            store.commit(seg.key, file, bytesWritten, contentType);
        } catch (IOException e) {
            return State.DISK_ERROR;
        }
        temp = null;
        return State.COMPLETED;
    }

    /** 进入终态；已经被取消的保持 CANCELLED。 */
    private synchronized void finish(State terminal, boolean throttle) {
        if (state == State.CANCELLED) return;
        state = terminal;
        throttleSignal = throttle;
        notifyAll();
    }

    private void deleteTemp() {
        File file;
        synchronized (this) {
            file = temp;
            temp = null;
        }
        if (file != null) file.delete();
    }

    static boolean isSuccess(int code) {
        return code >= 200 && code <= 299;
    }

    /** 源站限流的信号：403、429、503。连接被拒另算。 */
    static boolean isThrottleCode(int code) {
        return code == 403 || code == 429 || code == 503;
    }

    /** 阻塞到状态不是 PENDING。被中断时恢复中断标记，返回当前状态。 */
    synchronized State awaitHeaders() {
        try {
            while (state == State.PENDING) wait();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return state;
    }

    /**
     * 只在 STREAMING 或 COMPLETED 时调用。打开文件和提交（rename）在同一个锁里，
     * 所以不会按旧路径去打开已经提交的文件。读者关闭流不会取消下载。
     */
    synchronized InputStream openReader() throws IOException {
        if (state == State.COMPLETED) {
            File file = store.committedFile(seg.key);
            if (file == null) throw new IOException("committed file is gone");
            return new Reader(new FileInputStream(file));
        }
        if (state != State.STREAMING) throw new IOException("download is " + state);
        return new Reader(new FileInputStream(temp));
    }

    synchronized int httpCode() {
        return httpCode;
    }

    /** -1 表示未知。 */
    synchronized long contentLength() {
        return contentLength;
    }

    /** 源站的 Content-Type，可能为 null。 */
    synchronized String contentType() {
        return contentType;
    }

    /** 结果是 403/429/503 或连接被拒时为 true。 */
    synchronized boolean isThrottleSignal() {
        return throttleSignal;
    }

    boolean isPrefetch() {
        return prefetch;
    }

    synchronized boolean isFinished() {
        return state != State.PENDING && state != State.STREAMING;
    }

    /** 任意线程，不阻塞。取消后不会提交，读者抛 IOException。 */
    void cancel() {
        SegmentDownloader.Call c;
        synchronized (this) {
            if (isFinished()) return;
            state = State.CANCELLED;
            notifyAll();
            c = call;
        }
        if (c != null) c.cancel();
    }

    /** 跟读下载进度：读到已写入的位置时等待，直到有新数据或下载结束。 */
    private final class Reader extends InputStream {
        private final FileInputStream in;
        private long position;

        Reader(FileInputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) == -1 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            long available = awaitData();
            if (available == 0) return -1;
            int n = in.read(b, off, (int) Math.min(len, available));
            if (n == -1) throw new IOException("segment file ended early");
            position += n;
            return n;
        }

        /** 返回可读的字节数；0 表示已经读完。 */
        private long awaitData() throws IOException {
            synchronized (SegmentDownload.this) {
                while (true) {
                    if (state == State.CANCELLED) throw new IOException("download cancelled");
                    if (position < bytesWritten) return bytesWritten - position;
                    if (state == State.COMPLETED) return 0;
                    if (state != State.STREAMING) throw new IOException("download " + state);
                    try {
                        SegmentDownload.this.wait(READER_WAIT_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedIOException();
                    }
                }
            }
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
