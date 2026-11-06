package com.github.tvbox.osc.precache;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;

public class PrecacheWindowTest {

    private static final long AHEAD = 120_000;
    private static final long BEHIND = 30_000;

    private static PrecacheWindow uniform(int count, long durationMs) {
        long[] durations = new long[count];
        Arrays.fill(durations, durationMs);
        return new PrecacheWindow(durations);
    }

    @Test
    public void tenSecondSegmentsAnchoredAtTenGiveWindowTenToTwentyOneAndBehindSevenToNine() {
        PrecacheWindow window = uniform(40, 10_000);

        assertEquals(21, window.aheadEnd(10, AHEAD));
        assertEquals(7, window.behindStart(10, BEHIND));
    }

    @Test
    public void windowIsTruncatedAtTheLastSegment() {
        PrecacheWindow window = uniform(15, 10_000);

        assertEquals(14, window.aheadEnd(10, AHEAD));
        assertEquals(14, window.aheadEnd(14, AHEAD));
    }

    @Test
    public void anchorAtZeroHasNoBehindRange() {
        PrecacheWindow window = uniform(40, 10_000);

        assertEquals(11, window.aheadEnd(0, AHEAD));
        assertEquals(0, window.behindStart(0, BEHIND));
    }

    @Test
    public void unevenSegmentsUseAccumulatedStartTimes() {
        // startMs = 0, 4000, 10000, 20000, 22000, 52000, 57000
        PrecacheWindow window = new PrecacheWindow(new long[]{4000, 6000, 10000, 2000, 30000, 5000, 3000});

        assertEquals(4, window.aheadEnd(1, 20_000));   // 22000 - 4000 < 20000, 52000 - 4000 不是
        assertEquals(2, window.behindStart(3, 15_000)); // 20000 - 10000 <= 15000, 20000 - 4000 不是
        assertEquals(3, window.behindStart(4, 2_000));  // 恰好等于 behindMs 时保留
        assertEquals(5, window.aheadEnd(4, 31_000));    // 52000 - 22000 < 31000, 57000 - 22000 不是
    }

    @Test
    public void isJumpOnlyWhenRequestIsNeitherAnchorNorNext() {
        Object[][] cases = {
                // previousAnchor, requested, expected
                {-1, 0, false},
                {-1, 7, false},
                {3, 3, false},
                {3, 4, false},
                {3, 5, true},
                {3, 2, true},
                {3, 0, true},
        };
        for (Object[] c : cases) {
            assertEquals(c[0] + " -> " + c[1], c[2], PrecacheWindow.isJump((Integer) c[0], (Integer) c[1]));
        }
    }
}
