package com.github.tvbox.osc.precache;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 锁住 spec 固定参数表里的取值。 */
public class PrecacheConfigTest {

    @Test
    public void defaultMatchesTheFixedParametersOfTheSpec() {
        PrecacheConfig c = PrecacheConfig.DEFAULT;

        assertEquals("预缓存窗口 120 秒", 120_000, c.aheadMs);
        assertEquals("回看保留 30 秒", 30_000, c.behindMs);
        assertEquals("并发下载最多 3 个", 3, c.maxConcurrency);
        assertEquals("被限流后 1 个", 1, c.throttledConcurrency);
        assertEquals("累计 3 次限流信号后降并发", 3, c.throttleThreshold);
        assertEquals("每个会话 300MB", 300L * 1024 * 1024, c.capBytes);
        assertEquals("没有已提交分片时按 2MiB 估计", 2L * 1024 * 1024, c.defaultSegmentEstimateBytes);
    }
}
