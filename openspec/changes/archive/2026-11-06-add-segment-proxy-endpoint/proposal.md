Issue: #3

## Why

HLS 预缓存（#1，方案见 `docs/adr/0001-hls-precache-via-local-segment-proxy.md`）把 m3u8 里的分片地址改写到本地服务上，播放器要通过一个 HTTP 端点拿到分片。预缓存核心（S1，#2）只给出了不依赖 NanoHTTPD 的 `HlsPrecache.openSegment` 和 `SegmentResponse`。本 change（S2）在 `RemoteServer` 上把这个端点接出来。

本地服务对局域网开放，所以这个端点必须只接受本机请求，避免局域网里的其他设备借它下载源站内容或读取缓存。

## What Changes

- `RemoteServer.serve` 新增分片代理路由 `GET /hlsseg?s=<会话>&i=<序号>`（路径和参数名取自 `HlsPrecache` 的常量）。只处理 GET，在现有的 `getRequestList`、`/media`、`/proxy` 等分支之前匹配。
- 对端地址不是本机回环地址时返回 403，不调用预缓存核心（验收标准 A4）。
- 本机请求交给 `HlsPrecache.get().openSegment(s, i)`，把结果转成 NanoHTTPD 响应：
  - 状态码原样返回，包括 NanoHTTPD 枚举里没有的 502、504 等（A7）；
  - 长度已知时返回定长响应，未知时返回 chunked 流式响应；没有 body 时返回空的定长响应；
  - 响应只带 Content-Type 和长度，不转发源站响应头；即使播放器发了 `Accept-Encoding: gzip`，也不带 `Content-Encoding`（A7）；
  - 分片 body 在发送完或连接断开后一定被关闭。
- 改代码之前，先写 characterization test 锁住 `serve()` 现有的路由行为。
- `remote-control-push` spec 的「HTTP 接口一览」加入分片代理一行和对应场景。

## Non-goals

- 不实现预缓存核心（m3u8 改写、下载、磁盘、调度、会话），这些属于 S1（#2）。
- 不接入 `PlayFragment` / `PlayActivity`，不加设置开关，不改 `cleanPlayerCache()`，这些属于 S3（#4）。
- 不改变任何已有路由的行为，包括 `/m3u8`、`/proxy`、默认页，以及它们现有的 gzip 行为。
- 不限制 `/m3u8` 等其他路由的局域网访问。
- 不支持 HTTP Range，不处理 HEAD 等 GET 以外的方法（这些请求按现有逻辑落到默认处理）。
- 不转发播放器发来的请求头。回源用的请求头由 S1 在 `rewrite` 时记下。
- 不做访问日志或统计。

## Capabilities

### New Capabilities

（无）

### Modified Capabilities

- `remote-control-push`：修改「HTTP 接口一览」，接口表加入分片代理，新增本机获取分片、局域网请求 403、源站错误原样返回、会话失效 410 四个场景。Purpose 一句也要补上「HLS 预缓存分片代理」，delta 表达不了，归档时手工修改，见 design.md 的 Migration Plan。

## Impact

- **代码**：
  - 修改 `app/src/main/java/com/github/tvbox/osc/server/RemoteServer.java`
  - 新增 `app/src/main/java/com/github/tvbox/osc/server/SegmentProxyResponse.java`
  - 测试新增在 `app/src/test/java/com/github/tvbox/osc/server/` 下
- **依赖**：编译时依赖 S1 的 `com.github.tvbox.osc.precache.HlsPrecache` 和 `SegmentResponse`，只用 design 里「跨 change 接口」一节的 public API。没有新的第三方依赖，也不改 owner-only 文件。
- **对外接口**：本地服务多了一个只对本机开放的 `/hlsseg` 端点。只要 S3 没有改写 m3u8，就不会有请求打到这里，现有功能不受影响。
