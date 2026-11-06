package com.github.tvbox.osc.precache;

import androidx.media3.common.util.UriUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析和改写单码率点播 m3u8（design.md D2）。纯函数，不做 IO。
 * 改写时只替换分片 URI 行，并把 KEY、MAP 的 URI 补成绝对地址，其余字符逐字保留。
 */
final class HlsPlaylist {
    private static final long DEFAULT_DURATION_MS = 10_000;
    private static final String[] UNSUPPORTED_TAGS = {
            "#EXT-X-STREAM-INF", "#EXT-X-I-FRAME-STREAM-INF", "#EXT-X-BYTERANGE", "#EXT-X-PART", "#EXT-X-PRELOAD-HINT",
    };
    private static final Pattern URI_ATTRIBUTE = Pattern.compile("[:,]\\s*URI=\"([^\"]*)\"");

    private final List<HlsSegment> segments;
    /** 分片 URI 行之间的原文：literals[i] 在第 i 个分片之前，最后一个在所有分片之后。 */
    private final List<String> literals;

    private HlsPlaylist(List<HlsSegment> segments, List<String> literals) {
        this.segments = Collections.unmodifiableList(segments);
        this.literals = literals;
    }

    /** 不适用时返回 null。 */
    static HlsPlaylist parse(String playlistUrl, String content) {
        if (content == null || !content.startsWith("#EXTM3U")) return null;
        if (!isHttpUrl(playlistUrl)) return null;
        return new Parser(playlistUrl).parse(content);
    }

    private static boolean isHttpUrl(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    /** 不可修改，按出现顺序，index 从 0 开始。 */
    List<HlsSegment> segments() {
        return segments;
    }

    /** 第 i 个分片 URI 行替换成 segmentUrlPrefix + i，其余部分逐字输出。 */
    String render(String segmentUrlPrefix) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            out.append(literals.get(i)).append(segmentUrlPrefix).append(i);
        }
        return out.append(literals.get(segments.size())).toString();
    }

    private static final class Parser {
        private final String playlistUrl;
        private final List<String> urls = new ArrayList<>();
        /** 每个分片的 EXTINF 时长，没有或解析不了时为 -1。 */
        private final List<Long> extinfMs = new ArrayList<>();
        private final List<String> literals = new ArrayList<>();
        private final StringBuilder literal = new StringBuilder();
        private long pendingExtinfMs = -1;
        private long targetDurationMs = -1;
        private boolean hasEndList;

        Parser(String playlistUrl) {
            this.playlistUrl = playlistUrl;
        }

        HlsPlaylist parse(String content) {
            int start = 0;
            while (start < content.length()) {
                int newline = content.indexOf('\n', start);
                int end = newline < 0 ? content.length() : newline + 1;
                if (!line(content.substring(start, end))) return null;
                start = end;
            }
            if (!hasEndList || urls.isEmpty()) return null;
            literals.add(literal.toString());
            return new HlsPlaylist(segments(), literals);
        }

        /** 处理一行（含行尾）。遇到不适用的标签时返回 false。 */
        private boolean line(String line) {
            String eol = line.endsWith("\r\n") ? "\r\n" : line.endsWith("\n") ? "\n" : "";
            String body = line.substring(0, line.length() - eol.length());
            String trimmed = body.trim();
            for (String tag : UNSUPPORTED_TAGS) {
                if (trimmed.startsWith(tag)) return false;
            }
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                literal.append(tag(trimmed, body)).append(eol);
            } else {
                urls.add(UriUtil.resolve(playlistUrl, trimmed));
                extinfMs.add(pendingExtinfMs);
                pendingExtinfMs = -1;
                literals.add(literal.toString());
                literal.setLength(0);
                literal.append(eol);
            }
            return true;
        }

        /** 处理标签行，返回要输出的原文（KEY、MAP 的 URI 已补成绝对地址）。 */
        private String tag(String trimmed, String body) {
            if (trimmed.startsWith("#EXT-X-ENDLIST")) {
                hasEndList = true;
            } else if (trimmed.startsWith("#EXTINF:")) {
                pendingExtinfMs = seconds(trimmed.substring("#EXTINF:".length()));
            } else if (trimmed.startsWith("#EXT-X-TARGETDURATION:")) {
                targetDurationMs = seconds(trimmed.substring("#EXT-X-TARGETDURATION:".length()));
            } else if (trimmed.startsWith("#EXT-X-KEY") || trimmed.startsWith("#EXT-X-MAP")) {
                return resolveUriAttribute(body);
            }
            return body;
        }

        private String resolveUriAttribute(String body) {
            Matcher m = URI_ATTRIBUTE.matcher(body);
            if (!m.find()) return body;
            return body.substring(0, m.start(1)) + UriUtil.resolve(playlistUrl, m.group(1)) + body.substring(m.end(1));
        }

        private List<HlsSegment> segments() {
            long fallback = targetDurationMs >= 0 ? targetDurationMs : DEFAULT_DURATION_MS;
            List<HlsSegment> segments = new ArrayList<>(urls.size());
            for (int i = 0; i < urls.size(); i++) {
                long duration = extinfMs.get(i) >= 0 ? extinfMs.get(i) : fallback;
                segments.add(new HlsSegment(i, urls.get(i), duration));
            }
            return segments;
        }

        /** "<秒>[,标题]" 换算成毫秒（四舍五入）；解析不了时返回 -1。 */
        private static long seconds(String value) {
            int comma = value.indexOf(',');
            String number = (comma < 0 ? value : value.substring(0, comma)).trim();
            try {
                double s = Double.parseDouble(number);
                return s >= 0 && !Double.isInfinite(s) ? Math.round(s * 1000) : -1;
            } catch (NumberFormatException e) {
                return -1;
            }
        }
    }
}
