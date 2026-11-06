## 前置

- 不改 owner-only 文件（design.md D8）。
- **对 S1 的依赖**：2.1 和 2.2 要编译 S1（#2，`add-hls-precache-core`）的 `com.github.tvbox.osc.precache.HlsPrecache` 和 `SegmentResponse`。S1 的接口骨架会先提交到它的 change 分支上；dev-implement 会让本 change 从这个骨架提交拉分支。本 change 的测试运行时不调用 `HlsPrecache`（design.md D6），所以只有骨架也能全部通过。只能用 S1 design「跨 change 接口」一节里的 public API，不能引用 S1 的包内成员，也不能修改 S1 的文件。
- 1.1 不依赖 S1，可以和 2.1 并行。

所有路径都相对仓库根目录。每个任务都先写测试，再写实现（TDD）；1.1 只写测试，它必须在没有改动的代码上直接通过。

## 1. 锁住现有路由

- [x] 1.1 `RemoteServer.serve` 的 characterization test，以及原始 HTTP 测试辅助类。按 design.md D7 的「characterization test 的范围」实现，共 8 条：
  - `/m3u8` 返回 `m3u8Content`
  - `/` 命中 `getRequestList`
  - 未知路径落到默认页
  - `/proxy` 既没有 `do` 也没有 `go` 时落到默认页
  - `/media` 的 JSON
  - POST 到未知路径时落到默认页
  - 每个请求都 post `SERVER_CONNECTION`
  - 真实 socket 上 `/m3u8` 的 gzip 行为（带 `Accept-Encoding: gzip` 时有 `Content-Encoding: gzip`，不带时没有，并且有 `Content-Length`）

  只写测试，不改生产代码；写完后在当前代码上直接通过。`RawHttpClient` 用 `java.net.Socket` 发原始请求，返回状态行、不区分大小写的响应头和 body，支持解 chunked，不做透明解压。
  - writes: app/src/test/java/com/github/tvbox/osc/server/RemoteServerRoutesTest.java, app/src/test/java/com/github/tvbox/osc/server/RawHttpClient.java
  - reads: openspec/changes/add-segment-proxy-endpoint/design.md, app/src/main/java/com/github/tvbox/osc/server/RemoteServer.java, app/src/main/java/com/github/tvbox/osc/server/RawRequestProcess.java, app/src/main/java/com/github/tvbox/osc/event/ServerEvent.java, app/src/test/java/com/github/tvbox/osc/util/VideoParseRulerTest.java
  - depends-on: 无
  - verify: com.github.tvbox.osc.server.RemoteServerRoutesTest

## 2. 分片代理端点

- [x] 2.1 新增 `SegmentProxyResponse`（design.md D3、D4）：
  - 继承 `NanoHTTPD.Response`
  - 静态方法 `from(SegmentResponse)`、`empty(int)`、`create(int, String, InputStream, long, Closeable)`
  - 内部类 `SegmentStatus`：原因短语表，表里没有的按类别兜底
  - body 外面统一包一层流，关闭时连带关闭 resource，并且只关闭一次

  纯 JUnit 测试覆盖：
  - 200、400、403、404、410、429、500、502、503、504 的 `getRequestStatus()` 和 `getDescription()`；表外状态码（如 418、599）按类别兜底
  - `getMimeType()` 等于传入的 contentType
  - body 为 null 时，`getData()` 读出来是空的
  - 关闭 `Response` 时，被包装的 body 和 resource 都被关闭；关两次，resource 只关闭一次
  - resource 为 null 时关闭不抛异常
  - `empty(403)` 的 MIME 是 `text/plain`

  定长和 chunked 的区分在 2.2 的真实 socket 测试里验证。
  - writes: app/src/main/java/com/github/tvbox/osc/server/SegmentProxyResponse.java, app/src/test/java/com/github/tvbox/osc/server/SegmentProxyResponseTest.java
  - reads: openspec/changes/add-segment-proxy-endpoint/design.md, openspec/changes/add-hls-precache-core/design.md（只读「跨 change 接口」一节）, app/src/main/java/com/github/tvbox/osc/precache/SegmentResponse.java（S1 骨架）
  - depends-on: 无（需要 S1 接口骨架已经提交）
  - verify: com.github.tvbox.osc.server.SegmentProxyResponseTest
- [x] 2.2 在 `RemoteServer` 注册分片代理路由（design.md D1、D2、D5、D6）：
  - GET 分支的第一条语句匹配 `HlsPrecache.SEGMENT_PATH`，交给 `serveSegment`
  - 静态方法 `isLoopback(String)`
  - 包内可覆盖的测试接缝 `openSegment(String, String)`，默认实现是 `SegmentProxyResponse.from(HlsPrecache.get().openSegment(...))`
  - catch Throwable，返回 `empty(500)`
  - 覆盖 `useGzipWhenAccepted`，对 `SegmentProxyResponse` 返回 false

  `RemoteServerSegmentRouteTest` 按 design.md D7「新路由的测试」实现：
  - mock session：4 种回环地址放行，局域网地址、空串、null 返回 403 且不调用接缝，缺参数时传 `(null, null)`，接缝抛异常时返回 500，`POST /hlsseg` 不进入分片代理
  - 真实 socket：定长、chunked、`text/plain` 不 gzip、502 状态行、发送完毕后 resource 只关闭一次、客户端中途断开后 5 秒内 resource 被关闭

  1.1 的 `RemoteServerRoutesTest` 必须不加修改地继续通过。
  - writes: app/src/main/java/com/github/tvbox/osc/server/RemoteServer.java, app/src/test/java/com/github/tvbox/osc/server/RemoteServerSegmentRouteTest.java
  - reads: openspec/changes/add-segment-proxy-endpoint/design.md, openspec/changes/add-segment-proxy-endpoint/specs/remote-control-push/spec.md, openspec/changes/add-hls-precache-core/design.md（只读「跨 change 接口」一节）, app/src/main/java/com/github/tvbox/osc/precache/HlsPrecache.java（S1 骨架）, app/src/main/java/com/github/tvbox/osc/server/SegmentProxyResponse.java, app/src/test/java/com/github/tvbox/osc/server/RawHttpClient.java, app/src/test/java/com/github/tvbox/osc/server/RemoteServerRoutesTest.java
  - depends-on: 1.1, 2.1
  - verify: com.github.tvbox.osc.server.RemoteServerSegmentRouteTest, com.github.tvbox.osc.server.RemoteServerRoutesTest

## 3. 验证

- [ ] 3.1 验证：构建 + 测试通过，并附手动验证结果
  - writes: 无
  - reads: docs/agents/dev-workflow.md
  - depends-on: 1.1, 2.1, 2.2
  - verify: `GRADLE_TASK=:app:assembleArm64GenericNormalDebug scripts/gradle-test.sh`，以及 `scripts/gradle-test.sh`（全量单测）
  - manual-verify:
    - 第 1、2 步在本 change 分支上就能做。下文的 `<端口>` 是盒子上本地服务的实际端口，`<盒子IP>` 是盒子的局域网 IP，两者都可以在首页「推送」页看到。第 3 步要求 S1、S3 都已经合并，在 dev-test 的集成分支上做。
    1. **局域网请求返回 403**：在同一局域网的另一台电脑上执行 `curl -i "http://<盒子IP>:<端口>/hlsseg?s=x&i=0"`，应该返回 `HTTP/1.1 403 Forbidden`，body 为空。再执行 `curl -i "http://<盒子IP>:<端口>/"`，应该仍然返回遥控网页，说明其他路由不受影响。
    2. **本机请求能进入核心**：在电脑上执行 `adb forward tcp:19978 tcp:<端口>`，这样连接在盒子上的对端地址是本机。然后：
       - `curl -i "http://127.0.0.1:19978/hlsseg"`：集成分支上应该返回 `HTTP/1.1 400 Bad Request`；只有 S1 骨架时，取决于骨架的实现，返回 500 或骨架给出的状态码，但不能是 403。
       - `curl -i "http://127.0.0.1:19978/hlsseg?s=zzz&i=0"`：集成分支上应该返回 `HTTP/1.1 410 Gone`。
    3. **本机播放时分片走代理**（集成分支）：
       1. 打开设置里的「预缓存」开关，播放一部 HLS 点播，片源要带 `#EXT-X-ENDLIST`。
       2. 在电脑上执行 `curl -s http://127.0.0.1:19978/m3u8`，看到分片行都是 `http://127.0.0.1:<端口>/hlsseg?s=<id>&i=<n>`。
       3. 取其中一行，把端口换成 19978，执行 `curl -s -D - -o /dev/null -H "Accept-Encoding: gzip" "<地址>"`，应该返回 200，并且没有 `Content-Encoding` 头。
       4. 执行 `adb shell ls -la /sdcard/Android/data/com.github.tvbox.osc.tk/cache/hlscache/`，应该能看到 `s<id>/` 目录，里面的 `.seg` 文件在增加。
       5. 视频正常播放，拖动进度后也能继续播放。
  - 结果（dev-test，集成分支 `1-integration` @ d42a54f）：`assembleArm64GenericNormalDebug` BUILD SUCCESSFUL；全量单测 16 个类 191 个用例 0 失败（连续 4 次）。
  - 手动验证：**未执行**。用户决定暂缓真机验证，先合并交付；清单见 #1，待后续补验。
