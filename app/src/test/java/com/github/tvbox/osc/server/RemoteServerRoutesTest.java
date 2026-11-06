package com.github.tvbox.osc.server;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.event.ServerEvent;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.zip.GZIPInputStream;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Method;
import fi.iki.elonen.NanoHTTPD.Response;

/**
 * RemoteServer.serve 现有路由的 characterization test：锁住新增分片代理路由可能影响到的行为。
 * 见 openspec/changes/add-segment-proxy-endpoint/design.md D7。
 */
// 用空 Application 代替 App，避免测试启动时初始化整个应用
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class RemoteServerRoutesTest {

    private static final String M3U8 = "#EXTM3U\n#EXTINF:5,\nhttps://cdn.a.com/v/seg0.ts\n#EXT-X-ENDLIST\n";
    private static final String HTML_UTF8 = NanoHTTPD.MIME_HTML + "; charset=utf-8";

    private Context context;
    private RemoteServer server;
    private String savedM3u8Content;
    private String savedVodName;
    private String savedArtist;

    @Before
    public void setUp() {
        savedM3u8Content = RemoteServer.m3u8Content;
        savedVodName = RemoteServer.vodName;
        savedArtist = RemoteServer.artist;
        context = ApplicationProvider.getApplicationContext();
        server = new RemoteServer(0, context);
    }

    @After
    public void tearDown() {
        server.stop();
        RemoteServer.m3u8Content = savedM3u8Content;
        RemoteServer.vodName = savedVodName;
        RemoteServer.artist = savedArtist;
    }

    @Test
    public void m3u8ReturnsCurrentContentAsPlainText() throws IOException {
        RemoteServer.m3u8Content = M3U8;

        Response response = server.serve(session(Method.GET, "/m3u8"));

        assertEquals(Response.Status.OK, response.getStatus());
        assertEquals(NanoHTTPD.MIME_PLAINTEXT, response.getMimeType());
        assertEquals(M3U8, bodyText(response));
    }

    @Test
    public void rootIsServedByGetRequestList() throws IOException {
        assertDefaultPage(server.serve(session(Method.GET, "/")));
    }

    @Test
    public void unknownPathFallsBackToDefaultPage() throws IOException {
        assertDefaultPage(server.serve(session(Method.GET, "/no-such-path")));
    }

    @Test
    public void proxyWithoutDoOrGoFallsBackToDefaultPage() throws IOException {
        assertDefaultPage(server.serve(session(Method.GET, "/proxy")));
    }

    @Test
    public void mediaReturnsTitleAndArtistAsJson() throws Exception {
        RemoteServer.vodName = "影片";
        RemoteServer.artist = "第1集";

        Response response = server.serve(session(Method.GET, "/media"));

        assertEquals(Response.Status.OK, response.getStatus());
        // NanoHTTPD 发现文本不能用 ASCII 编码时，会自己补上 charset=UTF-8
        assertEquals(NanoHTTPD.MIME_PLAINTEXT + "; charset=UTF-8", response.getMimeType());
        JSONObject json = new JSONObject(bodyText(response));
        assertEquals("影片", json.getString("title"));
        assertEquals("第1集", json.getString("artist"));
    }

    @Test
    public void postToUnknownPathFallsBackToDefaultPage() throws IOException {
        assertDefaultPage(server.serve(session(Method.POST, "/no-such-path")));
    }

    @Test
    public void everyRequestPostsServerConnection() {
        EventRecorder recorder = new EventRecorder();
        EventBus.getDefault().register(recorder);
        try {
            server.serve(session(Method.GET, "/m3u8"));
            server.serve(session(Method.GET, "/no-such-path"));
            server.serve(session(Method.POST, "/no-such-path"));
        } finally {
            EventBus.getDefault().unregister(recorder);
        }

        assertEquals(3, recorder.types.size());
        for (int type : recorder.types) {
            assertEquals(ServerEvent.SERVER_CONNECTION, type);
        }
    }

    @Test
    public void m3u8OverSocketIsGzippedOnlyWhenAccepted() throws IOException {
        RemoteServer.m3u8Content = M3U8;
        server.start();
        int port = server.getListeningPort();

        RawHttpClient.Response gzipped = RawHttpClient.get(port, "/m3u8",
                Collections.singletonMap("Accept-Encoding", "gzip"));
        RawHttpClient.Response plain = RawHttpClient.get(port, "/m3u8");

        assertEquals("gzip", gzipped.header("Content-Encoding"));
        assertEquals(M3U8, gunzip(gzipped.body));
        assertNull(plain.header("Content-Encoding"));
        assertEquals(String.valueOf(M3U8.getBytes(StandardCharsets.UTF_8).length), plain.header("Content-Length"));
        assertEquals(M3U8, new String(plain.body, StandardCharsets.UTF_8));
    }

    private void assertDefaultPage(Response response) throws IOException {
        assertEquals(Response.Status.OK, response.getStatus());
        assertEquals(HTML_UTF8, response.getMimeType());
        assertArrayEquals(readAll(context.getResources().openRawResource(R.raw.index)), readAll(response.getData()));
    }

    private static IHTTPSession session(Method method, String uri) {
        IHTTPSession session = mock(IHTTPSession.class);
        when(session.getMethod()).thenReturn(method);
        when(session.getUri()).thenReturn(uri);
        when(session.getParms()).thenReturn(new HashMap<String, String>());
        when(session.getHeaders()).thenReturn(new HashMap<String, String>());
        when(session.getRemoteIpAddress()).thenReturn("127.0.0.1");
        return session;
    }

    private static String bodyText(Response response) throws IOException {
        return new String(readAll(response.getData()), StandardCharsets.UTF_8);
    }

    private static String gunzip(byte[] compressed) throws IOException {
        return new String(readAll(new GZIPInputStream(new ByteArrayInputStream(compressed))), StandardCharsets.UTF_8);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream input = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n = input.read(buffer); n >= 0; n = input.read(buffer)) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    public static class EventRecorder {
        final List<Integer> types = new ArrayList<>();

        @Subscribe
        public void onEvent(ServerEvent event) {
            types.add(event.type);
        }
    }
}
