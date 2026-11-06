package com.github.tvbox.osc.server;

import static org.junit.Assert.assertEquals;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;

import fi.iki.elonen.NanoHTTPD;

/**
 * 不覆盖测试接缝的真实链路：RemoteServer.openSegment → HlsPrecache.openSegment → SegmentProxyResponse.from。
 * 只用不依赖会话状态的请求（缺参数 400、未知会话 410），不改 HlsPrecache 单例的状态，也不发网络请求。
 * 会话标识是时间戳的 36 进制，"zzz" 不会和任何真实会话撞上。
 */
// 用空 Application 代替 App，避免测试启动时初始化整个应用
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class RemoteServerSegmentEndToEndTest {

    private RemoteServer server;

    @Before
    public void setUp() throws IOException {
        server = new RemoteServer(0, ApplicationProvider.getApplicationContext());
        server.start();
    }

    @After
    public void tearDown() {
        server.stop();
    }

    @Test
    public void unknownSessionIsGone() throws IOException {
        assertEmptyPlainText("HTTP/1.1 410 Gone", get("/hlsseg?s=zzz&i=0"));
    }

    @Test
    public void missingParamsAreBadRequest() throws IOException {
        assertEmptyPlainText("HTTP/1.1 400 Bad Request", get("/hlsseg"));
    }

    private RawHttpClient.Response get(String path) throws IOException {
        return RawHttpClient.get(server.getListeningPort(), path);
    }

    private static void assertEmptyPlainText(String statusLine, RawHttpClient.Response response) {
        assertEquals(statusLine, response.statusLine);
        assertEquals(NanoHTTPD.MIME_PLAINTEXT, response.header("Content-Type"));
        assertEquals("0", response.header("Content-Length"));
        assertEquals(0, response.body.length);
    }
}
