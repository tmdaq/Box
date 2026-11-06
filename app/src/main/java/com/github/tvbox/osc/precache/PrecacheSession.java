package com.github.tvbox.osc.precache;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
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
 * 可变状态都由本对象的锁保护；只在锁外阻塞（等响应头、纯转发）。
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
            Lookup found;
            synchronized (this) {
                if (closed) return SegmentResponse.error(410);
                if (attempt == 0) moveAnchor(index);
                found = lookup(seg);
                pump();
            }
            if (found.response != null) return found.response;
            if (found.download == null) return forward(seg);
            SegmentResponse response = respond(found.download, seg);
            if (response != null) return response;
        }
        return SegmentResponse.error(502);
    }

    /** 不阻塞：标记关闭，取消全部下载。可以重复调用。 */
    synchronized void close() {
        closed = true;
        for (SegmentDownload d : downloads.values()) d.cancel();
    }

    @Override
    public void onFinished(SegmentDownload d) {
        SegmentDownload.State state = d.state();
        int index = d.segment().index;
        synchronized (this) {
            if (downloads.get(index) == d) downloads.remove(index);
            recordSignal(d.isThrottleSignal());
            if (state == SegmentDownload.State.DISK_ERROR) degrade();
            if (d.isPrefetch() && isFailure(state)) prefetchFailed.add(index);
            pump();
        }
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

    private static final Lookup FORWARD = new Lookup(null, null);

    private Lookup lookup(HlsSegment seg) {
        if (degraded) return FORWARD;
        SegmentResponse hit = openCommitted(seg);
        if (hit != null) return new Lookup(hit, null);
        if (degraded) return FORWARD;
        SegmentDownload d = downloads.get(seg.index);
        if (d == null || !d.canServe()) {
            prefetchFailed.remove(seg.index);
            d = start(seg, false);
        }
        return new Lookup(null, d);
    }

    /** 命中时返回 200；文件已不存在时按未命中返回 null；文件还在却打不开时降级并返回 null。 */
    private SegmentResponse openCommitted(HlsSegment seg) {
        File file = store.committedFile(seg.key);
        if (file == null) return null;
        long length = file.length();
        try {
            InputStream in = new FileInputStream(file);
            return new SegmentResponse(200, in, length, contentType(store.contentType(seg.key), seg.url));
        } catch (IOException e) {
            if (file.exists()) {
                degrade();
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
                synchronized (this) {
                    degrade();
                }
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

    // ---- 调度（都在锁里调用） ----

    /** 更新锚点；跳转时取消新窗口外的预缓存下载；锚点变化时淘汰窗口和回看范围外的分片。 */
    private void moveAnchor(int index) {
        boolean jump = PrecacheWindow.isJump(anchor, index);
        boolean changed = anchor != index;
        anchor = index;
        int end = window.aheadEnd(anchor, config.aheadMs);
        if (jump) {
            for (Map.Entry<Integer, SegmentDownload> e : downloads.entrySet()) {
                int k = e.getKey();
                if (e.getValue().isPrefetch() && (k < anchor || k > end)) e.getValue().cancel();
            }
        }
        if (changed && !degraded) evictOutside(window.behindStart(anchor, config.behindMs), end);
    }

    /** 删除序号不在 [from, to] 里的已提交分片，以及不属于当前列表的 key。 */
    private void evictOutside(int from, int to) {
        for (String key : store.committedKeys()) {
            Integer k = indexByKey.get(key);
            if (k == null || k < from || k > to) store.remove(key);
        }
    }

    /** 按升序给窗口里还没下载的分片开始预缓存下载，直到并发或磁盘上限。 */
    private void pump() {
        if (closed || degraded || anchor < 0) return;
        int allowed = throttled ? config.throttledConcurrency : config.maxConcurrency;
        int end = window.aheadEnd(anchor, config.aheadMs);
        for (int k = anchor + 1; k <= end && downloads.size() < allowed; k++) {
            HlsSegment seg = segments.get(k);
            if (store.isCommitted(seg.key) || downloads.containsKey(k) || prefetchFailed.contains(k)) continue;
            if (!fits() && !evictBehindAnchor()) break;
            start(seg, true);
        }
    }

    private SegmentDownload start(HlsSegment seg, boolean prefetch) {
        SegmentDownload d = new SegmentDownload(seg, headers, prefetch, downloader, store, this);
        downloads.put(seg.index, d);
        downloadExecutor.execute(d);
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

    /** 按序号从小到大删除锚点之前已提交的分片，直到放得下；返回是否放得下。 */
    private boolean evictBehindAnchor() {
        for (int k = 0; k < anchor; k++) {
            String key = segments.get(k).key;
            if (!store.isCommitted(key)) continue;
            store.remove(key);
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
    private void degrade() {
        degraded = true;
        for (SegmentDownload d : downloads.values()) {
            if (d.isPrefetch()) d.cancel();
        }
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
