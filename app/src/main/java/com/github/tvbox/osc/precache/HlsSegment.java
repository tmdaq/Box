package com.github.tvbox.osc.precache;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** m3u8 里的一个分片：在列表里的位置、源站绝对地址、时长，以及用作磁盘文件名的 key。 */
final class HlsSegment {
    /** 在列表里的位置，从 0 开始（不是 MEDIA-SEQUENCE）。 */
    final int index;
    /** 源站绝对地址。 */
    final String url;
    final long durationMs;
    /** url 的 SHA-1，十六进制小写。 */
    final String key;

    HlsSegment(int index, String url, long durationMs) {
        this.index = index;
        this.url = url;
        this.durationMs = durationMs;
        this.key = sha1Hex(url);
    }

    private static String sha1Hex(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(s.getBytes(Charset.forName("UTF-8")));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
