package com.github.tvbox.osc.precache;

/** 预缓存的固定参数（design.md D1）。测试可以传别的值。 */
final class PrecacheConfig {
    /** 预缓存窗口：锚点往后的时长。 */
    final long aheadMs;
    /** 回看保留：锚点往前的时长。 */
    final long behindMs;
    final int maxConcurrency;
    /** 被限流后的并发数。 */
    final int throttledConcurrency;
    /** 累计多少次限流信号后降并发。 */
    final int throttleThreshold;
    /** 每个会话的磁盘上限。 */
    final long capBytes;
    /** 还没有已提交分片时，按这个值估计一个分片的大小。 */
    final long defaultSegmentEstimateBytes;

    static final PrecacheConfig DEFAULT = new PrecacheConfig(
            120_000, 30_000, 3, 1, 3, 300L * 1024 * 1024, 2L * 1024 * 1024);

    PrecacheConfig(long aheadMs, long behindMs, int maxConcurrency, int throttledConcurrency,
                   int throttleThreshold, long capBytes, long defaultSegmentEstimateBytes) {
        this.aheadMs = aheadMs;
        this.behindMs = behindMs;
        this.maxConcurrency = maxConcurrency;
        this.throttledConcurrency = throttledConcurrency;
        this.throttleThreshold = throttleThreshold;
        this.capBytes = capBytes;
        this.defaultSegmentEstimateBytes = defaultSegmentEstimateBytes;
    }
}
