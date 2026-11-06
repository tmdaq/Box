package com.github.tvbox.osc.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;

// 用空 Application 代替 App，避免测试启动时初始化整个应用
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class VideoParseRulerTest {

    private static final String API_URL = "https://cdn.example.com/api/getplay?id=1&token=abc";

    @Before
    public void setUp() {
        VideoParseRuler.clearRule();
    }

    @Test
    public void matchesUrlAgainstRulesOfThePageHost() {
        VideoParseRuler.addHostRule("www.site.com", new ArrayList<>(Arrays.asList("getplay", "token=")));

        assertTrue(VideoParseRuler.checkIsVideoForParse("https://www.site.com/play/1", API_URL));
        assertFalse(VideoParseRuler.checkIsVideoForParse("https://www.site.com/play/1", "https://cdn.example.com/api/getplay?id=1"));
    }

    @Test
    public void fallsBackToWildcardRulesForUnknownHost() {
        VideoParseRuler.addHostRule("*", new ArrayList<>(Arrays.asList("getplay")));

        assertTrue(VideoParseRuler.checkIsVideoForParse("https://other.site.com/play/1", API_URL));
    }

    @Test
    public void ignoresRulesWithoutPageUrl() {
        VideoParseRuler.addHostRule("*", new ArrayList<>(Arrays.asList("getplay")));

        assertFalse(VideoParseRuler.checkIsVideoForParse(null, API_URL));
    }
}
