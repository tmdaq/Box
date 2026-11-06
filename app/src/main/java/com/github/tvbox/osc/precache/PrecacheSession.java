package com.github.tvbox.osc.precache;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * 一个预缓存会话（design.md D8–D12）：维护锚点，按预缓存窗口调度预缓存下载，处理跳转、回看淘汰、
 * 磁盘上限、限流降并发和磁盘故障降级，并为播放器的分片请求给出结果。
 * 可变状态都由本对象的锁保护。持锁时只做内存操作：磁盘 IO（stat、打开、删除文件）和取消下载都在锁外，
 * 阻塞（等响应头、纯转发）也在锁外（design.md D5）。
 */
final class PrecacheSession implements SegmentDownload.Listener {

    private final String id;
    private final List<HlsSegment> segments;
    private final Map<String, Integer> indexByKey = new HashMap<>();
    private final PrecacheWindow window;
    private final Map<String, String> headers;
    private final SegmentStore store;
    private final PrecacheConfig config;
    private final SegmentDownloader downloader;
    private final Executor downloadExecutor;

    // 以下字段由本对象的锁保护
    private boolean closed;
    private boolean degraded;
    private boolean throttled;
    private int throttleSignals;
    private int anchor = -1;
    /** 正在进行的下载（包括按需下载），按分片序号；它的大小就是并发数。结束后在 onFinished 里移除。 */
    private final Map<Integer, SegmentDownload> downloads = new HashMap<>();
    /** 预缓存失败过的序号，pump 不再重试；播放器请求到时由按需下载再试一次。 */
    private final Set<Integer> prefetchFailed = new HashSet<>();

    PrecacheSession(String id, HlsPlaylist playlist, Map<String, String> headers, SegmentStore store,
                    PrecacheConfig config, SegmentDownloader downloader, Executor downloadExecutor) {
        this.id = id;
        this.segments = playlist.segments();
        this.headers = headers;
        this.store = store;
        this.config = config;
        this.downloader = downloader;
        this.downloadExecutor = downloadExecutor;
        long[] durations = new long[segments.size()];
        for (HlsSegment seg : segments) {
            durations[seg.index] = seg.durationMs;
            indexByKey.put(seg.key, seg.index);
        }
        this.window = new PrecacheWindow(durations);
    }

    String id() {
        return id;
    }

    SegmentStore store() {
        return store;
    }

    int segmentCount() {
        return segments.size();
    }

    /** 阻塞到能确定状态码为止。index 已由门面校验过范围。 */
    SegmentResponse open(int index) {
        HlsSegment seg = segments.get(index);
        // 读者打不开，或者等的下载被跳转取消时，按未命中重来一次
        for (int attempt = 0; attempt < 2; attempt++) {
            Deferred later = new Deferred();
            boolean forwardOnly;
            synchronized (this) {
                if (closed) return SegmentResponse.error(410);
                if (attempt == 0) moveAnchor(index, later);
                forwardOnly = degraded;
            }
            later.run();
            if (forwardOnly) return forward(seg);
            Lookup found = lookup(seg);
            if (found == null) return SegmentResponse.error(410);
            if (found.response != null) return found.response;
            if (found.download == null) return forward(seg);
            SegmentResponse response = respond(found.download, seg);
            if (response != null) return response;
        }
        return SegmentResponse.error(502);
    }

    /** 不阻塞：标记关闭，取消全部下载。可以重复调用。持会话锁时只做内存操作，取消在锁外进行。 */
    void close() {
        List<SegmentDownload> all;
        synchronized (this) {
            closed = true;
            all = new ArrayList<>(downloads.values());
        }
        for (SegmentDownload d : all) d.cancel();
    }

    @Override
    public void onFinished(SegmentDownload d) {
        SegmentDownload.State state = d.state();
        int index = d.segment().index;
        Deferred later = new Deferred();
        synchronized (this) {
            if (downloads.get(index) == d) downloads.remove(index);
            recordSignal(d.isThrottleSignal());
            if (state == SegmentDownload.State.DISK_ERROR) degrade(later);
            if (d.isPrefetch() && isFailure(state)) prefetchFailed.add(index);
            pump(later);
        }
        later.run();
    }

    // ---- open 的各步 ----

    /** 命中时 response 不为 null；需要等下载时 download 不为 null；都为 null 表示走纯转发。 */
    private static final class Lookup {
        final SegmentResponse response;
        final SegmentDownload download;

        Lookup(SegmentResponse response, SegmentDownload download) {
            this.response = response;
            this.download = download;
        }
    }

    /**
     * 先在锁外查磁盘，再在锁里加入正在进行（或刚提交）的下载，都没有时开始一个按需下载。
     * 会话已关闭时返回 null。
     */
    private Lookup lookup(HlsSegment seg) {
        for (int look = 0; ; look++) {
            SegmentResponse hit = openCommitted(seg);
            Deferred later = new Deferred();
            SegmentDownload d = null;
            boolean committedMeanwhile = false;
            synchronized (this) {
                if (closed) {
                    if (hit != null) hit.close();
                    return null;
                }
                if (hit == null && !degraded) {
                    d = downloads.get(seg.index);
                    if (d == null || !d.canServe()) {
                        // 查完磁盘、拿到锁之前，下载刚好提交并结束了：再查一次磁盘，不重复下载
                        committedMeanwhile = look == 0 && store.isCommitted(seg.key);
                        d = committedMeanwhile ? null : startOnDemand(seg);
                    }
                }
                pump(later);
            }
            later.run();
            if (!committedMeanwhile) return new Lookup(hit, d);
        }
    }

    /** 按需下载不检查并发和磁盘上限。在锁里调用。 */
    private SegmentDownload startOnDemand(HlsSegment seg) {
        prefetchFailed.remove(seg.index);
        return start(seg, false);
    }

    /** 在锁外调用（stat、打开文件）。命中时返回 200；文件已不存在时按未命中返回 null；文件还在却打不开时降级并返回 null。 */
    private SegmentResponse openCommitted(HlsSegment seg) {
        File file = store.committedFile(seg.key);
        if (file == null) return null;
        long length = file.length();
        try {
            InputStream in = new FileInputStream(file);
            return new SegmentResponse(200, in, length, contentType(store.contentType(seg.key), seg.url));
        } catch (IOException e) {
            if (file.exists()) {
                degradeNow();
            } else {
                store.remove(seg.key);
            }
            return null;
        }
    }

    /** 等下载的响应头，按状态给出结果；需要重来时返回 null。 */
    private SegmentResponse respond(SegmentDownload d, HlsSegment seg) {
        switch (d.awaitHeaders()) {
            case STREAMING:
            case COMPLETED:
                try {
                    InputStream in = d.openReader();
                    return new SegmentResponse(200, in, length(d.contentLength()), contentType(d.contentType(), seg.url));
                } catch (IOException e) {
                    return null;
                }
            case HTTP_ERROR:
                return SegmentResponse.error(d.httpCode());
            case NETWORK_ERROR:
                return SegmentResponse.error(502);
            case DISK_ERROR:
                degradeNow();
                return forward(seg);
            case CANCELLED:
                synchronized (this) {
                    if (closed) return SegmentResponse.error(410);
                }
                return null;
            case FAILED:
                return null;
            default:
                return SegmentResponse.error(502);
        }
    }

    /** 纯转发：在当前线程上请求源站，不读写磁盘。 */
    private SegmentResponse forward(HlsSegment seg) {
        SegmentDownloader.Source source;
        try {
            source = downloader.newCall(seg.url, headers).execute();
        } catch (IOException e) {
            synchronized (this) {
                recordSignal(e instanceof ConnectException);
            }
            return SegmentResponse.error(502);
        }
        if (!SegmentDownload.isSuccess(source.code)) {
            source.close();
            synchronized (this) {
                recordSignal(SegmentDownload.isThrottleCode(source.code));
            }
            return SegmentResponse.error(source.code);
        }
        return new SegmentResponse(200, source.body, length(source.contentLength), contentType(source.contentType, seg.url));
    }

    private static long length(long contentLength) {
        return contentLength >= 0 ? contentLength : SegmentResponse.UNKNOWN_LENGTH;
    }

    // ---- 调度（都在锁里调用，只做内存操作；取消和删文件记进 later，释放锁之后执行） ----

    /**
     * 持会话锁时攒下的取消和删除，释放锁之后由同一个线程执行（D5）。
     * 这样会话锁上不会有 IO，主线程上的 close() 不会等别的线程删文件、打开文件。
     */
    private final class Deferred {
        private final List<SegmentDownload> cancels = new ArrayList<>();
        private final List<File> deletions = new ArrayList<>();

        void cancel(SegmentDownload d) {
            cancels.add(d);
        }

        void delete(File file) {
            if (file != null) deletions.add(file);
        }

        /** 在锁外调用。执行后清空，可以再次攒。 */
        void run() {
            for (SegmentDownload d : cancels) d.cancel();
            for (File file : deletions) store.delete(file);
            cancels.clear();
            deletions.clear();
        }
    }

    /** 更新锚点；跳转时取消新窗口外的预缓存下载；锚点变化时淘汰窗口和回看范围外的分片。 */
    private void moveAnchor(int index, Deferred later) {
        boolean jump = PrecacheWindow.isJump(anchor, index);
        boolean changed = anchor != index;
        anchor = index;
        int end = window.aheadEnd(anchor, config.aheadMs);
        if (jump) {
            for (Map.Entry<Integer, SegmentDownload> e : downloads.entrySet()) {
                int k = e.getKey();
                if (e.getValue().isPrefetch() && (k < anchor || k > end)) later.cancel(e.getValue());
            }
        }
        if (changed && !degraded) evictOutside(window.behindStart(anchor, config.behindMs), end, later);
    }

    /** 淘汰序号不在 [from, to] 里的已提交分片，以及不属于当前列表的 key。 */
    private void evictOutside(int from, int to, Deferred later) {
        for (String key : store.committedKeys()) {
            Integer k = indexByKey.get(key);
            if (k == null || k < from || k > to) later.delete(store.detach(key));
        }
    }

    /** 按升序给窗口里还没下载的分片开始预缓存下载，直到并发或磁盘上限。 */
    private void pump(Deferred later) {
        if (closed || degraded || anchor < 0) return;
        int allowed = throttled ? config.throttledConcurrency : config.maxConcurrency;
        int end = window.aheadEnd(anchor, config.aheadMs);
        for (int k = anchor + 1; k <= end && downloads.size() < allowed; k++) {
            HlsSegment seg = segments.get(k);
            if (store.isCommitted(seg.key) || downloads.containsKey(k) || prefetchFailed.contains(k)) continue;
            if (!fits() && !evictBehindAnchor(later)) break;
            start(seg, true);
        }
    }

    private SegmentDownload start(HlsSegment seg, boolean prefetch) {
        SegmentDownload d = new SegmentDownload(seg, headers, prefetch, downloader, store, this);
        downloads.put(seg.index, d);
        try {
            downloadExecutor.execute(d);
        } catch (RuntimeException e) {
            // 比如 RejectedExecutionException：下载没有开始，也就不会有 onFinished，在这里收尾
            downloads.remove(seg.index);
            d.notStarted();
            if (prefetch) prefetchFailed.add(seg.index);
        }
        return d;
    }

    /** 已存储的分片 + 正在下载的预计大小 + 下一个分片的预计大小，不超过上限。 */
    private boolean fits() {
        long estimate = store.averageSize(config.defaultSegmentEstimateBytes);
        long reserved = 0;
        for (SegmentDownload d : downloads.values()) {
            long length = d.contentLength();
            reserved += length >= 0 ? length : estimate;
        }
        return store.committedBytes() + reserved + estimate <= config.capBytes;
    }

    /** 按序号从小到大淘汰锚点之前已提交的分片，直到放得下；返回是否放得下。 */
    private boolean evictBehindAnchor(Deferred later) {
        for (int k = 0; k < anchor; k++) {
            String key = segments.get(k).key;
            if (!store.isCommitted(key)) continue;
            later.delete(store.detach(key));
            if (fits()) return true;
        }
        return false;
    }

    private void recordSignal(boolean signal) {
        if (!signal) return;
        throttleSignals++;
        if (throttleSignals >= config.throttleThreshold) throttled = true;
    }

    /** 降级为纯转发：取消全部预缓存下载，之后不再 pump；正在进行的按需下载继续。 */
    private void degrade(Deferred later) {
        degraded = true;
        for (SegmentDownload d : downloads.values()) {
            if (d.isPrefetch()) later.cancel(d);
        }
    }

    /** 在锁外调用的 degrade。 */
    private void degradeNow() {
        Deferred later = new Deferred();
        synchronized (this) {
            degrade(later);
        }
        later.run();
    }

    private static boolean isFailure(SegmentDownload.State state) {
        return state == SegmentDownload.State.HTTP_ERROR
                || state == SegmentDownload.State.NETWORK_ERROR
                || state == SegmentDownload.State.FAILED;
    }

    // ---- Content-Type（D11） ----

    /** 优先用源站的 Content-Type；没有时按 URL 路径的扩展名推断（不区分大小写，忽略 query）。 */
    static String contentType(String sourceType, String url) {
        if (sourceType != null) return sourceType;
        switch (extension(url)) {
            case "ts":
                return "video/mp2t";
            case "m4s":
            case "mp4":
            case "m4v":
            case "cmfv":
                return "video/mp4";
            case "aac":
                return "audio/aac";
            case "vtt":
                return "text/vtt";
            default:
                return "application/octet-stream";
        }
    }

    /** URL 路径最后一段的扩展名，小写；没有时为空串。 */
    private static String extension(String url) {
        int scheme = url.indexOf("://");
        int pathStart = url.indexOf('/', scheme < 0 ? 0 : scheme + 3);
        if (pathStart < 0) return "";
        int pathEnd = url.length();
        for (char c : new char[]{'?', '#'}) {
            int i = url.indexOf(c, pathStart);
            if (i >= 0 && i < pathEnd) pathEnd = i;
        }
        String path = url.substring(pathStart, pathEnd);
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
