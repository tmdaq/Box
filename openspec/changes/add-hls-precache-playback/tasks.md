## 前置

- 本 change 不需要改 owner-only 文件（design.md D11）。
- **依赖 S1（#2）**：3.1、4.1、4.2 引用 S1 的门面类 `HlsPrecache`（`CACHE_DIR_NAME`、`get()`、`setCacheRoot`、`rewrite`、`endSession`）。S1 的接口骨架会先在 change 分支上提交，这几个任务基于它编译；签名以 `openspec/changes/add-hls-precache-core/design.md`「跨 change 接口」为准，不改 S1 的任何文件。1.1、1.2、2.1 不依赖 S1。
- 路径都相对仓库根目录。

## 1. 抽取共享判断

- [ ] 1.1 characterization test：新建 `M3u8PlayPlanner`（design.md D1），把 PlayFragment `playUrl` 里 :684-:691 的前置判断、:710 的 `#EXTM3U` 判断、:714-742 的子列表查找、:744-753 / :764-770 的 purify 与地址选择逐字搬进 `shouldFetch` / `isPlaylist` / `findForwardUrl` / `plan`。`precache` 分支先不实现（传 true 也按 false 处理），`Purifier`、`Rewriter`、`Plan` 按 D1 的签名定好。用 `M3u8PlayPlannerTest` 锁住现有行为，用例见 D10 第一行的「第 1 步」。本任务不改 PlayFragment / PlayActivity
  - writes: app/src/main/java/com/github/tvbox/osc/util/M3u8PlayPlanner.java, app/src/test/java/com/github/tvbox/osc/util/M3u8PlayPlannerTest.java
  - reads: openspec/changes/add-hls-precache-playback/design.md, app/src/main/java/com/github/tvbox/osc/ui/fragment/PlayFragment.java, app/src/main/java/com/github/tvbox/osc/ui/activity/PlayActivity.java, app/src/main/java/com/github/tvbox/osc/util/M3U8.java, app/src/test/java/com/github/tvbox/osc/util/M3U8PurifyTest.java
  - depends-on: 无
  - verify: com.github.tvbox.osc.util.M3u8PlayPlannerTest
- [ ] 1.2 TDD 加上 `precache` 分支：`shouldFetch` 在去广告关闭、`precache=true` 时对 m3u8 地址返回 true；`plan` 按 D1 的规则把净化结果或原文交给 `Rewriter`，非 null 时返回本地 `/m3u8`、`precached=true`，null 时回到现有逻辑。用例见 D10 第一行的「第 2 步」，1.1 的用例全部保持通过
  - writes: app/src/main/java/com/github/tvbox/osc/util/M3u8PlayPlanner.java, app/src/test/java/com/github/tvbox/osc/util/M3u8PlayPlannerTest.java
  - reads: openspec/changes/add-hls-precache-playback/design.md, openspec/changes/add-hls-precache-playback/specs/vod-player/spec.md, openspec/changes/add-hls-precache-playback/specs/parse-sniff-adblock/spec.md, openspec/changes/add-hls-precache-core/design.md
  - depends-on: 1.1
  - verify: com.github.tvbox.osc.util.M3u8PlayPlannerTest

## 2. 设置开关

- [ ] 2.1 新增 Hawk 键 `HLS_PRECACHE = "hls_precache"`（默认 false）；设置页照「广告过滤」的写法加「预缓存」（D7）：`ModelSettingFragment` 的字段、初始化、点击切换；两份 `fragment_model.xml` 把 `llHlsPrecache` 放进 `llVideoPurify` 所在的容器，两格各占一半，按 D7 统一外边距；两个语言目录加 `mn_hls_precache`（D8）。不加 `nextFocus*`，实测焦点不对时再补，两份布局一起改
  - writes: app/src/main/java/com/github/tvbox/osc/util/HawkConfig.java, app/src/main/java/com/github/tvbox/osc/ui/fragment/ModelSettingFragment.java, app/src/main/res/layout/fragment_model.xml, app/src/main/res/layout-v21/fragment_model.xml, app/src/main/res/values/strings.xml, app/src/main/res/values-zh/strings.xml
  - reads: openspec/changes/add-hls-precache-playback/design.md, openspec/changes/add-hls-precache-playback/specs/settings-data/spec.md
  - depends-on: 无
  - verify: 无单测
  - manual-verify: 按 design.md「手动验证」第 1 项逐步操作：首次安装显示「关闭」；遥控器从「广告过滤」按右、从「播放器设置」按下都能到「预缓存」，从「预缓存」按上、左、下分别到「播放器设置」「广告过滤」「壁纸重置」，从「壁纸重置」按上回到「预缓存」；确认键和触屏点击都能切换；退出再进、重启 App 后状态保持；英文界面标题为 `Precache`

## 3. 缓存目录

- [x] 3.1 `FileUtils.cleanPlayerCache()` 拆出包内重载 `cleanPlayerCache(String cachePath, String externalCachePath)`，增加删除 `<外部缓存目录>/` + `HlsPrecache.CACHE_DIR_NAME`（D9），先写 `FileUtilsCleanPlayerCacheTest` 再改；`App.onCreate()` 紧跟 `FileUtils.cleanPlayerCache()` 之后调用 `HlsPrecache.get().setCacheRoot(new File(FileUtils.getExternalCachePath(), HlsPrecache.CACHE_DIR_NAME))`。依赖 S1 的接口骨架
  - writes: app/src/main/java/com/github/tvbox/osc/util/FileUtils.java, app/src/test/java/com/github/tvbox/osc/util/FileUtilsCleanPlayerCacheTest.java, app/src/main/java/com/github/tvbox/osc/base/App.java
  - reads: openspec/changes/add-hls-precache-playback/design.md, openspec/changes/add-hls-precache-core/design.md
  - depends-on: 无（需要 S1 的接口骨架已经提交到 change 分支）
  - verify: com.github.tvbox.osc.util.FileUtilsCleanPlayerCacheTest
  - manual-verify: 在 `/sdcard/Android/data/com.github.tvbox.osc.tk/cache/` 下手动建 `hlscache/s1/a.seg`，杀进程后重启 App，`adb shell ls` 确认 `hlscache/` 已不存在；`ijkcaches/` 等原有清理项照旧被删除

## 4. 播放入口接入

- [ ] 4.1 PlayFragment 接入（D2–D6）：`playUrl` 改成三参重载并调用 `M3u8PlayPlanner`；新增 `plan`、`playPlan`、`isExternalPlayer`；`startPlayUrl` 改成带 `keepPrecacheSession` 的重载，默认先 `endSession()`；荐片 :1317 / :1319、迅雷回调 :1339 传 `precacheAllowed=false`；`onDestroyView` 在 `mVideoView.release()` 后 `endSession()`。OkGo 回调结构、tag、请求头、`onError` 回退保持不变。依赖 S1 的接口骨架
  - writes: app/src/main/java/com/github/tvbox/osc/ui/fragment/PlayFragment.java
  - reads: openspec/changes/add-hls-precache-playback/design.md, openspec/changes/add-hls-precache-playback/specs/vod-player/spec.md, openspec/changes/add-hls-precache-core/design.md, app/src/main/java/com/github/tvbox/osc/util/M3u8PlayPlanner.java, app/src/main/java/com/github/tvbox/osc/util/HawkConfig.java, app/src/main/java/com/github/tvbox/osc/ui/activity/DetailActivity.java
  - depends-on: 1.2, 2.1（需要 S1 的接口骨架已经提交到 change 分支）
  - verify: 无单测
  - manual-verify: design.md「手动验证」第 2–8 项中涉及详情页小窗的步骤：窗口预览开启时进入详情页，小窗播放出现会话目录；小窗与全屏切换目录名不变；后台「开启」时按 Home 继续播放且目录仍在；下一集换新目录、重播目录名不变；返回离开详情页后目录被清空；预缓存关闭时行为与改动前一致（去广告成功仍播本地 `/m3u8`，分片指向源站）；选 MX 时交给 MX 的地址与关闭预缓存时相同且没有会话目录
- [ ] 4.2 PlayActivity 接入：和 4.1 相同的改动；特殊链接是 `tvbox-drive://` :1440、荐片 :1447 / :1449、迅雷回调 :1469；`onDestroy` 和 `onPictureInPictureModeChanged` 关闭小窗分支（`onStopCalled` 时 release 之后）各加一次 `endSession()`（D6）。依赖 S1 的接口骨架
  - writes: app/src/main/java/com/github/tvbox/osc/ui/activity/PlayActivity.java
  - reads: openspec/changes/add-hls-precache-playback/design.md, openspec/changes/add-hls-precache-playback/specs/vod-player/spec.md, openspec/changes/add-hls-precache-core/design.md, app/src/main/java/com/github/tvbox/osc/util/M3u8PlayPlanner.java, app/src/main/java/com/github/tvbox/osc/util/HawkConfig.java, app/src/main/java/com/github/tvbox/osc/ui/fragment/PlayFragment.java
  - depends-on: 1.2, 2.1（需要 S1 的接口骨架已经提交到 change 分支）
  - verify: 无单测
  - manual-verify: design.md「手动验证」第 2–8 项中涉及全屏播放页的步骤：窗口预览关闭时从详情页进入全屏播放页，四个内核都能起播并出现会话目录；AES-128 片源正常；暂停时目录先变大后停止增长；跳转后旧分片被删除；后台「画中画」时按 Home 进入画中画目录仍在，关闭小窗后目录被清空；返回离开播放页后目录被清空；存储空间里的 m3u8 和迅雷视频能播放且不出现会话目录

## 5. 验证

- [ ] 5.1 验证：构建 + 测试通过，并附手动验证结果
  - writes: 无
  - reads: docs/agents/dev-workflow.md, openspec/changes/add-hls-precache-playback/design.md
  - depends-on: 1.1, 1.2, 2.1, 3.1, 4.1, 4.2
  - verify: `GRADLE_TASK=:app:assembleArm64GenericNormalDebug scripts/gradle-test.sh`，以及 `scripts/gradle-test.sh`（全量单测）。本 change 不涉及 `pyramid/` 和 python flavor，extra-verify 为「无」
  - manual-verify: 在 S1、S2、S3 合并后的集成分支上，按 design.md「手动验证」1–9 项逐项执行（第 9 项依赖 S2 的分片代理端点），每项记录通过与否和观察到的数据（第 7 项附两组卡顿次数和时长），结果附在交付说明里
