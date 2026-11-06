Issue: #2

## Why

点播时播放受源站质量和网速影响，容易卡顿。各播放器内核只有内存预读缓冲，而且都用默认值，不会主动往后多下载（见 #1 事实清单）。ADR 0001 已定：在本地服务上做分片代理，把 HLS 分片提前下载到磁盘，对 Exo、IJK、阿里、系统 4 个内核一视同仁。

本 change 是 #1 的第一个 sub-issue（S1），只做与 Android 界面、本地服务都无关的预缓存核心。分片代理端点（S2，#3）和播放入口接入（S3，#4）并行开发，只依赖本 change 在 `design.md` 里定下的接口。

## What Changes

- 新增包 `com.github.tvbox.osc.precache`，包含以下内容：
  - **m3u8 改写**：对带 `#EXT-X-ENDLIST` 的单码率媒体列表，把每个分片 URI 行一对一替换成本地分片代理地址；`#EXT-X-KEY`、`#EXT-X-MAP` 的 URI 补成绝对地址；其余行逐字保留。不适用的列表返回 null。
  - **分片下载与磁盘存储**：原样下载、原样存储、不解密。请求源站时带上 trim 后的原始请求头。同一分片不重复下载，并发请求共用同一次下载。源站 HTTP 错误原样交给调用方；磁盘写入失败时本次会话降级为纯转发；分片文件被外部删除时按未命中处理。
  - **预缓存会话与调度**：锚点往后 120 秒、往前保留 30 秒；最多 3 个并发下载；跳转时取消窗口外的任务；每个会话最多 300MB；多次被限流时降到 1 个并发；同一时间只有一个会话，playlistUrl 相同时复用已下载的分片。
  - **对外接口**：门面类 `HlsPrecache`，供 S3 调 `rewrite` / `endSession`，供 S2 调 `openSegment`；分片代理路径常量和 query 参数名也由它定义。
- 核心逻辑可以用纯 JUnit 测试：缓存目录、执行器、下载器都通过注入传入，不依赖 NanoHTTPD 和 `App.getInstance()`。
- 测试依赖新增 MockWebServer，用来测试真实的 OkHttp 下载器。`app/build.gradle` 是 owner-only 文件，具体改动写在 `design.md`，由主代理提交。

## Non-goals

- 不在 `RemoteServer` 注册分片代理路由，也不做 loopback（只允许 `127.0.0.1`）校验。这部分属于 S2（#3）。
- 不接入 `PlayFragment` / `PlayActivity`，不加设置开关，不改 `FileUtils.cleanPlayerCache()`，不改 `App`。这部分属于 S3（#4）。哪些播放走预缓存（作用范围），也由 S3 的 requirement 规定。
- 不修改 `M3U8.purify`，不修改现有的 `/m3u8` 路由。
- 不做直链 mp4/mkv 的分块预取，不预缓存下一集，参数不可调，不显示网速。
- 不解密，不处理 DRM。
- 不支持 master 列表、带 `#EXT-X-BYTERANGE` 的列表和直播列表（没有 `ENDLIST`）。
- 不支持 HTTP Range：分片代理总是返回完整分片。

## Capabilities

### New Capabilities

（无）

### Modified Capabilities

- `vod-player`：新增「HLS 预缓存」requirement，规定改写规则、不适用的情形、命中与未命中、并发等待、源站错误、磁盘降级、窗口与回看淘汰、跳转、300MB 上限、限流降线程、会话复用与清空。

## Impact

- **新增代码**：`app/src/main/java/com/github/tvbox/osc/precache/**`，以及 `app/src/test/java/com/github/tvbox/osc/precache/**`。
- **依赖**：运行时没有新增依赖，复用 okhttp 3.12.11 和 `OkGoHelper.getDefaultClient()`。测试依赖新增 `com.squareup.okhttp3:mockwebserver:3.12.11`（owner-only 改动，见 `design.md` D15）。
- **磁盘**：`<外部缓存目录>/hlscache/` 下，同一时间最多一个会话目录。启动时由谁清理，属于 S3。
- **下游**：S2（#3）和 S3（#4）按 `design.md` 的「跨 change 接口」一节实现。本 change 合并之前，它们引用的类还不存在。
