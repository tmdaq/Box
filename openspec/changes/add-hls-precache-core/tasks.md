## 前置（owner-only，由主代理在开发开始前提交，不属于任何任务）

`app/build.gradle` 加 `testImplementation 'com.squareup.okhttp3:mockwebserver:3.12.11'`，见 design.md D15。

## 1. 预缓存核心

所有路径都相对仓库根目录。`<main>` = `app/src/main/java/com/github/tvbox/osc/precache`，`<test>` = `app/src/test/java/com/github/tvbox/osc/precache`。每个任务都先写失败的测试，再写实现（TDD）。

- [x] 1.1 m3u8 解析与改写：`HlsSegment`、`HlsPlaylist`（D2）。表驱动测试覆盖：分片一对一替换、`\r\n` 和空行、残留的 DISCONTINUITY、MEDIA-SEQUENCE 不变、相对和绝对分片地址、多个 KEY、MAP、`METHOD=NONE`、EXTINF 缺失时的时长回退、`key` 是绝对地址的 SHA-1，以及 D2 列出的每一种不适用情况
  - writes: app/src/main/java/com/github/tvbox/osc/precache/HlsSegment.java, app/src/main/java/com/github/tvbox/osc/precache/HlsPlaylist.java, app/src/test/java/com/github/tvbox/osc/precache/HlsPlaylistTest.java
  - reads: openspec/changes/add-hls-precache-core/design.md, openspec/changes/add-hls-precache-core/specs/vod-player/spec.md, app/src/main/java/com/github/tvbox/osc/util/M3U8.java, app/src/test/java/com/github/tvbox/osc/util/M3U8PurifyTest.java
  - depends-on: 无
  - verify: com.github.tvbox.osc.precache.HlsPlaylistTest
- [x] 1.2 窗口计算：`PrecacheWindow`（D9）。测试覆盖：每片 10 秒、锚点 10 时窗口是 10..21、回看是 7..9；列表末尾截断；长度不一的分片；锚点为 0；`isJump` 的各种情况
  - writes: app/src/main/java/com/github/tvbox/osc/precache/PrecacheWindow.java, app/src/test/java/com/github/tvbox/osc/precache/PrecacheWindowTest.java
  - reads: openspec/changes/add-hls-precache-core/design.md
  - depends-on: 无
  - verify: com.github.tvbox.osc.precache.PrecacheWindowTest
- [x] 1.3 回源接口、OkHttp 实现和测试替身：`SegmentDownloader`（含 `Call`、`Source`）、`OkHttpSegmentDownloader`（D4）、`FakeSegmentDownloader`。用 MockWebServer 测试：请求头 trim、丢弃的头、2xx 和非 2xx、Content-Length 已知和未知、gzip 透明解压、`cancel()` 让执行或读取失败、连接被拒抛 `ConnectException`
  - writes: app/src/main/java/com/github/tvbox/osc/precache/SegmentDownloader.java, app/src/main/java/com/github/tvbox/osc/precache/OkHttpSegmentDownloader.java, app/src/test/java/com/github/tvbox/osc/precache/OkHttpSegmentDownloaderTest.java, app/src/test/java/com/github/tvbox/osc/precache/FakeSegmentDownloader.java
  - reads: openspec/changes/add-hls-precache-core/design.md, app/src/main/java/com/github/tvbox/osc/util/OkGoHelper.java, app/build.gradle
  - depends-on: 无（需要前置的 build.gradle 改动已经提交）
  - verify: com.github.tvbox.osc.precache.OkHttpSegmentDownloaderTest
- [x] 1.4 磁盘存储与单个分片的下载：`SegmentStore`（D6）、`SegmentDownload`（D7）。用 `TemporaryFolder` 测试：
  - commit 后能读到，`committedBytes`、`averageSize` 正确
  - 文件被外部删除后按未命中处理；目录被删后会 mkdirs 重建；`deleteAll`
  - 多个读者跟读拿到的字节和源站一致，源站只被请求 1 次
  - HTTP 错误不写盘；长度和 Content-Length 不符时为 FAILED
  - cancel 后读者抛 IOException，并且不会 commit
  - 目录无法创建时为 DISK_ERROR
  - writes: app/src/main/java/com/github/tvbox/osc/precache/SegmentStore.java, app/src/main/java/com/github/tvbox/osc/precache/SegmentDownload.java, app/src/test/java/com/github/tvbox/osc/precache/SegmentStoreTest.java, app/src/test/java/com/github/tvbox/osc/precache/SegmentDownloadTest.java
  - reads: openspec/changes/add-hls-precache-core/design.md, app/src/main/java/com/github/tvbox/osc/precache/SegmentDownloader.java, app/src/main/java/com/github/tvbox/osc/precache/HlsSegment.java, app/src/test/java/com/github/tvbox/osc/precache/FakeSegmentDownloader.java
  - depends-on: 1.1, 1.3
  - verify: com.github.tvbox.osc.precache.SegmentStoreTest, com.github.tvbox.osc.precache.SegmentDownloadTest
- [x] 1.5 会话与调度：`PrecacheConfig`、`SegmentResponse`、`PrecacheSession`（D8、D9、D10、D11、D12）。按 spec 场景逐条测试：
  - 命中、未命中边下边返回、同一分片的并发请求只回源 1 次
  - 源站错误码原样返回、无法连接返回 502
  - 磁盘失败后降级为纯转发
  - 窗口内最多 3 个并发、暂停时填满窗口后停止
  - 回看淘汰、跳转时取消窗口外的预缓存下载
  - 达到上限时先淘汰回看分片，再缩短窗口
  - 累计 3 次限流信号后降到 1 个并发，并且不再恢复
  - writes: app/src/main/java/com/github/tvbox/osc/precache/PrecacheConfig.java, app/src/main/java/com/github/tvbox/osc/precache/SegmentResponse.java, app/src/main/java/com/github/tvbox/osc/precache/PrecacheSession.java, app/src/test/java/com/github/tvbox/osc/precache/PrecacheSessionTest.java, app/src/test/java/com/github/tvbox/osc/precache/FakeSegmentDownloader.java
  - reads: openspec/changes/add-hls-precache-core/design.md, openspec/changes/add-hls-precache-core/specs/vod-player/spec.md, app/src/main/java/com/github/tvbox/osc/precache/HlsPlaylist.java, app/src/main/java/com/github/tvbox/osc/precache/PrecacheWindow.java, app/src/main/java/com/github/tvbox/osc/precache/SegmentStore.java, app/src/main/java/com/github/tvbox/osc/precache/SegmentDownload.java
  - depends-on: 1.1, 1.2, 1.4
  - verify: com.github.tvbox.osc.precache.PrecacheSessionTest
- [x] 1.6 门面：`HlsPrecache`（D3、D5、D13，以及 design.md「跨 change 接口」一节的签名，必须逐字一致）。测试覆盖：
  - 400/404/410
  - 代理地址的格式 `http://127.0.0.1:<port>/hlsseg?s=<id>&i=<n>`，每次 rewrite 都换新的 id
  - 同一 playlistUrl 复用分片并且不删目录；不同 playlistUrl 或调用 `endSession` 时删除旧目录
  - 不适用时结束旧会话
  - 没有设置 cacheRoot 时返回 null
  - rewrite 内部出现异常时返回 null
  - writes: app/src/main/java/com/github/tvbox/osc/precache/HlsPrecache.java, app/src/test/java/com/github/tvbox/osc/precache/HlsPrecacheTest.java
  - reads: openspec/changes/add-hls-precache-core/design.md, app/src/main/java/com/github/tvbox/osc/precache/PrecacheSession.java, app/src/main/java/com/github/tvbox/osc/precache/OkHttpSegmentDownloader.java, app/src/test/java/com/github/tvbox/osc/precache/FakeSegmentDownloader.java
  - depends-on: 1.3, 1.5
  - verify: com.github.tvbox.osc.precache.HlsPrecacheTest

## 2. 验证

- [ ] 2.1 验证：构建 + 测试通过
  - writes: 无
  - reads: docs/agents/dev-workflow.md
  - depends-on: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6
  - verify: `GRADLE_TASK=:app:assembleArm64GenericNormalDebug scripts/gradle-test.sh`，以及 `scripts/gradle-test.sh`（全量单测）。本 change 不涉及 UI，没有手动验证项；真机上的手动验证由 S3（#4）负责
