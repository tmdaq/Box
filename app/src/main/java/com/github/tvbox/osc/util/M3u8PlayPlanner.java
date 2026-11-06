package com.github.tvbox.osc.util;

import java.util.Map;

/** playUrl 的判断：要不要先拉 m3u8、找子列表、去广告与预缓存怎么组合、最终交给播放器什么。PlayFragment 和 PlayActivity 共用。 */
public final class M3u8PlayPlanner {

    /** 对应 M3U8.purify，调用方传 M3U8::purify。 */
    public interface Purifier { String purify(String tsUrlPre, String content); }

    /** 对应 HlsPrecache.rewrite，调用方传 HlsPrecache.get()::rewrite。 */
    public interface Rewriter { String rewrite(String playlistUrl, String content, Map<String, String> headers, int port); }

    private M3u8PlayPlanner() {
    }

    /**
     * 拉取前：是否要先下载 m3u8。
     * @param purify   广告过滤开关
     * @param precache 本次播放可以走预缓存：预缓存开关打开、不是外部播放器、调用点允许
     */
    public static boolean shouldFetch(String url, boolean purify, boolean precache) {
        return (purify || precache) && (url.contains("://127.0.0.1/") || url.contains(".m3u8"));
    }

    /** 第一层内容是不是 m3u8。content 为 null 时抛 NPE，和现状一致。 */
    public static boolean isPlaylist(String content) {
        return content.startsWith("#EXTM3U");
    }

    /** 只看前 10 行，只有一个子列表时返回它的绝对地址，否则返回 ""。 */
    public static String findForwardUrl(String url, String content) {
        String[] lines = content.split(content.contains("\r\n") ? "\r\n" : "\n", 10);
        String forwardurl = "";
        boolean dealedFirst = false;
        for (String line : lines) {
            if (!"".equals(line) && line.charAt(0) != '#') {
                if (dealedFirst) {
                    //跳转行后还有内容，说明不需要跳转
                    forwardurl = "";
                    break;
                }
                if (line.endsWith(".m3u8") || line.contains(".m3u8?")) {
                    if (line.startsWith("http://") || line.startsWith("https://")) {
                        forwardurl = line;
                    } else if (line.charAt(0) == '/') {
                        int ifirst = url.indexOf('/', 9);//skip https://, http://
                        forwardurl = url.substring(0, ifirst) + line;
                    } else {
                        int ilast = url.lastIndexOf('/');
                        forwardurl = url.substring(0, ilast + 1) + line;
                    }
                }
                dealedFirst = true;
            }
        }
        return forwardurl;
    }

    /**
     * 拿到最终要播的那份 m3u8 后，决定交给播放器什么。不做 IO（purifier / rewriter 由调用方注入）。
     * @param playlistUrl 这份 m3u8 的真实地址：没跟随时是 url，跟随过时是 finalforwardurl
     * @param port        RemoteServer.serverPort
     */
    public static Plan plan(String playlistUrl, String content, Map<String, String> headers,
                            boolean purify, boolean precache, int port,
                            Purifier purifier, Rewriter rewriter) {
        String local = "http://127.0.0.1:" + port + "/m3u8";
        String purified = purify ? purifier.purify(directoryOf(playlistUrl), content) : null;
        if (precache) {
            String rewritten = rewriter.rewrite(playlistUrl, purified != null ? purified : content, headers, port);
            if (rewritten != null) return new Plan(local, true, rewritten, true);
        }
        if (purify) return new Plan(purified != null ? local : playlistUrl, true, purified, false);
        return new Plan(playlistUrl, false, null, false);
    }

    /** 地址最后一个 '/' 及之前的部分，用来解析列表里的相对分片地址。 */
    private static String directoryOf(String playlistUrl) {
        return playlistUrl.substring(0, playlistUrl.lastIndexOf('/') + 1);
    }

    public static final class Plan {
        /** 交给 startPlayUrl 的地址。 */
        public final String playUrl;
        /** 是否写 RemoteServer.m3u8Content。 */
        public final boolean setM3u8Content;
        /** 要写入的内容；可以是 null（沿用现状：purify 返回 null 时也写 null）。 */
        public final String m3u8Content;
        /** true 表示 m3u8Content 是 rewrite 的结果，startPlayUrl 时保留预缓存会话。 */
        public final boolean precached;

        Plan(String playUrl, boolean setM3u8Content, String m3u8Content, boolean precached) {
            this.playUrl = playUrl;
            this.setM3u8Content = setM3u8Content;
            this.m3u8Content = m3u8Content;
            this.precached = precached;
        }
    }
}
