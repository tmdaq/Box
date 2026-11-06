package com.github.tvbox.osc.precache;

import java.io.File;
import java.util.Map;

/**
 * HLS 预缓存的门面，进程内单例。
 * 接口语义见 add-hls-precache-core 的 design.md「跨 change 接口」。
 * 当前是接口骨架：rewrite 一律不适用，openSegment 一律返回 410，实现由 add-hls-precache-core 的任务补全。
 */
public final class HlsPrecache {

    /** 缓存根目录名。 */
    public static final String CACHE_DIR_NAME = "hlscache";

    /** 分片代理路径。 */
    public static final String SEGMENT_PATH = "/hlsseg";
    /** query 参数：会话标识。 */
    public static final String PARAM_SESSION = "s";
    /** query 参数：分片序号，从 0 开始，表示分片在列表里的位置。 */
    public static final String PARAM_INDEX = "i";

    private static final HlsPrecache INSTANCE = new HlsPrecache();

    private volatile File cacheRoot;

    private HlsPrecache() {
    }

    /** 取进程内单例。线程安全，不做 IO。 */
    public static HlsPrecache get() {
        return INSTANCE;
    }

    /** 设置缓存根目录。只记下路径，不做 IO。 */
    public void setCacheRoot(File cacheRoot) {
        this.cacheRoot = cacheRoot;
    }

    /** 改写 m3u8 并开始新的预缓存会话；不适用时返回 null。不抛异常。 */
    public String rewrite(String playlistUrl, String content, Map<String, String> headers, int port) {
        return null;
    }

    /** 结束当前会话。不阻塞，可以重复调用，不抛异常。 */
    public void endSession() {
    }

    /** 分片代理的入口，阻塞调用，返回值不为 null，不抛异常。 */
    public SegmentResponse openSegment(String sessionId, String index) {
        return SegmentResponse.error(410);
    }
}
