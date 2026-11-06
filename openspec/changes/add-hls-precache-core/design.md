## Context

动机见 proposal.md 的 Why；方案选型见 `docs/adr/0001-hls-precache-via-local-segment-proxy.md`；术语见 `GLOSSARY.md`（预缓存、预缓存窗口、分片代理、预缓存会话）；行为要求见 `specs/vod-player/spec.md`「HLS 预缓存」。

和设计相关的现状：

- **调用 `rewrite` 的线程**：`PlayFragment.playUrl`（:683-798）和 `PlayActivity.playUrl`（:700）在 OkGo `AbsCallback.onSuccess` 里拿到 m3u8 内容。这个回调运行在**主线程**，所以 `rewrite` 不能做网络 IO，也不能做会阻塞的磁盘 IO。
- **`/m3u8` 路由**：`RemoteServer.serve` 的 `/m3u8`（:192-193）把静态字段 `m3u8Content` 原样作为 `text/plain` 定长返回。
  - Exo 按 URL 里含 `m3u8` 判定为 HLS（`ExoMediaSourceHelper.inferContentType` :148）。
  - IJK 按内容探测。
  - 去广告功能已经在用这条路径。
- **NanoHTTPD 线程模型**：NanoHTTPD 2.3.1 每个连接一个线程。返回 `Response` 后，它在同一线程上读 body 流写给客户端，发送结束后关闭流（`Response implements Closeable`）。`Response.Status.lookup(int)` 只认识枚举里有的状态码，不认识的返回 null。
- **OkHttp 客户端**：`OkGoHelper.getDefaultClient()` 返回全局 OkHttp 3.12.11 客户端，带 DoH，超时 10s，会跟随重定向。它在 `App.onCreate` 的 :69 初始化，比 `FileUtils.cleanPlayerCache()`（:101）早。
- **缓存目录**：`FileUtils.getExternalCachePath()` 依赖 `App.getInstance()`，纯 JUnit 下拿不到，所以缓存目录由外部注入。
- **URI 解析工具**：`androidx.media3.common.util.UriUtil.resolve(String, String)` 是纯 Java 的 RFC 3986 实现，已经核实它只用 `String`/`StringBuilder`。`M3U8.java` 已经在用它，纯 JUnit 下也能调用。
- **语言和 API 限制**：minSdk 21，Java 8 语法，没开 core library desugaring。所以以下 API 都不能用：
  - `java.util.function.*`
  - `java.util.stream`
  - `CompletableFuture`
  - `java.nio.file.*`
  - `Map` 接口上的 `getOrDefault`、`putIfAbsent`、`computeIfAbsent`（`ConcurrentHashMap` 自己的 `putIfAbsent` 可以用）

  lambda 可以用。

## Goals / Non-Goals

**Goals:**

- 给 S2（#3）、S3（#4）定死接口，两边可以照着并行开发，见下面「跨 change 接口」一节。
- 核心逻辑全部能用纯 JUnit 测试：不依赖 NanoHTTPD、`App`、`Context`、Hawk。
- 预缓存出任何问题，最多降级为纯转发，不能让分片请求拿不到结果。

**Non-Goals:**

- 不支持 HTTP Range：分片代理总是返回 200 和完整分片，忽略播放器发来的 Range（HTTP 允许服务端忽略 Range）。
- 不持久化会话：进程重启后不认旧目录，由 S3 的 `cleanPlayerCache()` 在启动时清掉 `hlscache/`。
- 不对外暴露进度、命中率等统计接口。

## 跨 change 接口（S2、S3 只依赖这一节）

包名都是 `com.github.tvbox.osc.precache`。下面两个类是 public 的，其余类都是包内可见，S2 和 S3 不要引用。

### `HlsPrecache`：门面，进程内单例

```java
package com.github.tvbox.osc.precache;

public final class HlsPrecache {

    /** 缓存根目录名。S3 用它拼出 <外部缓存目录>/hlscache，并在 cleanPlayerCache() 里删除它。 */
    public static final String CACHE_DIR_NAME = "hlscache";

    /** 分片代理路径。S2 在 RemoteServer.serve 里用 fileName.equals(SEGMENT_PATH) 匹配。 */
    public static final String SEGMENT_PATH = "/hlsseg";
    /** query 参数：会话标识，取值是 [0-9a-z]+。 */
    public static final String PARAM_SESSION = "s";
    /** query 参数：分片序号，十进制，从 0 开始，表示分片在列表里的位置（不是 MEDIA-SEQUENCE）。 */
    public static final String PARAM_INDEX = "i";

    /**
     * 取进程内单例，第一次调用时创建。线程安全，不做 IO，可以在主线程调用。
     * 默认配置：下载器使用 OkGoHelper.getDefaultClient()（每次请求时取；为 null 时退回一个
     * 进程内共享的 new OkHttpClient()），线程都是 daemon 线程。
     */
    public static HlsPrecache get();

    /**
     * 设置缓存根目录。只记下路径，不建目录，不做 IO，可以在主线程调用。
     * 调用时机：S3 在 App.onCreate() 里紧跟 FileUtils.cleanPlayerCache() 之后调用：
     *   HlsPrecache.get().setCacheRoot(
     *       new File(FileUtils.getExternalCachePath(), HlsPrecache.CACHE_DIR_NAME));
     * 没有设置时，rewrite 一律返回 null，也就是不启用预缓存。
     * 重复调用时，只影响之后新建的会话。
     */
    public void setCacheRoot(File cacheRoot);

    /**
     * 改写 m3u8，同时开始一个新的预缓存会话。
     *
     * @param playlistUrl 这份 m3u8 的真实源站地址，用来解析相对 URI，也作为会话复用的判断依据。
     *                    跟随过一次 master 时，传子列表地址（PlayFragment 里的 finalforwardurl）。
     * @param content     m3u8 文本：去广告开启且 purify 返回非 null 时传 purify 的结果，否则传原文。
     * @param headers     播放时的请求头，可以为 null。方法内部会复制一份，调用方之后再修改不影响会话。
     *                    值的首尾空白在请求源站时去掉。
     * @param port        本地服务的实际端口，传 RemoteServer.serverPort。
     * @return 改写后的 m3u8 文本；不适用时返回 null（判断规则见 D2）。
     *
     * 线程：任意线程都可以调用，包括主线程。不做网络 IO，也不做阻塞的磁盘 IO（删除旧目录交给后台
     *       IO 线程），耗时和行数成正比，几千行在毫秒级。
     * 副作用：不管返回什么，上一个会话都会结束（取消它的全部下载，让它的代理地址失效）。
     *         返回非 null 时，新会话成为当前会话；playlistUrl 和上一个会话相同时复用已下载的分片，
     *         不同时上一个会话的目录在后台删除。
     *         返回 null 时，上一个会话的目录也在后台删除。
     * 异常：不抛异常。内部出现任何 Throwable 都当作不适用，返回 null。
     */
    public String rewrite(String playlistUrl, String content, Map<String, String> headers, int port);

    /**
     * 结束当前会话：取消它的全部下载，让它的代理地址失效（之后的请求返回 410），在后台删除它的目录。
     * S3 在离开播放页时调用；后台播放和画中画不算离开。
     * 线程：任意线程都可以调用，包括主线程。不阻塞，不做 IO，可以重复调用，不抛异常。
     */
    public void endSession();

    /**
     * dev-test 返工时新增（纯增量，上面两个方法的签名和语义不变）。
     * 同 rewrite(4 参)，并把新会话记在 owner 名下。owner 由播放页自己 new 一个普通 Object 传入，
     * 用来区分「是谁建的会话」，不能传 Context（单例会持有它，直到会话被替换或结束）。
     * 原因：旧页面的 onDestroy 可能晚于新页面的 rewrite 才到达（比如先 finish 旧 DetailActivity 再启动新的），
     * 无条件的 endSession() 会结束新页面的会话。
     */
    public String rewrite(String playlistUrl, String content, Map<String, String> headers, int port, Object owner);

    /**
     * dev-test 返工时新增。只在当前会话的 owner 和传入的 owner 是同一个对象（==）时结束它，否则什么都不做。
     * owner 为 null 时什么都不做。线程与异常语义同 endSession()。S3 的播放页一律用这个版本结束会话。
     */
    public void endSession(Object owner);

    /**
     * 分片代理的入口，由 S2 在 NanoHTTPD 的请求线程上调用。
     *
     * @param sessionId session.getParms().get(PARAM_SESSION)，可以为 null
     * @param index     session.getParms().get(PARAM_INDEX)，可以为 null
     * @return 不为 null。调用方必须把它发出去，或者调用 close()。
     *
     * 线程：阻塞调用，不能在主线程调用。阻塞到能确定状态码为止：命中时立即返回；
     *       需要下载时，阻塞到源站响应头到达，或者确认失败（受 OkHttp 的连接和读取超时约束）。
     *       返回的 body 在读取时可能继续阻塞，等下载进度跟上。
     * 异常：不抛异常。读 body 时，如果源站中途断开或者下载被取消，read() 抛 IOException，
     *       NanoHTTPD 会因此断开这个连接，播放器自己重试。
     * 状态码：
     *   200  成功，body 为分片字节
     *   400  sessionId 或 index 为 null，或者 index 不是非负十进制整数
     *   404  会话有效，但 index 超出分片数
     *   410  没有当前会话，或者 sessionId 和当前会话不一致（会话已结束或已被替换）
     *   其他 源站返回的非 2xx 状态码，原样返回（如 403、404、429、503）
     *   502  请求源站时没有拿到 HTTP 响应（连接被拒、超时、DNS 失败等）
     *   500  核心内部出现意外的 Throwable
     * loopback 校验（只允许 127.0.0.1 访问，否则 403）由 S2 在调用本方法之前完成，核心不做。
     */
    public SegmentResponse openSegment(String sessionId, String index);
}
```

改写后，分片代理地址的格式固定为：

```
http://127.0.0.1:<port>/hlsseg?s=<sessionId>&i=<index>
```

### `SegmentResponse`：不依赖 NanoHTTPD 的结果类型

```java
package com.github.tvbox.osc.precache;

public final class SegmentResponse implements Closeable {
    /** length 取这个值，表示长度未知。 */
    public static final long UNKNOWN_LENGTH = -1L;

    /** HTTP 状态码。成功时固定为 200（源站返回其他 2xx 时也写成 200）。 */
    public final int status;
    /** 只在 status == 200 时不为 null；其他状态码时为 null，表示 body 为空。 */
    public final InputStream body;
    /** body 的字节数。命中磁盘时是文件长度；源站给了 Content-Length 时是这个值；否则是 UNKNOWN_LENGTH。错误响应时为 0。 */
    public final long length;
    /** 一定不为 null。成功时优先用源站的 Content-Type，没有时按分片 URL 的扩展名推断（D11）；错误响应时为 "text/plain"。 */
    public final String contentType;

    /** 关闭 body。可以重复调用，不抛异常。 */
    @Override public void close();
}
```

S2 的用法示意（不在本 change 里实现）：

```java
SegmentResponse r = HlsPrecache.get().openSegment(
        parms.get(HlsPrecache.PARAM_SESSION), parms.get(HlsPrecache.PARAM_INDEX));
Response.IStatus st = /* 自己实现 IStatus：getRequestStatus() 返回 r.status，
                         getDescription() 返回 r.status + " " + 原因短语。
                         不能用 Status.lookup，它对 502 等状态码可能返回 null */;
if (r.body == null) return newFixedLengthResponse(st, r.contentType, "");
if (r.length >= 0) return newFixedLengthResponse(st, r.contentType, r.body, r.length);
return newChunkedResponse(st, r.contentType, r.body);
```

响应头只带 Content-Type 和长度，不转发源站的任何响应头。注意，光靠这一点还不能保证没有 Content-Encoding：NanoHTTPD 遇到 Content-Type 含 `text/` 且请求带 `Accept-Encoding: gzip` 的情况会自己加 gzip，比如错误响应的 `text/plain`、`.vtt` 字幕分片。所以 S2 必须对分片代理的响应关闭 gzip（S2 的做法见 `add-segment-proxy-endpoint` 的 design）。

### S3 怎么把改写结果交给播放器

沿用现有的 `RemoteServer.m3u8Content` 加 `/m3u8` 路由，这样做可行：

```java
String rewritten = HlsPrecache.get().rewrite(playlistUrl, content, headers, RemoteServer.serverPort);
if (rewritten != null) {
    RemoteServer.m3u8Content = rewritten;
    startPlayUrl("http://127.0.0.1:" + RemoteServer.serverPort + "/m3u8", headers);
} else { /* 维持现有逻辑：有 purify 结果就播 /m3u8，没有就播原地址 */ }
```

可行的依据：

- 改写结果里所有 URI 都是绝对地址：分片是代理的绝对地址，KEY 和 MAP 被补成了源站绝对地址。所以播放器以 `127.0.0.1/m3u8` 为基准解析也不会出错。
- Exo 和 IJK 现在就是这样播去广告后的列表的。

需要注意：

- `m3u8Content` 是全局静态字段，并发覆盖的风险和现在一样，本 change 不处理。
- 播放器请求本地服务时会带上 headers，分片代理会忽略这些 headers，回源只用 `rewrite` 时传入的 headers。

## Decisions

### D1 包结构

所有类都放在 `app/src/main/java/com/github/tvbox/osc/precache/`。

| 类 | 可见性 | 职责 |
| --- | --- | --- |
| `HlsPrecache` | public | 门面：单例、会话切换、`openSegment` 参数校验和分发 |
| `SegmentResponse` | public | 返回给 S2 的结果 |
| `PrecacheConfig` | 包内 | 固定参数，测试时可以传别的值 |
| `HlsSegment` | 包内 | 一个分片：`index`、源站绝对 `url`、`durationMs`、`key` |
| `HlsPlaylist` | 包内 | 解析和改写 m3u8，是纯函数 |
| `PrecacheWindow` | 包内 | 计算窗口和回看范围、判断跳转，是纯函数 |
| `SegmentDownloader` | 包内接口 | 回源抽象，包含 `Call` 和 `Source` |
| `OkHttpSegmentDownloader` | 包内 | `SegmentDownloader` 的 OkHttp 实现 |
| `SegmentStore` | 包内 | 一个会话目录里已提交的分片文件和占用字节数 |
| `SegmentDownload` | 包内 | 一次下载：边下边写临时文件，多个读者跟读 |
| `PrecacheSession` | 包内 | 一个会话：锚点、调度、限流、降级、`open(index)` |

固定参数和 `SegmentResponse` 的包内构造方式：

```java
final class PrecacheConfig {
    final long aheadMs;                     // 120_000
    final long behindMs;                    // 30_000
    final int maxConcurrency;               // 3
    final int throttledConcurrency;         // 1
    final int throttleThreshold;            // 3
    final long capBytes;                    // 300L * 1024 * 1024
    final long defaultSegmentEstimateBytes; // 2L * 1024 * 1024
    PrecacheConfig(long aheadMs, long behindMs, int maxConcurrency, int throttledConcurrency,
                   int throttleThreshold, long capBytes, long defaultSegmentEstimateBytes);
    static final PrecacheConfig DEFAULT = /* 上面注释里的取值 */;
}

// SegmentResponse 的包内成员（S2 不调用）
SegmentResponse(int status, InputStream body, long length, String contentType);
static SegmentResponse error(int status);   // body = null，length = 0，contentType = "text/plain"
```

为什么拆这么细：

- 纯逻辑（`HlsPlaylist`、`PrecacheWindow`）和 IO 分开，可以直接写表驱动测试。
- 并发最难的部分集中在 `SegmentDownload` 一个类里，单独测试。

### D2 m3u8 解析与改写（`HlsPlaylist`）

```java
final class HlsPlaylist {
    /** 不适用时返回 null。纯函数，不做 IO。 */
    static HlsPlaylist parse(String playlistUrl, String content);
    List<HlsSegment> segments();          // 不可修改，按出现顺序，index 从 0 开始
    /** 第 i 个分片 URI 行替换成 segmentUrlPrefix + i，其余部分逐字输出。 */
    String render(String segmentUrlPrefix);
}
```

**不适用（返回 null）的情况**，满足任意一条即可：

- `content` 为 null，或者不以 `#EXTM3U` 开头。只看原文开头，不跳过 BOM 和空白，和 `PlayFragment` 现有的判断一致。
- 任意一行以 `#EXT-X-STREAM-INF` 或 `#EXT-X-I-FRAME-STREAM-INF` 开头，说明是 master 列表。
- 任意一行以 `#EXT-X-BYTERANGE` 开头。
- 任意一行以 `#EXT-X-PART` 或 `#EXT-X-PRELOAD-HINT` 开头。这一条是需求之外补充的，原因见 Risks。
- 没有 `#EXT-X-ENDLIST`。
- 分片数为 0。
- `playlistUrl` 为 null，或者不以 `http://`、`https://` 开头。

**按行处理**：

- 按 `\n` 切分，每一行保留自己原来的行尾（`\r\n` 或 `\n`，最后一行可能没有行尾）。
- 判断一行是什么时，先去掉首尾空白，但输出时用原文。所以空行、`\r`、残留的 `#EXT-X-DISCONTINUITY` 都原样保留。
- 分片 URI 行：去掉首尾空白后不为空，也不以 `#` 开头。这一整行（不含行尾）替换成代理地址。
- 分片的源站地址用 `UriUtil.resolve(playlistUrl, trimmedLine)` 得到。已经是绝对地址的保持不变。
- 分片时长：取前面最近一个 `#EXTINF:<秒>,` 的值，换算成毫秒（四舍五入）。没有或者解析不了时，用 `#EXT-X-TARGETDURATION`；再没有，按 10 秒算。
- `#EXT-X-KEY` 和 `#EXT-X-MAP` 行：只把 `URI="..."` 引号里的值替换成 `UriUtil.resolve(playlistUrl, 原值)`，其余字符不动。处理所有 KEY 和 MAP 行，而不只是第一个。不在整行上用 `String.replace` 替换，因为 URI 的值可能在别的属性里也出现。没有 URI 属性的行（如 `METHOD=NONE`）不动。
- `HlsSegment.key` 是源站绝对地址的 SHA-1 十六进制小写，用作磁盘文件名（D6）。

### D3 会话标识与代理地址

- **会话标识**：`AtomicLong` 以 `System.currentTimeMillis()` 作初值，每次 `rewrite` 成功时加 1，再用 `Long.toString(v, 36)` 转成字符串。
  - 每次 `rewrite` 都换一个新标识，包括复用分片的情况。这样旧播放器残留的请求一定拿到 410。
  - 不需要防猜测，因为 S2 只允许 loopback 访问。
- **代理地址前缀**：`"http://127.0.0.1:" + port + SEGMENT_PATH + "?" + PARAM_SESSION + "=" + id + "&" + PARAM_INDEX + "="`，后面接分片序号。
- **为什么序号放在 query 里，而不是写成 `/hlsseg/<id>/<i>.ts` 这样的路径**：S2 匹配路由时只需要做一次 `equals`。
  - Exo 的 `MyHlsExtractorFactory` 会参考响应头里的 Content-Type，也会探测内容，不依赖扩展名。
  - IJK 的 ffmpeg 4.x 不检查分片扩展名。
  - 所以代价只是播放器少了一个扩展名提示，而我们会返回 Content-Type（D11）。

### D4 回源接口与 OkHttp 实现

```java
interface SegmentDownloader {
    Call newCall(String url, Map<String, String> headers);

    interface Call {
        /** 阻塞到拿到响应头。拿不到 HTTP 响应时抛 IOException，连接被拒时抛 java.net.ConnectException。 */
        Source execute() throws IOException;
        /** 任意线程都可以调用，不阻塞，可以重复调用。会让正在进行的 execute() 或 body 读取抛 IOException。 */
        void cancel();
    }

    final class Source implements Closeable {
        final int code; final InputStream body; final long contentLength; /* -1 表示未知 */
        final String contentType; /* 可能为 null */
        Source(int code, InputStream body, long contentLength, String contentType);
        @Override public void close();   // 可以重复调用，不抛异常
    }
}

final class OkHttpSegmentDownloader implements SegmentDownloader {
    interface ClientProvider { OkHttpClient client(); }   // 不用 Supplier，因为 minSdk 21
    OkHttpSegmentDownloader(ClientProvider provider);
}
```

**请求头**：由 `OkHttpSegmentDownloader` 统一处理。这样请求实际发出时的样子，可以直接用 MockWebServer 验证。

- 名字和值都去掉首尾空白。去掉后名字为空的，丢弃。
- 丢弃 `Accept-Encoding`、`Range`、`Host`、`Connection`、`Content-Length`，名字不区分大小写。丢弃 `Accept-Encoding` 后，OkHttp 会自己加上 gzip 并透明解压，所以存下来的就是解码后的字节，播放器也不会看到 Content-Encoding。
- OkHttp 认为不合法的头（`IllegalArgumentException`），跳过那一个。

**响应**：

- 非 2xx 时，`Source.body` 仍然可以关闭，调用方不读它。
- `contentLength` 取 `ResponseBody.contentLength()`。

**不重试**：重试交给播放器。

### D5 线程与执行器

| 执行器 | 默认实现 | 用途 |
| --- | --- | --- |
| `downloadExecutor` | 缓存线程池：`ThreadPoolExecutor(0, Integer.MAX_VALUE, 30s, SynchronousQueue)`，daemon 线程，名为 `hls-precache-N` | 运行每个 `SegmentDownload` |
| `ioExecutor` | 单线程，daemon，名为 `hls-precache-io` | 删除整个会话目录 |

- 并发数不靠线程池大小控制，而是由 `PrecacheSession` 自己计数（D8）。不用固定 3 线程的线程池，是因为播放器请求触发的下载不能排在预缓存任务后面。
- 测试构造器：`HlsPrecache(PrecacheConfig config, SegmentDownloader downloader, Executor downloadExecutor, Executor ioExecutor)`，包内可见。测试里 `ioExecutor` 可以传 `Runnable::run`。
- 不需要注入时钟：调度只按分片时长和事件触发，不依赖墙钟。
- **锁的顺序**，只允许下面这几种嵌套：
  - `HlsPrecache` 的锁 → `PrecacheSession` 的锁
  - `PrecacheSession` → `SegmentStore`
  - `SegmentDownload` → `SegmentStore`

  `SegmentDownload` 结束时回调 `PrecacheSession.onFinished`，这个回调必须在释放自己的锁之后再调，避免反向加锁。
- **持会话锁和 `SegmentStore` 的锁时只做内存操作**（dev-test 返工时收紧）：
  - 会话锁里不做任何磁盘 IO：命中查找（`committedFile` 的 stat、打开 `FileInputStream`）在锁外做；淘汰时在锁里只用 `SegmentStore.detach(key)` 摘掉记录（占用字节随即减少，`fits()` 立刻看得到），文件在释放会话锁之后由同一个线程删除。
  - 会话锁里也不调 `SegmentDownload.cancel()`，不拿 `SegmentDownload` 的锁：要取消的下载在锁里记下来，释放锁之后再取消；会话在锁里读的 `contentLength`、`state`、`isThrottleSignal` 是 volatile 字段，不加锁读（写仍然在 `SegmentDownload` 的锁里）。原因是 `SegmentDownload` 持自己的锁时会做 rename 和打开读者（D7 要求它们和状态变化在同一把锁里）。
  - `SegmentStore` 的 stat、rename、删除也都在它自己的锁外，锁里只改记录。
  - 文件刚被摘掉记录、还没删时，同一个 key 又被重新下载并提交，这个新文件可能被随后的删除删掉。之后的请求按「文件被外部删除」处理（D6），重新回源，不会出错。被淘汰的分片在窗口外，这种情况只在播放器恰好请求它时才会出现。
- 主线程上的调用（`rewrite`、`endSession`）因此不会在会话锁上等 IO：`close()` 在会话锁里只置 `closed` 并取下载列表的快照，然后在锁外逐个 `cancel()`。`cancel()` 最多等这个下载正在做的一次 rename 或打开读者（单次元数据操作），不会等文件删除。其余只有 `Call.cancel()` 和 `ioExecutor.execute()`。
- 单个文件的删除只发生在 NanoHTTPD 线程或下载线程上。

### D6 磁盘布局（`SegmentStore`）

- **会话目录**：`<cacheRoot>/s<创建它的会话标识>/`。
  - 复用时目录不变；换了 playlistUrl 就建新目录。
  - 每个目录名只用一次，所以在 A→B→A 这样快速切换时，后台删旧目录不会误删新目录。
- **文件命名**：
  - 已提交的分片：`<key>.seg`。
  - 下载中的临时文件：`<key>.<下载序号>.tmp`。下载序号在进程内递增，保证两次下载不会写同一个临时文件。
- **内存记录**：只有已提交的分片记在内存里，`key → (size, contentType)`，再加上 `committedBytes`。记录用 `synchronized` 保护，文件 IO（stat、rename、删除）都在锁外（D5）。

```java
final class SegmentStore {
    SegmentStore(File dir, String playlistUrl);
    String playlistUrl();
    /** 已提交而且文件还在时返回文件；记录显示已提交、但文件被外部删掉时，删除这条记录，返回 null。会做一次 stat。 */
    File committedFile(String key);
    boolean isCommitted(String key);            // 只查内存，不做 IO，给调度用
    String contentType(String key);
    File newTempFile(String key) throws IOException;   // 先 mkdirs，再创建文件；失败抛 IOException
    void commit(String key, File temp, long size, String contentType) throws IOException; // renameTo 失败抛 IOException
    void remove(String key);                    // 删文件和记录
    Set<String> committedKeys();                // 快照
    long committedBytes();
    long averageSize(long fallback);            // 没有已提交的分片时返回 fallback
    void deleteAll();                           // 递归删除目录，清空记录，只在 ioExecutor 上调用
}
```

**外部删除**：命中判断是「记录显示已提交，并且 `file.isFile()`」。打开 `FileInputStream` 失败时分两种情况：文件已经不存在，按未命中处理并删除这条记录；文件还在却打不开，视为磁盘故障，会话降级（D12），本次请求走纯转发。整个缓存目录被首页清理掉时，`newTempFile` 会先 `mkdirs` 把目录建回来。

### D7 单个分片的下载（`SegmentDownload`）

```java
final class SegmentDownload implements Runnable {
    interface Listener { void onFinished(SegmentDownload d); }
    enum State { PENDING, STREAMING, COMPLETED, HTTP_ERROR, NETWORK_ERROR, DISK_ERROR, FAILED, CANCELLED }

    SegmentDownload(HlsSegment seg, Map<String,String> headers, boolean prefetch,
                    SegmentDownloader downloader, SegmentStore store, Listener listener);
    void run();                       // 在 downloadExecutor 上运行
    State awaitHeaders();             // 阻塞到状态不是 PENDING
    InputStream openReader() throws IOException; // 只在 STREAMING 或 COMPLETED 时调用
    int httpCode(); long contentLength(); String contentType();
    boolean isThrottleSignal();       // 结果是 403/429/503 或 ConnectException 时为 true
    boolean isPrefetch();
    void cancel();                    // 任意线程，不阻塞
}
```

**状态机**：

```
PENDING ──execute 抛 IOException──▶ NETWORK_ERROR
PENDING ──非 2xx──▶ HTTP_ERROR(code)          （不写盘）
PENDING ──2xx，newTempFile 失败──▶ DISK_ERROR
PENDING ──2xx，临时文件已建好──▶ STREAMING ──读完、长度校验通过、commit 成功──▶ COMPLETED
                                        ├─写入或 commit 失败──▶ DISK_ERROR
                                        ├─读源站失败，或者长度和 Content-Length 不符──▶ FAILED
任意非终态 ──cancel()──▶ CANCELLED（调 Call.cancel()，删除临时文件，不 commit）
```

- **写入过程**：每读一块（64KiB）就写入临时文件，然后更新 `bytesWritten` 并 `notifyAll`。
- **commit 和终态**：commit（rename）和状态改为 `COMPLETED` 在同一个锁里完成。
- **取消检查**：commit 之前，在锁里检查是否已被取消。已取消的下载一定不会 commit。
- **跟读（`openReader`）**：
  - 在锁里打开临时文件的 `FileInputStream`。如果已经是 `COMPLETED`，改为打开已提交的文件。因为打开文件和 rename 在同一个锁里，不会出现文件已经被 rename、却按旧路径去打开的情况。
  - `read()` 读到 `bytesWritten` 时，如果下载还没结束，就 `wait(500)` 循环等待。
  - 读到末尾时：`COMPLETED` 返回 -1。`FAILED`、`DISK_ERROR`、`CANCELLED` 抛 IOException。
  - 已经打开的文件描述符在 rename 之后仍然有效，因为外部缓存目录在 ext4、f2fs 以及 Android 11+ 的直通 FUSE 上都是 POSIX 语义。
- **为什么跟读，而不是等下载完成再返回**：
  - 需求原文是「并发请求等待它完成」。字面做法是等整片下完再返回，但这样首字节要等整个分片下载完。限速环境下，这正好会超过播放器自己的读超时（Exo 默认 8s），反而造成卡顿。
  - 跟读同样只下载一次，语义上仍然是「等同一次下载」。
  - 这是对需求字面的细化，回报里会单独列出。
- **读者关闭流不会取消下载**：分片仍在窗口里，下完正好可以缓存。只有跳转或会话结束才取消。

### D8 会话与调度（`PrecacheSession`）

```java
final class PrecacheSession implements SegmentDownload.Listener {
    PrecacheSession(String id, HlsPlaylist playlist, Map<String,String> headers, SegmentStore store,
                    PrecacheConfig config, SegmentDownloader downloader, Executor downloadExecutor);
    String id(); SegmentStore store(); int segmentCount();
    SegmentResponse open(int index);   // 阻塞；index 已由门面校验过范围
    void close();                      // 不阻塞：标记关闭，取消全部下载
}
```

**`open(index)` 的流程**：

1. 会话已关闭 → 410。
2. 加锁调用 `onRequest(index)`，更新锚点、处理跳转、淘汰，见 D9。要取消的下载和要删的文件在释放锁之后处理（D5）。
3. 会话已降级 → 纯转发，见下文。
4. **在锁外**调 `store.committedFile(key)`，不为 null → 返回 200，body 是 `FileInputStream`，`length` 是文件长度，Content-Type 取存储的值。打开失败时按 D6 处理：文件已不存在按未命中，继续第 5 步；文件还在则降级，走纯转发。
5. 加锁，找这个序号上正在进行的下载，然后 `pump()`。
   - 还在下载（`PENDING`、`STREAMING`），或者已经 `COMPLETED`（刚提交、还没从列表里移除）→ 加入它，不重复下载。
   - 没有，或者已经以失败、取消结束：如果这时 store 里已经有这个分片（第 4 步查完、拿到锁之前刚提交并结束），回到第 4 步再查一次（只回一次）；否则新建一个**按需下载**（`prefetch=false`），提交给 `downloadExecutor`。按需下载不检查并发上限和磁盘上限，但计入正在进行的下载数。如果这个序号在预缓存失败名单里，把它移出名单。
   - `downloadExecutor.execute()` 抛异常（如 `RejectedExecutionException`）时，这个下载移出列表并标成 `FAILED`，预缓存的序号记入失败名单，不会留下永远 `PENDING` 的下载。
6. `awaitHeaders()` 之后按状态返回：

| 状态 | 返回 |
| --- | --- |
| `STREAMING` / `COMPLETED` | 200，body 用 `openReader()`；`length` 是 Content-Length，未知时为 -1 |
| `HTTP_ERROR` | 源站状态码 |
| `NETWORK_ERROR` | 502 |
| `DISK_ERROR` | 会话已经降级，改走纯转发 |
| `CANCELLED` | 会话已关闭时返回 410；否则（被跳转取消）回到第 5 步，最多重来一次，之后返回 502 |

`openReader()` 抛 IOException 时，按未命中重新从第 4 步开始，最多一次。

**纯转发**：在当前线程上执行 `downloader.newCall(url, headers).execute()`。

- 2xx → 200，body 用源站流，`close()` 时关闭 `Source`。
- 非 2xx → 源站状态码。
- IOException → 502。
- 遇到限流信号同样计数。

**`pump()`**：持会话锁执行。下载完成（`onFinished`）、锚点变化、空间被释放后都会调用。

```
if (closed || degraded || anchor < 0) return;
allowed = throttled ? config.throttledConcurrency : config.maxConcurrency;   // 1 : 3
for k in anchor+1 .. window.aheadEnd(anchor):
    if (active >= allowed) break;                         // active 包括按需下载
    if (store.isCommitted(key(k)) || downloading(k) || prefetchFailed.contains(k)) continue;
    if (!fits()) { evictBehindAnchor(); if (!fits()) break; }   // D10
    start prefetch download k;  active++;
```

- 按升序找第一个空位，所以离锚点近的分片先下载。
- 锚点分片本身由播放器请求触发下载，`pump()` 不管它。
- 预缓存失败（`HTTP_ERROR`、`NETWORK_ERROR`、`FAILED`）的序号放进 `prefetchFailed`，`pump()` 不再重试，免得出错时反复请求源站。等播放器真的请求到这个分片时，按需下载会再试一次。

**`onFinished(d)`**：

1. `active--`。
2. 如果是限流信号，`throttleSignals++`。达到阈值时把 `throttled` 设为 true。
3. 如果是 `DISK_ERROR`，进入降级。
4. 调用 `pump()`。

### D9 窗口、跳转与淘汰（`PrecacheWindow`）

```java
final class PrecacheWindow {
    PrecacheWindow(long[] durationsMs);          // 预先算出 startMs[k]（前缀和）
    /** 满足 startMs[k] - startMs[anchor] < aheadMs 的最大 k，不超过最后一个分片。 */
    int aheadEnd(int anchor, long aheadMs);
    /** 满足 startMs[anchor] - startMs[k] <= behindMs 的最小 k（k <= anchor）。 */
    int behindStart(int anchor, long behindMs);
    /** 没有上一个锚点（previous < 0）时为 false；requested == previous 或 previous + 1 时为 false；其余为 true。 */
    static boolean isJump(int previousAnchor, int requested);
}
```

- **用整数毫秒**：时长都是整数毫秒，避免浮点累加误差，测试可以精确断言。例如每片 10 秒、锚点是 10 时，窗口是 10..21，共 12 片，覆盖 120 秒；回看是 7..9。
- **`onRequest(index)` 的处理**：
  1. 用 `isJump(anchor, index)` 判断是否跳转，然后设 `anchor = index`。
  2. 如果是跳转，取消所有序号不在 `[anchor, aheadEnd]` 里的**预缓存**下载。按需下载不取消，它有读者在等。
  3. 每次锚点变化都淘汰一遍：删除已提交、但序号不在 `[behindStart, aheadEnd]` 里的分片，以及不属于当前列表的 key（同一地址复用时，列表内容可能变了）。持会话锁时只摘掉记录，文件在释放会话锁之后由 NanoHTTPD 线程同步删除（D5）。
  4. 调用 `pump()`。
- **暂停**：暂停时播放器不再请求新分片，锚点不变。`pump()` 填满 `[anchor+1, aheadEnd]` 之后自然停止。不需要专门处理。
- **会话刚开始还没有锚点时，不做预缓存**：续播会让第一个请求落在中间某个分片上，如果一开始就从第 0 片开始预取，下载的东西就浪费了。代价是第一个分片一定未命中，由按需下载边下边返回。

### D10 磁盘上限

- **`fits()` 的条件**：`store.committedBytes() + reserved + estimate <= config.capBytes`。
  - `reserved`：每个正在进行的下载，Content-Length 已知时取 Content-Length，否则取 `estimate`。
  - `estimate`：`store.averageSize(config.defaultSegmentEstimateBytes)`，默认是 2 MiB。
- **超出时，先淘汰回看分片**：按序号从小到大删除锚点之前已提交的分片，直到满足条件或者删完。仍然不满足就停止 `pump()`，相当于窗口缩短。锚点前移、淘汰出空间后，在下一次 `pump()` 里自动恢复。
- **按需下载不受上限约束**：播放必须优先。它造成的超出最多是并发下载数乘以一个分片的大小，可以接受。spec 的「达到磁盘上限」场景只约束「不再开始新的预缓存下载」。

### D11 Content-Type

- **优先用源站响应的 Content-Type**，和分片一起记在 store 里，命中时也返回这个值。
- **源站没给时按 URL 路径的扩展名推断**（不区分大小写，忽略 query）：

| 扩展名 | Content-Type |
| --- | --- |
| `.ts` | `video/mp2t` |
| `.m4s`、`.mp4`、`.m4v`、`.cmfv` | `video/mp4` |
| `.aac` | `audio/aac` |
| `.vtt` | `text/vtt` |
| 其他 | `application/octet-stream` |

### D12 限流与降级状态机

```
          throttleSignals >= 3（403/429/503/连接被拒，累计计数，按需、预缓存、纯转发的请求都算）
NORMAL(3) ───────────────────────────────────────────────▶ THROTTLED(1)      单向，本会话内不恢复
任意状态  ──任何一次 DISK_ERROR，或者打开命中文件失败但文件还在──▶ DEGRADED   单向
任意状态  ──close()──▶ CLOSED
```

- **限流阈值**：`config.throttleThreshold = 3`。需求说的「多次」取 3：1 次可能是偶发，3 次已经足够说明源站在限流。
- **计数方式**：累计计数，不要求连续。这样实现简单，也满足「本次会话内不再恢复」。
- **降级时**：
  - 取消全部预缓存下载，不再调用 `pump()`。
  - 正在进行的按需下载继续，它的读者照常拿到数据。
  - 之后所有请求都走纯转发，不再读写磁盘。
  - 会话目录留到会话结束时再删。
- 新会话（包括复用同一地址）的限流和降级状态从头开始。

### D13 会话切换（`HlsPrecache`，持门面锁）

```
rewrite(url, content, headers, port):
    playlist = HlsPlaylist.parse(url, content)            // 可能为 null
    old = current; current = null
    if (old != null) old.close()                          // Call.cancel()，不阻塞
    reuse = playlist != null && cacheRoot != null && old != null && old.store().playlistUrl().equals(url)
    if (old != null && !reuse) ioExecutor.execute(old.store()::deleteAll)
    if (playlist == null || cacheRoot == null) return null
    id = nextId()
    store = reuse ? old.store() : new SegmentStore(new File(cacheRoot, "s" + id), url)
    current = new PrecacheSession(id, playlist, copy(headers), store, config, downloader, downloadExecutor)
    return playlist.render(prefix(port, id))

endSession(): old = current; current = null; if (old != null) { old.close(); ioExecutor.execute(old.store()::deleteAll); }

rewrite(url, content, headers, port, owner): 同 4 参版，新会话建好时在门面锁里记下 currentOwner = owner；4 参版即 owner = null。
                                            不适用或抛异常时同 4 参版：无条件结束上一个会话，返回 null。
endSession(owner): owner == null → 什么都不做；持门面锁比较 currentOwner == owner（同一个对象），
                   不相等什么都不做，相等时和 endSession() 一样结束。current 被摘下时 currentOwner 一起清空。

openSegment(sid, idx): 校验参数（400）→ s = current（volatile 读，不持门面锁）→ s == null 或 !s.id().equals(sid) → 410
                       → idx >= s.segmentCount() → 404 → s.open(idx)；捕获 Throwable → 500
```

- **复用时取消旧会话的下载，而不是交给新会话接管**：接管需要跨会话转移读者和计数，复杂度和收益不成比例。被取消的分片很快会被新会话重新下载。
- **复用的判断**：只比较 `playlistUrl` 是否完全相同（`String.equals`），不做规范化。

### D14 测试策略

测试类放在 `app/src/test/java/com/github/tvbox/osc/precache/`，全部用纯 JUnit 4，不用 Robolectric。

| 测试类 | 覆盖内容 |
| --- | --- |
| `HlsPlaylistTest` | 表驱动测试，覆盖 A1–A3。包括：`\r\n` 换行、空行、残留的 DISCONTINUITY、相对和绝对分片地址、多个 KEY、MAP、`METHOD=NONE`、MEDIA-SEQUENCE 不变，以及每一种不适用的情况 |
| `PrecacheWindowTest` | 窗口边界、回看边界、末尾截断、长度不一的分片、`isJump` |
| `OkHttpSegmentDownloaderTest` | 用 MockWebServer 测：请求头 trim、丢弃的头、2xx 和非 2xx、Content-Length、gzip 透明解压、`cancel()` 让 `execute` 或读取失败、连接被拒抛 `ConnectException` |
| `SegmentStoreTest` | 用 `TemporaryFolder` 测：commit、外部删除后按未命中处理、目录被删后 mkdirs、`committedBytes`、`averageSize`、`deleteAll` |
| `SegmentDownloadTest` | 用可控的 `FakeSegmentDownloader`：多个读者跟读的字节和源站一致、只请求源站 1 次、HTTP 错误、长度不符、cancel、磁盘失败（`cacheRoot` 指向一个普通文件） |
| `PrecacheSessionTest` | A5–A12：命中、未命中、并发等待、错误码、502、降级为纯转发、窗口和最多 3 个并发、暂停时填满窗口后停止、回看淘汰、跳转取消、上限缩窗、限流降到 1 |
| `HlsPrecacheTest` | A13 和接口契约：400/404/410、代理地址格式、复用和清空目录、`endSession`、不适用时结束旧会话、没有设置 cacheRoot 时返回 null |

- **测试替身**：`FakeSegmentDownloader` 放在测试目录。
  - 按 URL 预设状态码、字节或异常。
  - 可以用闸门（`CountDownLatch`）卡住响应头或 body 的读取。
  - 记录每个 URL 被请求的次数和请求头。
- **执行器**：测试里 `downloadExecutor` 用真实的 `Executors.newCachedThreadPool()`，`ioExecutor` 用 `Runnable::run`。
- **测试参数**：`PrecacheConfig` 用小参数，比如上限 350 字节、每片 100 字节，来测上限。
- **断言方式**：需要等后台下载的断言，用带超时的轮询辅助方法，最长 5 秒，不能用固定的 sleep。

### D15 owner-only 改动：`app/build.gradle`

在 `app/build.gradle` 的 `testImplementation 'androidx.test:core:1.5.0'`（当前在第 198 行）之后加一行：

```groovy
    testImplementation 'com.squareup.okhttp3:mockwebserver:3.12.11'
```

**版本用 3.12.11，不用 issue 里写的 3.12.13**：

- 已经核实，Maven Central 上 3.12.0–3.12.13 都存在。
- 但 `mockwebserver-3.12.13.pom` 依赖 `okhttp:${project.version}`，也就是 okhttp 3.12.13。Gradle 解决版本冲突时会选更高的版本，结果单测运行时的 okhttp 被升到 3.12.13，和生产环境的 3.12.11 不一致。
- 3.12.11 和生产版本完全一致，不会带来版本冲突。
- 它还会传递依赖 junit 4.12，会被现有的 4.13.2 覆盖，没有影响。

`tasks.md` 的任何 writes 都不包含这个文件。主代理在开发阶段开始前提交这项改动，`1.3` 的测试依赖它。

## Risks / Trade-offs

- **[风险] 跟读依赖「rename 之后已经打开的 fd 仍然有效」。** 少数 ROM 的外部存储是非 POSIX 的 FUSE 实现，可能不满足这一点。→ 缓解：读取出错时 `open` 按未命中重来一次（D8）；打开命中文件失败会触发降级。真机手动验证（S3 的手动验证第 2、4 条）时观察。
- **[风险] 磁盘在写到一半时失败，会让当前正在跟读的那个请求中途断开。** → 缓解：播放器会重试这个分片，重试时会话已经降级为纯转发，可以拿到数据。Exo 默认会重试分片加载。IJK 可能直接跳过这个分片，这个代价可以接受，因为磁盘写入失败本来就很少见。
- **[风险] 播放器在 Range 续传时（ffmpeg 重连时带 `Range: bytes=N-`）拿到的是 200 和完整内容。** → 首个请求的 `Range: bytes=0-` 不受影响。中途重连的情况很少，而且 HTTP 允许服务端忽略 Range。第一期不支持 Range，已写进 Non-Goals。
- **[取舍] 额外补充的不适用规则：`#EXT-X-PART` 和 `#EXT-X-PRELOAD-HINT`。** 这两个是 LL-HLS 标签，自带相对 URI。如果不改写它们的 URI，就会被解析到 `127.0.0.1/m3u8` 上，导致请求失败。点播列表几乎不会有这两个标签，所以直接判为不适用，退回现有逻辑播放，最安全。
- **[取舍] 按需下载不受磁盘上限约束。** 磁盘占用可能超出上限，最多超出并发数个分片，换来的是播放不会被卡住。
- **[取舍] 复用时取消旧会话正在进行的下载。** 会浪费一点已经下载的数据，换来的是会话之间没有共享的可变状态。
- **[取舍] 第一个分片一定未命中**（D9），换来的是续播时不会浪费带宽。
- **[风险] S2 和 S3 在本 change 合并之前编译不了。** → 并行开发期间，它们只按本文的签名写代码；集成分支上，三个 change 一起合并后再编译。

## Migration Plan

- 本 change 只新增代码，没有调用方，合并后不影响任何现有行为。
- 回滚：直接 revert。S3 的开关默认关闭，就算 S3 已经合并，关闭开关也就不会走到这里。
- `hlscache/` 的启动清理由 S3 加进 `cleanPlayerCache()`，旧版本没有这个目录，不需要迁移。
