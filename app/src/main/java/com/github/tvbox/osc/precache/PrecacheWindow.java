package com.github.tvbox.osc.precache;

/**
 * 按分片时长计算预缓存窗口和回看范围（design.md D9）。纯函数，时长都是整数毫秒。
 */
final class PrecacheWindow {
    /** startMs[k]：第 k 个分片的开始时间，即前 k 个分片的时长之和。 */
    private final long[] startMs;

    PrecacheWindow(long[] durationsMs) {
        startMs = new long[durationsMs.length];
        for (int k = 1; k < durationsMs.length; k++) {
            startMs[k] = startMs[k - 1] + durationsMs[k - 1];
        }
    }

    /** 满足 startMs[k] - startMs[anchor] < aheadMs 的最大 k，不超过最后一个分片。 */
    int aheadEnd(int anchor, long aheadMs) {
        int k = anchor;
        while (k + 1 < startMs.length && startMs[k + 1] - startMs[anchor] < aheadMs) k++;
        return k;
    }

    /** 满足 startMs[anchor] - startMs[k] <= behindMs 的最小 k（k <= anchor）。 */
    int behindStart(int anchor, long behindMs) {
        int k = anchor;
        while (k > 0 && startMs[anchor] - startMs[k - 1] <= behindMs) k--;
        return k;
    }

    /** 没有上一个锚点时为 false；请求的是锚点本身或下一个分片时为 false；其余为 true。 */
    static boolean isJump(int previousAnchor, int requested) {
        return previousAnchor >= 0 && requested != previousAnchor && requested != previousAnchor + 1;
    }
}
