Issue: #4

## Why

#1 要在后台把即将播放的 HLS 分片提前下载到磁盘，减少点播卡顿。预缓存核心（S1，#2）只提供 `rewrite` / `endSession` 接口，分片代理端点（S2，#3）只负责在本地服务上提供分片。本 change 是 S3：把预缓存真正接到播放流程上，并给用户一个开关。没有这一步，前两个 change 合并后不会有任何效果。

## What Changes

- **设置开关**：设置页「播放器」组新增「预缓存」，和「广告过滤」同一种写法（点一下在开启和关闭之间切换），默认**关闭**。对应 Hawk 键 `hls_precache`，默认 false，老用户不需要迁移。
- **两个播放入口都接入**：开关打开时，`PlayFragment`（详情页小窗）和 `PlayActivity`（全屏播放页）里经过 `playUrl` 的 HLS 点播，都把最终要播的那份 m3u8 交给预缓存改写：
  - 去广告开启且净化成功时，改写净化后的内容；去广告关闭或净化失败时，改写原文。
  - 去广告关闭时，现在直接播原地址；开关打开后，改为也先下载 m3u8 再改写。
  - 改写成功时，播放器播本地 `/m3u8`，列表里的分片指向分片代理；不适用时，按现在的逻辑播放。
- **不走预缓存的情况**：开关关闭；外部播放器（MX、Reex、Kodi）；直播；`tvbox-drive://` 存储空间、迅雷、荐片、分段 mp4（yxdm）；地址里不含 `.m3u8` 的普通直链。外部播放器拿到的地址和现在完全一致。
- **会话生命周期**：离开播放页时结束预缓存会话；后台播放、画中画不算离开；播放不走预缓存的内容（包括外部播放器）时，也结束上一个会话。
- **启动清理**：`FileUtils.cleanPlayerCache()` 增加删除 `<外部缓存目录>/hlscache/`；`App.onCreate()` 在它之后设置预缓存的缓存根目录。
- **重构**：把 `PlayFragment` 和 `PlayActivity` 里重复的「要不要拉取 m3u8、找子列表、去广告与预缓存怎么组合、最终交给播放器什么」抽成一个共享的纯函数类，先用 characterization test 锁住现有行为再改。

## Non-goals

- 不实现预缓存核心（m3u8 改写规则、下载调度、磁盘上限、会话复用），属于 S1（#2）；本 change 只调用它的 public API，不改 `precache` 包。
- 不注册分片代理路由，不做 loopback 校验，不改 `RemoteServer`，属于 S2（#3）。
- 不预缓存下一集，不做直链 mp4/mkv 的分块预取，线程数、窗口、上限等参数不提供设置项，不显示预缓存进度或网速。
- 不让外部播放器、直播、存储空间（`tvbox-drive://`）、迅雷、荐片、分段 mp4 使用预缓存。
- 不改变「广告过滤」本身的净化规则，也不改变开关关闭时的任何现有行为。
- 不处理两个播放页同时存在时争用唯一会话的问题（见 design.md Risks）。
- 不修复 `RemoteServer.m3u8Content` 是全局静态字段这一既有问题。

## Capabilities

### New Capabilities

（无）

### Modified Capabilities

- `vod-player`：新增「预缓存的作用范围」，规定哪些播放走预缓存、改写结果怎么交给播放器、外部播放器和特殊链接不走预缓存、会话何时结束。
- `parse-sniff-adblock`：修改「M3U8 去广告」，补充开启预缓存时 `/m3u8` 里的分片指向分片代理，以及去广告关闭或净化失败时预缓存仍然生效。
- `settings-data`：修改「播放器设置项」，设置表新增「预缓存」（默认关闭），「默认值」场景加上预缓存关闭。

## Impact

- **代码**（路径相对 `app/src/main/java/com/github/tvbox/osc/`）：
  - 新增 `util/M3u8PlayPlanner.java`（纯函数类）和对应单测。
  - 修改 `ui/fragment/PlayFragment.java`、`ui/activity/PlayActivity.java`：`playUrl`、`startPlayUrl`、特殊链接的调用点、`onDestroyView` / `onDestroy`，以及 PlayActivity 画中画窗口关闭的分支。
  - 修改 `util/HawkConfig.java`、`ui/fragment/ModelSettingFragment.java`、`util/FileUtils.java`、`base/App.java`。
  - 修改资源：`res/layout/fragment_model.xml`、`res/layout-v21/fragment_model.xml`、`res/values/strings.xml`、`res/values-zh/strings.xml`。
- **依赖**：编译依赖 S1 的 `com.github.tvbox.osc.precache.HlsPrecache`（只用 `CACHE_DIR_NAME`、`get()`、`setCacheRoot`，以及 dev-test 返工时新增的带 owner 的 `rewrite(..., Object owner)` / `endSession(Object owner)`，播放页只结束自己建的会话）。S1 的接口骨架会先提交到 change 分支上。运行时没有新的第三方依赖。
- **配置与数据**：新增 Hawk 键 `hls_precache`（默认 false）；新增磁盘目录 `<外部缓存目录>/hlscache/`，每次 App 启动时清空。
- **owner-only 文件**：不需要改 `AndroidManifest.xml` 和任何 gradle 文件。
