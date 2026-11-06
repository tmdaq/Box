## Context

动机见 proposal.md 的 Why；方案选型见 `docs/adr/0001-hls-precache-via-local-segment-proxy.md`；术语见 `GLOSSARY.md`（分片代理、预缓存会话）；行为要求见 `specs/remote-control-push/spec.md`。

本 change 只依赖 S1（`openspec/changes/add-hls-precache-core/design.md`「跨 change 接口」一节）的以下 public API：

- `HlsPrecache.SEGMENT_PATH`（`"/hlsseg"`）、`PARAM_SESSION`（`"s"`）、`PARAM_INDEX`（`"i"`）
- `HlsPrecache.get().openSegment(sessionId, index)`：阻塞，不抛异常，返回值不为 null；调用方必须把它发出去，或者调用 `close()`
- `SegmentResponse` 的 public 字段 `status`、`body`、`length`、`contentType`，以及 `close()`、`UNKNOWN_LENGTH`

相关现状（行号基于 main 060d8cd）：

- **`RemoteServer.serve`**（`server/RemoteServer.java:130-288`）：
  - :131 先 `EventBus.getDefault().post(new ServerEvent(SERVER_CONNECTION))`。唯一的订阅者 `UserFragment.server` 是空方法。
  - :133-136 取 `getUri().trim()`，再去掉 `?` 之后的部分，得到 `fileName`。
  - GET 分支先遍历 `getRequestList`（:138，7 个 `RawRequestProcess`，按 `equalsIgnoreCase` 匹配 `/`、`/index.html` 等），然后依次是 `/media`、`/proxy`、`/file/`、`/dns-query`、`/m3u8`、`/dash/`。
  - 都没有命中时落到 :287，用 `getRequestList.get(0)` 返回 `R.raw.index`，也就是默认页。
  - `/proxy` 既没有 `do` 也没有 `go` 时没有 return，同样落到默认页。
- **NanoHTTPD 2.3.1**（已用 javap 核实 jar `nanohttpd-nanohttpd-project-2.3.1.jar`）：
  - `IHTTPSession` 有 `getRemoteIpAddress()`。`HTTPSession` 构造时，如果对端 `InetAddress.isLoopbackAddress()` 或 `isAnyLocalAddress()`，把 `remoteIp` 设成字面量 `"127.0.0.1"`；否则设成 `getHostAddress()`。也就是说，127.0.0.0/8、`::1`，以及 Java 自动转成 `Inet4Address` 的 IPv4 映射回环地址，都会被归一成 `"127.0.0.1"`。
  - `HTTPSession.execute()`：`r = serve(this)` 之后，调用 `r.setGzipEncoding(useGzipWhenAccepted(r) && Accept-Encoding 含 "gzip")`，然后 `r.send(out)`。不论成功、`SocketException` 还是其他异常，都会在 finally 里 `safeClose(r)`。
  - `useGzipWhenAccepted(r)` 的默认实现：MIME 含 `text/` 或 `/json` 时为 true。
  - `Response.send()`：
    - 写 IO 失败时在内部 catch 并记日志，不往外抛；
    - `encodeAsGzip` 为 true 时写 `Content-Encoding: gzip`，并改成 chunked；
    - 已经有 `content-length` 头时，不启用 gzip。
  - `Response.close()` 会关闭 `data`。
  - `Response` 的构造器是 protected 的 `Response(IStatus, String mime, InputStream data, long totalBytes)`：
    - `data == null` 时换成空流，长度为 0；
    - `totalBytes < 0` 时用 chunked。
    - `newFixedLengthResponse(st, mime, in, len)` 和 `newChunkedResponse(st, mime, in)`（`len = -1`）都只是直接调这个构造器。
  - `Response.Status` 枚举里没有 502、504。`Status.lookup` 遇到不在枚举里的状态码返回 null。
- **测试基础设施**：JUnit 4.13.2、Mockito 4.11.0、Robolectric 4.11.1、`androidx.test:core` 都已经有了。`includeAndroidResources = true`，所以 Robolectric 能读到 `R.raw.index`。测试里不需要 MockWebServer。

## Goals / Non-Goals

**Goals:**

- 在 S1 只提交了接口骨架的分支上，S2 的全部单测就能跑通：测试运行时不调用 `HlsPrecache`。
- 用 characterization test 锁住现有路由，加了新路由以后这些测试原样通过。
- 响应头不带 `Content-Encoding` 要在真实的 HTTP 字节流上验证，不能只看 `Response` 对象。

**Non-Goals:**

- 不给 `HlsPrecache` 做测试替身，也不依赖 S1 的包内成员（比如 `SegmentResponse` 的包内构造器）。
- 不重构 `serve()` 的 if/else 结构，只插入一个分支。

## Decisions

### D1 路由位置

在 `serve()` 的 GET 分支里，作为第一条语句插入，放在 `getRequestList` 循环之前：

```java
if (session.getMethod() == Method.GET) {
    if (fileName.equals(HlsPrecache.SEGMENT_PATH)) {
        return serveSegment(session);
    }
    for (RequestProcess process: getRequestList) { ... }   // 以下不变
```

- **为什么在 GET 分支里**：需求是只处理 GET。POST、HEAD 等方法请求 `/hlsseg` 时，不进入分片代理，按现有逻辑处理（落到默认页）。
- **为什么在 `getRequestList` 之前**：现在没有任何 `RawRequestProcess` 或后续分支会匹配 `/hlsseg`，所以放在哪里结果都一样。放在最前面，是为了以后别人往 `getRequestList` 加条目（`equalsIgnoreCase` 匹配）时，不会意外截走分片请求。同时它也不会影响其他路由，因为 `/hlsseg` 之前只会落到默认页。
- **匹配方式**：`fileName.equals(SEGMENT_PATH)`，区分大小写，和 S1 的约定一致。`fileName` 已经 trim 过，也去掉了 query。
- **EventBus**：`/hlsseg` 和其他路由一样，经过 :131 的 `post(SERVER_CONNECTION)`，不另做处理。每个分片多一次 post，订阅者是空方法，开销可以忽略。这样也不用为了它改动 :131。

### D2 loopback 判定

```java
static boolean isLoopback(String remoteIp) {
    return "127.0.0.1".equals(remoteIp)
            || "::1".equals(remoteIp)
            || "0:0:0:0:0:0:0:1".equals(remoteIp)
            || "::ffff:127.0.0.1".equals(remoteIp);
}
```

- **数据来源**：`session.getRemoteIpAddress()`，不看任何请求头（`X-Forwarded-For`、NanoHTTPD 放进 headers 的 `remote-addr` 等）。客户端能伪造请求头，但伪造不了套接字的对端地址。
- **放行 IPv6 回环的理由**：
  - 按 NanoHTTPD 2.3.1 的实现，真实的回环连接一律被归一成 `"127.0.0.1"`，所以实际只会出现这一个值。
  - 另外三种字面量本身就是回环地址，放行它们不会放进任何非本机的请求。
  - 这样做是防御性的：以后升级 NanoHTTPD，或者换一个 `IHTTPSession` 实现直接返回 `getHostAddress()`（Java 对 `::1` 返回 `0:0:0:0:0:0:0:1`）时，本机播放不会突然变成 403。
- **只做字面量比较，不用 `InetAddress.getByName` 解析**。原因有两个：
  - `getByName(null)` 和 `getByName("")` 都会返回回环地址，会把「拿不到对端地址」误判成本机；
  - 解析非字面量的字符串还可能触发 DNS 查询。
- **其他情况一律返回 403**：null、空串、局域网地址，以及 `127.0.0.2` 这类 NanoHTTPD 实际上不会产生的字面量。
- **403 不调用核心**：既不调用 `HlsPrecache.get()`，也不调用 `openSegment`。
- **`adb forward` 进来的连接**：对端是本机，会被放行。能用 adb 的人本来就能完全控制设备，可以接受；手动验证也正好利用这一点（见 tasks 3.1）。

### D3 `IStatus` 的实现与 Response 的构造（新类 `SegmentProxyResponse`）

新增包内可见的 `server/SegmentProxyResponse.java`：

```java
final class SegmentProxyResponse extends NanoHTTPD.Response {
    /** 默认路径：把 S1 的结果转成响应。r 的所有权交给返回的响应。 */
    static SegmentProxyResponse from(SegmentResponse r) {
        return create(r.status, r.contentType, r.body, r.length, r);
    }
    /** 本端生成的空响应：403、500。Content-Type 为 text/plain，body 为空。 */
    static SegmentProxyResponse empty(int status);
    /**
     * body == null      → 空的定长响应（长度 0）
     * length >= 0       → 定长响应
     * 其他（length < 0） → chunked 响应
     * resource 可以为 null；不为 null 时，响应关闭时一起关闭它。
     */
    static SegmentProxyResponse create(int status, String contentType, InputStream body,
                                       long length, Closeable resource);

    static final class SegmentStatus implements NanoHTTPD.Response.IStatus {
        public int getRequestStatus() { return code; }
        public String getDescription() { return code + " " + reasonPhrase(code); }
    }
}
```

- **为什么继承 `Response`，而不是照抄 S1 示意里的 `newFixedLengthResponse` / `newChunkedResponse`**：
  - 需要让 `RemoteServer.useGzipWhenAccepted` 认出分片代理的响应（D5），继承得到一个可以用 `instanceof` 判断的类型。
  - 这两个静态工厂方法本来也只是直接调 `Response` 的 protected 构造器（`newChunkedResponse` 传 `-1`），所以三种情况的语义和 S1 的用法示意完全一致：`body == null` 是空定长，`length >= 0` 是定长，否则是 chunked。
- **`SegmentStatus`**：自己实现 `IStatus`，不用 `Status.lookup`。
  - NanoHTTPD 写状态行时是 `"HTTP/1.1 " + getDescription() + " \r\n"`，所以 `getDescription()` 必须以状态码开头。
  - 原因短语用一张小表：200 OK、400 Bad Request、403 Forbidden、404 Not Found、410 Gone、429 Too Many Requests、500 Internal Server Error、502 Bad Gateway、503 Service Unavailable、504 Gateway Timeout。
  - 表里没有的状态码按类别兜底：1xx Informational、2xx Successful、3xx Redirection、4xx Client Error、5xx Server Error。客户端只看状态码，原因短语只是给人看的。
- **只设置 Content-Type 和长度相关的头**：不调用 `addHeader`，不转发源站的任何响应头。
- **`contentType` 原样传入**：S1 保证它不为 null；`empty()` 用 `NanoHTTPD.MIME_PLAINTEXT`。

### D4 body 的关闭

- **统一包一层流**：`create()` 总是把 body（为 null 时换成空的 `ByteArrayInputStream`）包进一个包内的 `FilterInputStream` 子类，再交给 `Response`。它的 `close()` 先关闭被包装的流，然后在 finally 里关闭 `resource`，也就是 `SegmentResponse` 本身；用一个布尔标记保证 `resource.close()` 最多调用一次。
  - S1 只承诺「`close()` 关闭 body」，没有承诺「关闭 body 等于释放 `SegmentResponse`」（纯转发时它持有 OkHttp 的 `Source`）。所以这里显式调用 `r.close()`，不依赖 body 流的实现。
- **NanoHTTPD 保证一定会调用 `Response.close()`**：`HTTPSession.execute()` 在正常发送完后会 `safeClose(r)`；出现 `SocketException`、`IOException`，或者 `send()` 内部吞掉了写失败，最后也都会走到 finally 里的 `safeClose(r)`。`Response.close()` 关闭 `data`，也就是我们包的那层流，于是 `SegmentResponse` 跟着被关闭。所以：
  - 发送完毕：`send()` 读到 EOF，返回之后 `safeClose(r)` 关闭流。
  - 播放器中途断开：写 socket 抛 IOException，被 `send()` 吞掉记日志，之后 `safeClose(r)` 关闭流。S1 那边的读者随之释放；按 S1 D7，读者关闭不会取消下载。
- **`serveSegment` 的异常兜底**：
  - `openSegment` 按契约不抛异常。但在 S1 只有骨架、或者出了意外的 bug 时，`serveSegment` 会 catch Throwable，返回 `empty(500)`，不让 NanoHTTPD 线程把异常抛出去。
  - 这时 `SegmentResponse` 要么还没拿到，要么已经交给了 `create()`；`create()` 只做对象构造，不会在拿到 `r` 之后、交给响应之前抛异常，所以不会泄漏。

### D5 不带 `Content-Encoding`（A7）

**问题**：S1 design 里说「不转发源站响应头，Content-Encoding 天然不会出现」，这句话不完整。NanoHTTPD 会自己加 gzip：只要响应的 MIME 含 `text/`，并且请求带了 `Accept-Encoding: gzip`，就会写出 `Content-Encoding: gzip`。分片代理会遇到以下几种 `text/*` 响应：

- 错误响应：S1 规定是 `text/plain`；
- 字幕分片：`.vtt` 推断为 `text/vtt`；
- 源站把 `.ts` 标成 `text/plain`：这种情况并不少见。

**做法**：`RemoteServer` 覆盖 `useGzipWhenAccepted`：

```java
@Override
protected boolean useGzipWhenAccepted(Response r) {
    return !(r instanceof SegmentProxyResponse) && super.useGzipWhenAccepted(r);
}
```

- 对其他路由的响应，结果和原来完全一样，`/m3u8` 的 gzip 行为不变（由 1.1 的测试锁住）。
- **否决的方案**：
  - 给定长响应加 `Content-Length` 头来关掉 gzip：chunked 响应没法这样做。
  - 在 `serve` 里调用 `setGzipEncoding(false)`：会被 `execute()` 在 `serve` 之后覆盖。

### D6 测试怎么替换核心：可覆盖的包内方法

`RemoteServer` 增加一个包内可见、非 final 的方法，作为唯一的测试接缝：

```java
/** 测试接缝：分片代理的实际处理。默认交给 HLS 预缓存核心。 */
Response openSegment(String sessionId, String index) {
    return SegmentProxyResponse.from(HlsPrecache.get().openSegment(sessionId, index));
}

private Response serveSegment(IHTTPSession session) {
    if (!isLoopback(session.getRemoteIpAddress())) return SegmentProxyResponse.empty(403);
    Map<String, String> parms = session.getParms();
    try {
        return openSegment(parms.get(HlsPrecache.PARAM_SESSION), parms.get(HlsPrecache.PARAM_INDEX));
    } catch (Throwable th) {
        return SegmentProxyResponse.empty(500);
    }
}
```

- 测试用匿名子类 `new RemoteServer(0, context) { @Override Response openSegment(...) {...} }`：记录收到的参数，返回 `SegmentProxyResponse.create(...)` 构造的响应，或者抛异常。
- **为什么不在 `serve` 和 `HlsPrecache` 之间注入一个接口**：接口的返回值只能是 `SegmentResponse`，而它的构造器是包内可见的，`server` 包里的测试造不出来。要么改 S1 的 public API，要么依赖 S1 的包内成员，两者都不允许。改为在「已经转成 NanoHTTPD 响应」这一层替换，测试就只需要用到本 change 自己的 `SegmentProxyResponse.create`。
- **为什么用子类覆盖，而不是加一个字段**：不增加字段和接口类型，`ControlManager` 构造 `RemoteServer` 的方式也不用改。
- **S1 只提交骨架时也能测**：测试运行时不会执行 `HlsPrecache.get()`。默认的 `openSegment` 只在真实请求里才会被调用。
- **没有被单测覆盖的部分**：默认 `openSegment` 的那一行，以及 `from()` 里的字段映射。它们是两行直接的赋值，靠代码评审和集成后的手动验证（tasks 3.1）覆盖。
- **结论：不需要改 S1。**

### D7 测试设计与 characterization test 的范围

测试都放在 `app/src/test/java/com/github/tvbox/osc/server/`：

| 文件 | 运行方式 | 内容 |
| --- | --- | --- |
| `RemoteServerRoutesTest` | Robolectric + `@Config(application = Application.class)` | 现有路由的 characterization test（1.1） |
| `SegmentProxyResponseTest` | 纯 JUnit | 状态码和原因短语、Content-Type、body 为 null 时为空、关闭语义（2.1） |
| `RemoteServerSegmentRouteTest` | Robolectric + `@Config(application = Application.class)` | 路由、loopback、异常兜底，以及真实 HTTP 字节流上的头和关闭行为（2.2） |
| `RawHttpClient` | 测试辅助类 | 用 `java.net.Socket` 发原始请求，解析状态行、响应头和 body，支持解 chunked，不做任何透明解压 |

**两种测法**：

- **mock session**：直接调用 `server.serve(session)`，不启动服务。
  - `IHTTPSession` 用 Mockito mock，桩住 `getUri`、`getMethod`、`getParms`（可修改的 `HashMap`）、`getHeaders`（可修改的 `HashMap`）和 `getRemoteIpAddress`。
  - `parseBody` 不做任何事。
- **真实 socket**：`new RemoteServer(0, context)` 后调用 `start()`，端口 0 由系统分配，用 `getListeningPort()` 取实际端口，`@After` 里 `stop()`。
  - 请求都带 `Connection: close`，这样 NanoHTTPD 发完响应就关闭连接，客户端读到 EOF 即可。
  - 不碰静态字段 `serverPort`。

**依赖的处理**：

- **EventBus**：在 Robolectric 下，`EventBus.getDefault()` 不需要任何桩就能用。没有订阅者时只会发一个 `NoSubscriberEvent`，记一条日志。
- **`App.getInstance()`**：只有 `/dash/` 用到，测试不覆盖这个分支。
- **`ApiConfig`、`Proxy`、`OkGoHelper`、`Environment`**：只在 `/proxy?do`、`/proxy?go`、`/dns-query`、`/file/` 分支里用到，测试同样不覆盖。
- **Context**：用 `ApplicationProvider.getApplicationContext()`。`RawRequestProcess` 用它读 `R.raw.index`，测试的期望值也从同一个资源读出来。
- **静态字段**：`m3u8Content`、`vodName`、`artist` 在 `@Before` 里记下原值，`@After` 里还原。

**characterization test（1.1）的范围**：只锁住「新路由可能影响到」以及「需求点名」的行为，不追求覆盖所有分支。

1. `GET /m3u8` 返回 200，MIME 是 `text/plain`，body 等于 `m3u8Content`。
2. `GET /`：命中 `getRequestList`，返回 200，MIME 是 `text/html; charset=utf-8`，body 是 `R.raw.index`。
3. 未知路径（`GET /no-such-path`）落到默认页，结果和 2 相同。
4. `GET /proxy` 既没有 `do` 也没有 `go` 时，落到默认页，结果和 2 相同。
5. `GET /media` 返回 `text/plain` 的 JSON，`title` 和 `artist` 来自同名的静态字段。
6. `POST` 到未知路径时落到默认页。
7. 每个请求都会 post 一个 `ServerEvent`，类型是 `SERVER_CONNECTION`。测试注册一个 public 的订阅者对象来断言，结束后注销。
8. 真实 socket：`GET /m3u8` 带 `Accept-Encoding: gzip` 时，响应带 `Content-Encoding: gzip`；不带时，响应没有 `Content-Encoding`，并且有 `Content-Length`。这一条锁住了 D5 的覆盖不会影响其他路由。

不锁 `/hlsseg` 现在会落到默认页这一点，因为本 change 就是要改变它。

**新路由的测试（2.2）**：

- mock session：
  - 对端地址为 `127.0.0.1`、`::1`、`0:0:0:0:0:0:0:1`、`::ffff:127.0.0.1` 时，`openSegment` 被调用一次，收到的参数是 `s`、`i` 原值，返回的就是接缝给出的那个响应对象。
  - 对端地址为 `192.168.1.20`、`10.0.0.2`、`""`、`null` 时，返回 403，Content-Type 为 `text/plain`，body 为空，`openSegment` 没有被调用。
  - 缺少参数时，`openSegment` 收到 `(null, null)`。
  - `openSegment` 抛 `RuntimeException` 时返回 500。
  - `POST /hlsseg` 不调用 `openSegment`。
- 真实 socket（请求都带 `Accept-Encoding: gzip`）：
  - 200、`video/mp2t`、长度已知：状态行是 `HTTP/1.1 200 OK`，`Content-Length` 正确，没有 `Transfer-Encoding`，没有 `Content-Encoding`，body 逐字节相同。
  - 200、长度未知：`Transfer-Encoding: chunked`，解开 chunked 后 body 逐字节相同，没有 `Content-Encoding`。
  - 200、`text/plain`：没有 `Content-Encoding`，body 是未压缩的原始字节。这是 D5 的关键用例。
  - 502、body 为 null：状态行是 `HTTP/1.1 502 Bad Gateway`，`Content-Length: 0`，没有 `Content-Encoding`。
  - 发送完毕后 resource 被关闭，而且只关闭一次。
  - 客户端读完响应头就断开时，服务端还在写一个不会结束的 body，5 秒内 resource 被关闭。
  - 需要等待的断言，都用最长 5 秒的轮询辅助方法，不用固定的 sleep。

### D8 owner-only 文件

不需要改。测试依赖都已经有了，也不新增 Hawk 键或资源。

## Risks / Trade-offs

- **[风险] S1 的骨架和最终实现之间，`SegmentResponse` 字段的语义有偏差**（比如错误响应的 `length` 不是 0）。→ `create()` 在 `body == null` 时一律按长度 0 处理，不使用 `length`；`length` 只在 body 不为 null 时才用来区分定长和 chunked。
- **[风险] `from()` 和默认的 `openSegment` 在 S2 分支上没有单测。** → 两处都只是直接的字段映射；dev-test 在集成分支上用 3.1 的 adb 步骤验证真实的 400、410 和 200。
- **[风险] 客户端断开的测试依赖 TCP 行为。** 对端关闭以后，前一两次写入可能还会成功。→ 测试用的 body 是一个不会结束的流，服务端会一直写，直到写失败；5 秒的轮询上限足够。
- **[取舍] 每个分片请求都会 post 一次 `SERVER_CONNECTION`。** 为了不改动 `serve` 开头的现有行为，接受这点微小的开销。
- **[取舍] loopback 白名单里有几个实际不会出现的 IPv6 字面量。** 换来的是不依赖 NanoHTTPD 的归一化细节。
- **[取舍] 继承 `Response`，而不是用 NanoHTTPD 的静态工厂方法。** 换来的是能精确地只对分片代理关闭 gzip。

## Migration Plan

- 本 change 只新增路由，在 S3 改写 m3u8 之前不会有请求打到 `/hlsseg`，合并后对现有功能没有影响。回滚直接 revert。
- **归档时手工修改 Purpose**：delta spec 表达不了 Purpose 的修改。归档本 change（dev-test 阶段执行 `/opsx:archive`）时，同步把 `openspec/specs/remote-control-push/spec.md` 的 Purpose 一句改为：

  > TVBox 启动时在本机开启 HTTP 服务，让手机或电脑通过浏览器遥控盒子（搜索、推送播放、远程填写配置、管理存储卡文件），并作为爬虫代理、M3U8 去广告、HLS 预缓存分片代理和直播源加载的基础；同时支持设备间推送和系统广播控制。

  同时按 `openspec/config.yaml` 的 archive guidance，在 `docs/功能模块/` 对应的远程控制文档里加上分片代理的接口说明。
