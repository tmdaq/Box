package com.github.tvbox.osc.precache;

import com.github.tvbox.osc.util.OkGoHelper;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.OkHttpClient;

/**
 * HLS 预缓存的门面，进程内单例（design.md D3、D5、D13）。
 * 接口语义见 add-hls-precache-core 的 design.md「跨 change 接口」。
 */
public final class HlsPrecache {

    /** 缓存根目录名。S3 用它拼出 <外部缓存目录>/hlscache，并在 cleanPlayerCache() 里删除它。 */
    public static final String CACHE_DIR_NAME = "hlscache";

    /** 分片代理路径。S2 在 RemoteServer.serve 里用 fileName.equals(SEGMENT_PATH) 匹配。 */
    public static final String SEGMENT_PATH = "/hlsseg";
    /** query 参数：会话标识，取值是 [0-9a-z]+。 */
    public static final String PARAM_SESSION = "s";
    /** query 参数：分片序号，十进制，从 0 开始，表示分片在列表里的位置（不是 MEDIA-SEQUENCE）。 */
    public static final String PARAM_INDEX = "i";

    private static final class Holder {
        static final HlsPrecache INSTANCE = new HlsPrecache(PrecacheConfig.DEFAULT,
                new OkHttpSegmentDownloader(HlsPrecache::defaultClient),
                new ThreadPoolExecutor(0, Integer.MAX_VALUE, 30, TimeUnit.SECONDS, new SynchronousQueue<Runnable>(),
                        daemonThreads("hls-precache-", true)),
                Executors.newSingleThreadExecutor(daemonThreads("hls-precache-io", false)));
    }

    /** OkGoHelper 还没初始化时的退路，进程内共享。 */
    private static final class FallbackClient {
        static final OkHttpClient INSTANCE = new OkHttpClient();
    }

    private final PrecacheConfig config;
    private final SegmentDownloader downloader;
    private final Executor downloadExecutor;
    private final Executor ioExecutor;
    private final AtomicLong sessionSequence = new AtomicLong(System.currentTimeMillis());

    private volatile File cacheRoot;
    /** 写入时持有本对象的锁；openSegment 不持锁读取。 */
    private volatile PrecacheSession current;

    HlsPrecache(PrecacheConfig config, SegmentDownloader downloader, Executor downloadExecutor, Executor ioExecutor) {
        this.config = config;
        this.downloader = downloader;
        this.downloadExecutor = downloadExecutor;
        this.ioExecutor = ioExecutor;
    }

    /**
     * 取进程内单例，第一次调用时创建。线程安全，不做 IO，可以在主线程调用。
     * 默认配置：下载器使用 OkGoHelper.getDefaultClient()（每次请求时取；为 null 时退回一个
     * 进程内共享的 new OkHttpClient()），线程都是 daemon 线程。
     */
    public static HlsPrecache get() {
        return Holder.INSTANCE;
    }

    /**
     * 设置缓存根目录。只记下路径，不建目录，不做 IO，可以在主线程调用。
     * 没有设置时，rewrite 一律返回 null，也就是不启用预缓存。
     * 重复调用时，只影响之后新建的会话。
     */
    public void setCacheRoot(File cacheRoot) {
        this.cacheRoot = cacheRoot;
    }

    /**
     * 改写 m3u8，同时开始一个新的预缓存会话。
     *
     * @param playlistUrl 这份 m3u8 的真实源站地址，用来解析相对 URI，也作为会话复用的判断依据。
     * @param content     m3u8 文本。
     * @param headers     播放时的请求头，可以为 null。方法内部会复制一份，调用方之后再修改不影响会话。
     * @param port        本地服务的实际端口。
     * @return 改写后的 m3u8 文本；不适用时返回 null。
     *
     * 线程：任意线程都可以调用，包括主线程。不做网络 IO，也不做阻塞的磁盘 IO。
     * 副作用：不管返回什么，上一个会话都会结束。返回非 null 时，新会话成为当前会话；
     *         playlistUrl 和上一个会话相同时复用已下载的分片，不同时上一个会话的目录在后台删除。
     *         返回 null 时，上一个会话的目录也在后台删除。
     * 异常：不抛异常。内部出现任何 Throwable 都当作不适用，返回 null。
     */
    public String rewrite(String playlistUrl, String content, Map<String, String> headers, int port) {
        try {
            return switchSession(HlsPlaylist.parse(playlistUrl, content), playlistUrl, copy(headers), port);
        } catch (Throwable t) {
            endSession();
            return null;
        }
    }

    /**
     * 结束当前会话：取消它的全部下载，让它的代理地址失效（之后的请求返回 410），在后台删除它的目录。
     * 线程：任意线程都可以调用，包括主线程。不阻塞，不做 IO，可以重复调用，不抛异常。
     */
    public void endSession() {
        try {
            PrecacheSession old;
            synchronized (this) {
                old = current;
                current = null;
            }
            if (old != null) {
                old.close();
                deleteInBackground(old.store());
            }
        } catch (Throwable ignored) {
            // 约定不抛异常
        }
    }

    /**
     * 同 {@link #rewrite(String, String, Map, int)}，并把新会话记在 owner 名下，供 {@link #endSession(Object)} 识别。
     * owner 由播放页自己 new 一个普通 Object 传入，不能传 Context。
     * 当前是接口骨架：忽略 owner，实现由返工任务补全。
     */
    public String rewrite(String playlistUrl, String content, Map<String, String> headers, int port, Object owner) {
        return rewrite(playlistUrl, content, headers, port);
    }

    /**
     * 只在当前会话属于 owner 时结束它；不属于时什么都不做。线程与异常语义同 {@link #endSession()}。
     * 当前是接口骨架：忽略 owner、无条件结束，实现由返工任务补全。
     */
    public void endSession(Object owner) {
        endSession();
    }

    /**
     * 分片代理的入口，由 S2 在 NanoHTTPD 的请求线程上调用。
     *
     * @param sessionId session.getParms().get(PARAM_SESSION)，可以为 null
     * @param index     session.getParms().get(PARAM_INDEX)，可以为 null
     * @return 不为 null。调用方必须把它发出去，或者调用 close()。
     *
     * 线程：阻塞调用，不能在主线程调用。
     * 异常：不抛异常。读 body 时，如果源站中途断开或者下载被取消，read() 抛 IOException。
     * 状态码：200 成功；400 参数缺失或 index 不是非负十进制整数；404 index 超出分片数；
     *        410 没有当前会话或 sessionId 不一致；其他 源站的非 2xx 状态码；502 没有拿到源站响应；500 内部错误。
     */
    public SegmentResponse openSegment(String sessionId, String index) {
        try {
            if (sessionId == null || !isDecimal(index)) return SegmentResponse.error(400);
            PrecacheSession session = current;
            if (session == null || !session.id().equals(sessionId)) return SegmentResponse.error(410);
            int i = parseIndex(index);
            if (i >= session.segmentCount()) return SegmentResponse.error(404);
            return session.open(i);
        } catch (Throwable t) {
            return SegmentResponse.error(500);
        }
    }

    // ---- 内部 ----

    /** 结束上一个会话；适用时开始新会话并返回改写结果（D13）。 */
    private synchronized String switchSession(HlsPlaylist playlist, String playlistUrl,
                                              Map<String, String> headers, int port) {
        PrecacheSession old = current;
        current = null;
        if (old != null) old.close();
        File root = cacheRoot;
        boolean reuse = playlist != null && root != null && old != null && old.store().playlistUrl().equals(playlistUrl);
        if (old != null && !reuse) deleteInBackground(old.store());
        if (playlist == null || root == null) return null;
        String id = Long.toString(sessionSequence.incrementAndGet(), 36);
        SegmentStore store = reuse ? old.store() : new SegmentStore(new File(root, "s" + id), playlistUrl);
        String rendered = playlist.render(segmentUrlPrefix(port, id));
        current = new PrecacheSession(id, playlist, headers, store, config, downloader, downloadExecutor);
        return rendered;
    }

    private static String segmentUrlPrefix(int port, String id) {
        return "http://127.0.0.1:" + port + SEGMENT_PATH + "?" + PARAM_SESSION + "=" + id + "&" + PARAM_INDEX + "=";
    }

    private void deleteInBackground(SegmentStore store) {
        ioExecutor.execute(store::deleteAll);
    }

    private static Map<String, String> copy(Map<String, String> headers) {
        return headers == null ? null : new HashMap<>(headers);
    }

    private static boolean isDecimal(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /** 已确认是十进制数字串；超出 int 范围的按超出分片数处理。 */
    private static int parseIndex(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static OkHttpClient defaultClient() {
        OkHttpClient client = OkGoHelper.getDefaultClient();
        return client != null ? client : FallbackClient.INSTANCE;
    }

    private static ThreadFactory daemonThreads(String name, boolean numbered) {
        AtomicInteger count = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, numbered ? name + count.incrementAndGet() : name);
            t.setDaemon(true);
            return t;
        };
    }
}
