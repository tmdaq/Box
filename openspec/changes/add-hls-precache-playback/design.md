## Context

动机见 proposal.md 的 Why；方案选型见 `docs/adr/0001-hls-precache-via-local-segment-proxy.md`；术语见 `GLOSSARY.md`；行为要求见本 change 的 `specs/`。预缓存核心的接口以 `openspec/changes/add-hls-precache-core/design.md`「跨 change 接口」一节为准，本 change 只用其中 `HlsPrecache` 的 `CACHE_DIR_NAME`、`get()`、`setCacheRoot`、`rewrite`、`endSession`。

下文代码路径默认相对 `app/src/main/java/com/github/tvbox/osc/`，资源路径相对 `app/src/main/`。

和设计相关的现状（已逐行核实）：

- **两份重复的 `playUrl`**：`ui/fragment/PlayFragment.java` 的 `playUrl` :683-798、`startPlayUrl` :800-866，和 `ui/activity/PlayActivity.java` 的 `playUrl` :700-813、`startPlayUrl` :815-881，除了 `requireActivity()` / `PlayActivity.this` 和 `isAdded()` 检查之外逐字相同（已用 diff 核对）。
  - :684 `VIDEO_PURIFY` 关闭时直接 `startPlayUrl(url)`。
  - :688 地址既不含 `://127.0.0.1/` 也不含 `.m3u8` 时直接播放。
  - :703 OkGo 拉取（tag `m3u8-1`），回调 `onSuccess` 在主线程。
  - :710 内容不以 `#EXTM3U` 开头时播原地址。
  - :714-742 只看前 10 行，判断是不是只有一个子列表的 master，算出 `forwardurl`。
  - :744-753 不跟随：`RemoteServer.m3u8Content = M3U8.purify(目录, content)`，null 时播 `url`，否则播 `http://127.0.0.1:{serverPort}/m3u8`。
  - :755-785 跟随一次（tag `m3u8-2`）：同样的 purify 逻辑，null 时播 `finalforwardurl`。
  - 任一拉取失败都回退 `startPlayUrl(url)`（:782、:795）。
- **外部播放器**：`startPlayUrl` 里读 `mVodPlayerCfg.getInt("pl")`，`>= 10` 时交给 MX / Reex / Kodi，并且 `return`（:812-840）。现在外部播放器拿到的就是 `playUrl` 算出的地址，去广告成功时是本地 `/m3u8`。
- **经过 `playUrl` 的特殊链接**（需求要求排除）：
  - PlayFragment：荐片 :1317、:1319；迅雷回调 :1339。
  - PlayActivity：`tvbox-drive://` :1440；荐片 :1447、:1449；迅雷回调 :1469。
  - 这几处调用的地址无法从 URL 上和普通点播区分（存储空间去掉前缀后就是普通 http 地址，荐片是 `http://<局域网 IP>:<端口>/...`），所以只能在调用点上标记。
- **不经过 `playUrl` 的分支**：分段 mp4 `yxdm`（PlayFragment :659-681 / PlayActivity :676-698）和它的上下段切换（:286/:303、:299/:316）直接调 `startPlayUrl`；直播在 `LivePlayActivity`，完全不经过这两个类。
- **切集、重播、切内核重试**：`playNext` / `playPrevious` / 控制栏 `replay` / `autoRetry`（先 `switchPlayer()` 再 `play(false)`）都走 `play()`，`play()` 先 `release()` 再经由解析、嗅探或 `sourceViewModel.getPlay` 回到 `playUrl`；`autoRetryFromLoadFoundVideoUrls` 直接调 `playUrl`。
- **生命周期**：
  - PlayFragment 的 `onDestroyView` :1160-1178 释放播放器；`onPause` / `onHiddenChanged` 只暂停。DetailActivity 的后台播放（`BACKGROUND_PLAY_TYPE == 1`）和画中画（`== 2`）都不销毁 Fragment；画中画关闭时只暂停（DetailActivity :1215）。
  - PlayActivity 的 `onDestroy` :1289-1306 释放播放器；`onPause` 只暂停；`onPictureInPictureModeChanged(false)` 在 `onStopCalled` 为 true（用户关掉了画中画小窗）时 `mVideoView.release()`（:1278-1283），此时 Activity 不一定立即销毁。
- **设置页**：`VIDEO_PURIFY` 的写法是 `HawkConfig.java:41` 常量 + `ModelSettingFragment.java` :79 字段、:127-128 初始化文字、:499-503 点击切换；文字「开启 / 关闭」在代码里写死。两份 `fragment_model.xml` 里 `llVideoPurify` 独占「播放器」列（右列）的最后一行，外面包着一个本来就为两格设计的横向 `LinearLayout`。整份布局没有任何 `nextFocus*` 属性，焦点全靠系统默认的就近查找。
- **语言目录**：只有 `res/values/strings.xml`（英文）和 `res/values-zh/strings.xml`（中文）；`normal`、`python` source set 下没有 `strings.xml`。
- **启动清理**：`App.onCreate()` :101 在主线程调 `FileUtils.cleanPlayerCache()`（:321-325），递归删除 `thunder`、`ijkcaches`、`jpali/Downloads`。`FileUtils.getExternalCachePath()` 依赖 `App.getInstance()`，纯 JUnit 下拿不到。

## Goals / Non-Goals

**Goals:**

- 开关关闭（默认）时，任何可观察行为都和现在一致（A14）。
- 两个播放入口共用一份「要不要拉 m3u8、交给播放器什么」的判断，用纯 JUnit 覆盖。
- 每次开始播放，要么调用一次 `rewrite`，要么结束上一个会话，不留下没人用的会话。

**Non-Goals:**

- 不合并 PlayFragment 和 PlayActivity 的其余重复代码（`startPlayUrl` 主体、OkGo 回调结构、解析、嗅探）。OkGo 回调留在原处，只把判断搬出去。
- 不给「开启 / 关闭」文字做多语言（沿用「广告过滤」写死中文的现状）。
- 不改 `precache` 包、`RemoteServer.java`、owner-only 文件。

## Decisions

### D1 纯函数类 `util/M3u8PlayPlanner`

放在 `util/`，和 `M3U8.java` 同包，测试放在 `app/src/test/java/com/github/tvbox/osc/util/M3u8PlayPlannerTest.java`。不放进 `precache` 包（归 S1），也不放 `player/`（那里是内核实现）。

```java
package com.github.tvbox.osc.util;

/** playUrl 的判断：要不要先拉 m3u8、找子列表、去广告与预缓存怎么组合、最终交给播放器什么。PlayFragment 和 PlayActivity 共用。 */
public final class M3u8PlayPlanner {

    /** 对应 M3U8.purify，调用方传 M3U8::purify。 */
    public interface Purifier { String purify(String tsUrlPre, String content); }

    /** 对应 HlsPrecache.rewrite，调用方传 HlsPrecache.get()::rewrite。 */
    public interface Rewriter { String rewrite(String playlistUrl, String content, Map<String, String> headers, int port); }

    /**
     * 拉取前：是否要先下载 m3u8。
     * @param purify   广告过滤开关
     * @param precache 本次播放可以走预缓存：预缓存开关打开、不是外部播放器、调用点允许（见 D3）
     * @return (purify || precache) && (url.contains("://127.0.0.1/") || url.contains(".m3u8"))
     */
    public static boolean shouldFetch(String url, boolean purify, boolean precache);

    /** 第一层内容是不是 m3u8：content.startsWith("#EXTM3U")。content 为 null 时抛 NPE，和现状一致。 */
    public static boolean isPlaylist(String content);

    /** 逐字搬自 PlayFragment :714-742：只看前 10 行，只有一个子列表时返回它的绝对地址，否则返回 ""。 */
    public static String findForwardUrl(String url, String content);

    /**
     * 拿到最终要播的那份 m3u8 后，决定交给播放器什么。不做 IO（purifier / rewriter 由调用方注入）。
     * @param playlistUrl 这份 m3u8 的真实地址：没跟随时是 url，跟随过时是 finalforwardurl
     * @param port        RemoteServer.serverPort
     */
    public static Plan plan(String playlistUrl, String content, Map<String, String> headers,
                            boolean purify, boolean precache, int port,
                            Purifier purifier, Rewriter rewriter);

    public static final class Plan {
        /** 交给 startPlayUrl 的地址。 */
        public final String playUrl;
        /** 是否写 RemoteServer.m3u8Content。 */
        public final boolean setM3u8Content;
        /** 要写入的内容；可以是 null（沿用现状：purify 返回 null 时也写 null）。 */
        public final String m3u8Content;
        /** true 表示 m3u8Content 是 rewrite 的结果，startPlayUrl 时保留预缓存会话（见 D5）。 */
        public final boolean precached;
    }
}
```

`plan` 的规则（`local = "http://127.0.0.1:" + port + "/m3u8"`，`base = playlistUrl.substring(0, playlistUrl.lastIndexOf('/') + 1)`）：

```
purified = purify ? purifier.purify(base, content) : null
if (precache):
    rewritten = rewriter.rewrite(playlistUrl, purified != null ? purified : content, headers, port)
    if (rewritten != null) return Plan(local, set=true, rewritten, precached=true)
if (purify) return Plan(purified != null ? local : playlistUrl, set=true, purified, precached=false)   // 现状
return Plan(playlistUrl, set=false, null, precached=false)
```

- `precache == false` 时 `rewriter` 一次都不调用；`purify == false` 时 `purifier` 一次都不调用。
- `headers` 原样传给 `rewriter`（可能为 null），不复制，S1 会自己复制。
- 不捕获 `purifier` 的异常，和现状一致（现在异常也是从 `onSuccess` 里抛出去）。`rewriter` 按 S1 的约定不抛异常。

**为什么注入 `Purifier` / `Rewriter`，而不是直接调 `M3U8.purify` 和 `HlsPrecache.get()`**：测试可以断言「传给改写的是净化结果还是原文」「关闭时没有调用改写」，而且本类的测试不依赖 S1 的实现进度。接口自己定义而不用 `java.util.function`，因为 minSdk 21 没开 desugaring。

**为什么 `precache` 由调用方算好再传入**：外部播放器的判断要读 `mVodPlayerCfg`，特殊链接要看调用点，Hawk 也不适合在纯 JUnit 里读。纯函数只管组合。

**先锁行为再改**：仓库规则要求先写 characterization test。现在的逻辑嵌在 OkGo 回调里，没法直接测，所以分两步：

1. 把 :684-:688 的前置判断、:710 的 `#EXTM3U` 判断、:714-742 的子列表查找、:744-753 / :764-770 的 purify 与地址选择逐字搬进本类（`precache` 分支先不写，传 true 也按 false 处理），用 `M3u8PlayPlannerTest` 锁住现状。这一步还不改两个播放入口。
2. 再用 TDD 加 `precache` 分支。

两个入口改成调用本类时，`precache` 在开关关闭时恒为 false，第 1 步锁住的行为就是关闭时的行为。

### D2 两个入口的接入点

PlayFragment 和 PlayActivity 做完全相同的改动，下面以 PlayFragment 为例，行号是 PlayFragment 的（PlayActivity 对应行号见 Context）。

```java
void playUrl(String url, HashMap<String, String> headers) {
    playUrl(url, headers, true);
}

/** precacheAllowed：调用点是否允许走预缓存；特殊链接传 false（D3）。 */
private void playUrl(String url, HashMap<String, String> headers, boolean precacheAllowed) {
    final boolean purify = Hawk.get(HawkConfig.VIDEO_PURIFY, true);
    final boolean precache = precacheAllowed
            && Hawk.get(HawkConfig.HLS_PRECACHE, false)
            && !isExternalPlayer();
    if (!M3u8PlayPlanner.shouldFetch(url, purify, precache)) {   // 替换 :684-691
        startPlayUrl(url, headers);
        return;
    }
    // cancelTag、HttpHeaders 组装、两层 OkGo 请求结构、onError 回退保持不变
    //   第一层 onSuccess：
    //     if (!M3u8PlayPlanner.isPlaylist(content)) { startPlayUrl(url, headers); return; }
    //     String forwardurl = M3u8PlayPlanner.findForwardUrl(url, content);       // 替换 :714-742
    //     if ("".equals(forwardurl)) { playPlan(plan(url, content, ...), headers); return; }
    //   第二层 onSuccess：
    //     playPlan(plan(finalforwardurl, content, ...), headers);                 // 替换 :763-770
}

private M3u8PlayPlanner.Plan plan(String playlistUrl, String content, HashMap<String, String> headers,
                                  boolean purify, boolean precache) {
    return M3u8PlayPlanner.plan(playlistUrl, content, headers, purify, precache,
            RemoteServer.serverPort, M3U8::purify, HlsPrecache.get()::rewrite);
}

private void playPlan(M3u8PlayPlanner.Plan p, HashMap<String, String> headers) {
    if (p.setM3u8Content) RemoteServer.m3u8Content = p.m3u8Content;
    startPlayUrl(p.playUrl, headers, p.precached);
}

/** 和 startPlayUrl 判断外部播放器的条件一致；读不到时按内置播放器处理，和 startPlayUrl 捕获 JSONException 后继续用内置播放器一致。 */
private boolean isExternalPlayer() {
    return mVodPlayerCfg != null && mVodPlayerCfg.optInt("pl", 0) >= 10;
}
```

- `purify` 和 `precache` 在 `playUrl` 入口读一次，回调里用同一份值，避免拉取期间设置变化导致前后判断不一致。
- `HlsPrecache.get()::rewrite` 在主线程的 OkGo 回调里调用，S1 保证它不做网络 IO 和阻塞磁盘 IO。`M3U8.purify` 本来就在这里调用。
- 方法引用不捕获 `Context`、Fragment 或 Activity。
- `playUrl(url, headers)` 的签名不变，现有的十几个调用点（解析 :1516/:1578/:1706、嗅探 :2028/:2215、`autoRetryFromLoadFoundVideoUrls` :1272、播放结果 :1031）都不用改，默认允许预缓存。

### D3 特殊链接的排除

在调用点上把 `precacheAllowed` 传 false：

| 文件 | 行 | 链接 |
| --- | --- | --- |
| PlayFragment | :1317、:1319 | 荐片 `playUrl(Jianpian.JPUrlDec(...), null)` |
| PlayFragment | :1339 | 迅雷回调 `play(String url)` |
| PlayActivity | :1440 | `tvbox-drive://` |
| PlayActivity | :1447、:1449 | 荐片 |
| PlayActivity | :1469 | 迅雷回调 |

- 这几处在开关关闭时行为不变：`precache` 为 false，`shouldFetch` 退化为现在的判断。
- 分段 mp4（yxdm）和它的上下段切换不经过 `playUrl`，直接 `startPlayUrl`，按 D5 会结束上一个会话，不需要额外处理。
- 直播在 `LivePlayActivity`，不经过这两个类，不需要改。

**为什么不按 URL 识别**：存储空间的地址去掉 `tvbox-drive://` 之后就是普通 http 地址，荐片是局域网 IP 上的本地地址，迅雷是本地文件服务地址，从 URL 上分辨不可靠。调用点最清楚自己是什么链接。

### D4 去广告关闭时的新拉取路径

现在 :684 在去广告关闭时直接播原地址。改成 `shouldFetch(url, purify, precache)` 之后：

- 去广告关闭、预缓存开（并且本次允许）、地址含 `.m3u8` 或 `://127.0.0.1/` → 走和去广告完全相同的 OkGo 两层拉取（同样的 tag、同样的请求头、同样跟随一次子列表），只是 `plan` 里不调 purify，直接把原文交给 `rewrite`。
- `rewrite` 返回 null（master、BYTERANGE、无 ENDLIST 等）→ `Plan(playlistUrl, set=false)`，播原地址（跟随过时是子列表地址）。不写 `m3u8Content`，和去广告关闭时的现状一致。
- 拉取失败、内容不以 `#EXTM3U` 开头 → 播最初的 `url`，和现有回退一致。
- 地址既不含 `.m3u8` 也不含 `://127.0.0.1/` → 不拉取，直接播放（:688 的现状）。

代价是开关打开、去广告关闭时，起播前多一次 m3u8 请求（OkGo 全局 client，10s 超时，失败回退原地址）。这正是去广告开启时已有的开销。

### D5 外部播放器与会话结束：`startPlayUrl` 默认结束会话

```java
void startPlayUrl(String url, HashMap<String, String> headers) {
    startPlayUrl(url, headers, false);
}

private void startPlayUrl(String url, HashMap<String, String> headers, boolean keepPrecacheSession) {
    if (!keepPrecacheSession) HlsPrecache.get().endSession();
    // 以下是原 startPlayUrl 的主体，逐字不变（包括 isAdded 检查、runOnUiThread、外部播放器分支）
}
```

- **外部播放器**：`isExternalPlayer()` 为 true 时 `precache` 为 false，`shouldFetch` 和 `plan` 退化成现状，外部播放器拿到的地址和请求头和现在完全一致（去广告成功时仍是本地 `/m3u8`，内容是净化结果，分片指向源站）。
- **外部播放器要不要 `endSession`：要。** 选外部播放器时 App 退到后台，预缓存会话的生命周期管不住（ADR 0001），而之前用内置播放器播过的会话已经没人用了。所以走 `startPlayUrl` 的默认路径结束它。
- **其余不改写的播放也结束会话**：开关关闭、特殊链接、普通直链、`rewrite` 返回 null（S1 已经结束了旧会话，这里重复调用无副作用）、拉取失败回退、yxdm 分段 mp4。这样「每次开始播放，要么改写，要么结束上一个会话」只在一个地方保证，不用逐个分支去补。
- **只有 `plan` 返回 `precached == true` 时保留会话**，因为 `rewrite` 刚刚建立了新会话。
- **开关关闭时也调用 `endSession`**：没有会话时它是纯内存的空操作（S1：不阻塞、不做 IO、可重复调用），不改变任何可观察行为；好处是播放中途关掉开关（后台播放时去设置页）之后，下一次播放会清掉残留的会话。
- **为什么不在 `play()` 开头结束会话**：重播、切内核重试会用同一个 m3u8 地址重新 `rewrite`，S1 靠「上一个会话还在」来复用已下载的分片。在 `play()` 开头结束会让复用永远不发生。

**切集、重播、切内核重试逐条核对**：

| 路径 | 经过 | 结果 |
| --- | --- | --- |
| `playNext` / `playPrevious` → `play(false)` | 解析 / 嗅探 / 播放结果 → `playUrl` | 适用时 `rewrite`（地址不同，S1 清空旧会话）；不适用时 `startPlayUrl` 结束会话 |
| 控制栏 `replay` → `play(replay)` | 同上 | 同一地址 → S1 复用 |
| `autoRetry` → `switchPlayer()` + `play(false)` | 同上 | 同一地址 → S1 复用；从外部播放器切到 IJK 后开始预缓存 |
| `autoRetryFromLoadFoundVideoUrls` | 直接 `playUrl` | 同上 |
| 荐片、迅雷、存储空间 | `playUrl(..., false)` | `startPlayUrl` 结束会话 |
| yxdm 及其上下段 | 直接 `startPlayUrl` | 结束会话 |
| 画中画按钮上一集 / 下一集 | `playPrevious` / `playNext` | 同第一行 |

### D6 离开播放页

| 调用点 | 位置 | 说明 |
| --- | --- | --- |
| `PlayFragment.onDestroyView` | 在 `mVideoView.release()` 之后加 `HlsPrecache.get().endSession()` | 离开详情页（返回、被系统回收） |
| `PlayActivity.onDestroy` | 在 `mVideoView.release()` 之后加同样一行 | 离开全屏播放页 |
| `PlayActivity.onPictureInPictureModeChanged` 的 `else` 分支 | 在 `if (onStopCalled) { mVideoView.release(); }` 里，release 之后加同样一行 | 用户关掉画中画小窗，播放器已经释放；Activity 不一定马上销毁，不在这里结束的话，会话会一直留到 Activity 销毁 |

不加的地方：`onPause`、`onStop`、`onHiddenChanged`、进入画中画、DetailActivity 的小窗与全屏切换、后台播放服务。这些都只是暂停或继续播放，不算离开。DetailActivity 关闭画中画时只暂停不释放（:1215），所以也不结束。

### D7 设置开关

**Hawk 键**：`HawkConfig` 在 `VIDEO_PURIFY` 下面加

```java
public static final String HLS_PRECACHE = "hls_precache";   // 预缓存，默认 false
```

所有读取都写成 `Hawk.get(HawkConfig.HLS_PRECACHE, false)`。不在 `App.initParams` 里 `putDefault`，和 `VIDEO_PURIFY` 一样靠读取时的默认值。老用户没有这个键，读到 false，不需要迁移。

**ModelSettingFragment**，照 `VIDEO_PURIFY` 的三处写法：

- 字段：`private TextView tvHlsPrecacheText;`
- 初始化（紧跟 :127-128）：`findViewById(R.id.tvHlsPrecacheText)`，文字为 `Hawk.get(HLS_PRECACHE, false) ? "开启" : "关闭"`。
- 点击（紧跟 :499-503）：`findViewById(R.id.llHlsPrecache).setOnClickListener(v -> { FastClickCheckUtil.check(v); Hawk.put(HLS_PRECACHE, !Hawk.get(HLS_PRECACHE, false)); 更新文字; })`。

**布局**（两份 `fragment_model.xml` 都改，改完这一段两份内容一致）：

- 把新控件 `llHlsPrecache` 放进 `llVideoPurify` 外面那个横向 `LinearLayout`，排在 `llVideoPurify` 后面。两格各占右列一半，和上面 `llPlay | llMediaSetting`、`llScale | llBackgroundPlay` 两行的排法一致。
- 外层容器：`marginStart` / `marginLeft` 和 `marginEnd` / `marginRight` 都是 `@dimen/vs_5`（`layout-v21` 已经是这样，`layout` 补上 End/Right）。
- `llVideoPurify`：`marginEnd` / `marginRight` 为 `@dimen/vs_5`（`layout` 已经是这样，`layout-v21` 补上）。
- `llHlsPrecache`：属性照抄 `llMediaSetting`（`layout_width=@dimen/vs_0`、`layout_weight=1`、`marginStart` / `marginLeft=@dimen/vs_5`、`background=@drawable/shape_setting_model_focus`、`focusable=true`、`gravity=center_vertical`、左右 padding `vs_20`）。四个子 TextView 照抄 `llVideoPurify`：标题 `@string/mn_hls_precache`、占位、状态 `@+id/tvHlsPrecacheText`、`>`。

**焦点链**：整份布局没有 `nextFocus*` 属性，焦点靠系统按几何位置就近查找，新控件也不加 `nextFocus*`。放好之后的相邻关系：

| 从 | 按键 | 到 |
| --- | --- | --- |
| `llVideoPurify` | 右 | `llHlsPrecache` |
| `llHlsPrecache` | 左 | `llVideoPurify` |
| `llMediaSetting`（正上方） | 下 | `llHlsPrecache` |
| `llHlsPrecache` | 上 | `llMediaSetting` |
| `llHlsPrecache` | 下 | `llWpRecovery`（「系统」组右列第二格；中间的 `llSystemSettings` 标题行 `focusable=false`） |
| `llWpRecovery` | 上 | `llHlsPrecache`（原来到 `llVideoPurify`，因为它占满了右列） |
| `llHomeLive`（同一行左列） | 右 | `llVideoPurify`（不变） |

设置页嵌在 `ScrollView` 里，焦点移到屏幕外的控件时会自动滚动。手动验证第 1 项逐条检查上表。如果实测某个方向落点不对，再给相关控件补 `nextFocus*`，两份布局一起改。

### D8 strings.xml

只有两个语言目录，都加在 `mn_purify` 后面：

| 文件 | 内容 |
| --- | --- |
| `res/values/strings.xml` | `<string name="mn_hls_precache">Precache</string>` |
| `res/values-zh/strings.xml` | `<string name="mn_hls_precache">预缓存</string>` |

「开启 / 关闭」状态文字沿用 `VIDEO_PURIFY` 的写法，在代码里写死，不新增字符串。`strings.xml` 是 union-merge 文件，并行开发时追加不会冲突。

### D9 `App.java` 与 `FileUtils`

**FileUtils**：为了能测试，把清理逻辑挪进一个接收目录的包内重载，原方法只负责取目录：

```java
public static void cleanPlayerCache() {
    cleanPlayerCache(getCachePath(), getExternalCachePath());
}

/** 包内可见，供测试传入临时目录。 */
static void cleanPlayerCache(String cachePath, String externalCachePath) {
    recursiveDelete(new File(cachePath + File.separator + "thunder"));
    recursiveDelete(new File(externalCachePath + File.separator + "ijkcaches"));
    recursiveDelete(new File(externalCachePath + File.separator + "jpali" + File.separator + "Downloads"));
    recursiveDelete(new File(externalCachePath + File.separator + HlsPrecache.CACHE_DIR_NAME));
}
```

**App.onCreate()**：紧跟 :101 `FileUtils.cleanPlayerCache();` 之后，按 S1 的约定加

```java
HlsPrecache.get().setCacheRoot(new File(FileUtils.getExternalCachePath(), HlsPrecache.CACHE_DIR_NAME));
```

- `setCacheRoot` 只记路径，不做 IO，不管开关是否打开都调用，这样运行中打开开关也能立即生效。
- 必须在 `cleanPlayerCache()` 之后、同一线程同步执行。如果把删除 `hlscache/` 挪到后台线程，可能会删掉刚开始的新会话的目录。
- 删除 `hlscache/` 是在主线程做磁盘 IO，和现有的三项清理一样。上一次进程最多留下一个会话目录（至多 300MB，约一两百个文件），删除耗时和现有的 `ijkcaches` 清理同量级。这是沿用现有清理时机的取舍，见 Risks。
- `App` 只把 `File` 交给单例，不把 `Context` 交给它。

### D10 测试策略

| 测试 | 类型 | 覆盖 |
| --- | --- | --- |
| `util/M3u8PlayPlannerTest` | 纯 JUnit | 第 1 步（characterization）：`shouldFetch` 在 `precache=false` 时的真值表（去广告开 / 关 × 地址含 `.m3u8` / 含 `://127.0.0.1/` / 都不含 / 带端口的 `127.0.0.1:9978/...`）；`isPlaylist`；`findForwardUrl` 的媒体列表、单个绝对 / 根相对 / 相对子列表、`.m3u8?` 带参数、`\r\n` 换行、唯一的非空行不是 m3u8；`plan` 在 `precache=false` 时 purify 成功 / 返回 null 两种结果，以及传给 purifier 的目录前缀。第 2 步（TDD）：`precache=true` 下净化结果 / 原文分别交给 rewriter、rewriter 返回非 null 和 null 的各种组合、`precache=false` 时 rewriter 不被调用、`purify=false` 时 purifier 不被调用、headers 原样传递、port 拼进本地地址 |
| `util/FileUtilsCleanPlayerCacheTest` | 纯 JUnit + `TemporaryFolder` | `cleanPlayerCache(String, String)` 删除 `thunder`、`ijkcaches`、`jpali/Downloads`、`hlscache`（含子目录和文件），保留 `jpali` 下的其他内容和外部缓存目录里的其他文件；目录不存在时不报错 |
| 两个入口、设置页、`App` | 手动 | 见下面「手动验证」 |

测试替身用匿名类实现 `Purifier` / `Rewriter`，记录收到的参数；「不应被调用」的替身在被调用时直接 `fail()`。

### D11 owner-only 文件

不需要改 `AndroidManifest.xml`、`build.gradle`、`app/build.gradle` 或其他 owner-only 文件：没有新权限、新组件和新依赖（测试只用 JUnit 4 和 `TemporaryFolder`）。

## 手动验证

对应 #1 拷问纪要「手动验证」的 9 项。准备：

- 安装 `Arm64GenericNormalDebug` 包（包名 `com.github.tvbox.osc.tk`），`adb` 连上设备。
- 缓存目录：`/sdcard/Android/data/com.github.tvbox.osc.tk/cache/hlscache/`。下文的「看目录」指执行 `adb shell du -sk <目录>` 和 `adb shell ls <目录>`。
- 准备片源：一个普通 HLS 点播（单码率，带 `#EXT-X-ENDLIST`，至少 20 分钟），一个 `METHOD=AES-128` 加密的 HLS 点播，一个多集剧集。
- 「设置 → 后台」先设为「关闭」，「设置 → 广告过滤」保持默认开启，除第 1 项外「设置 → 预缓存」都设为开启。

1. **设置页开关**
   1. 首次安装（或清除数据）后打开设置页：「预缓存」显示「关闭」。
   2. 只用遥控器：从「广告过滤」按右，焦点落到「预缓存」；按左回到「广告过滤」。从「播放器设置」按下，落到「预缓存」；从「预缓存」按上，回到「播放器设置」。从「预缓存」按下，落到「系统」组右列第二格（壁纸重置）；从壁纸重置按上，回到「预缓存」。焦点框完整可见，页面能随焦点滚动。
   3. 焦点在「预缓存」上按确认：显示变为「开启」，再按一次变回「关闭」。
   4. 用触屏（或鼠标）点击「预缓存」：同样切换。
   5. 设为「开启」，退出设置页再进入：仍是「开启」。杀掉进程重启 App 再进入：仍是「开启」。
   6. 切换到英文界面：标题显示 `Precache`，状态文字和「广告过滤」一样显示中文「开启 / 关闭」。
   7. 两种布局：在 Android 5.0 以上设备（用 `layout-v21`）检查以上各步；如有 Android 5.0 以下环境可跳过（minSdk 21，`layout` 版本实际不会被使用，只核对两份 XML 这一段内容一致）。
2. **四个内核分别播放**：在「设置 → 播放器」依次选系统、IJK、Exo、阿里，每次从全屏播放页播放普通 HLS 片源。
   - 都能正常起播，拖动进度条后能继续播放。
   - 播放 30 秒后看目录：出现一个 `s<id>` 子目录，里面有若干 `.seg` 文件。
   - 再把「预缓存」关掉，重新播放同一片源：能正常播放，开始播放后几秒内旧的会话目录被删除，也不再出现新的会话目录。
3. **AES-128 加密片源**：开启预缓存，分别用 IJK 和 Exo 播放加密片源。
   - 画面和声音正常，没有花屏，拖动后也正常。
   - 用 `adb pull` 拉一个 `.seg` 文件，和用 `curl -H` 带同样请求头直接从源站下载的同一分片比较 `md5`，两者一致（说明磁盘上是原样的密文）。
4. **暂停时缓存目录先变大后停止增长**：播放普通片源到第 2 分钟后暂停。
   - 每 5 秒看一次目录大小：先持续变大，然后停止增长（窗口约 120 秒的分片下完）。
   - 停止增长后再等 30 秒，大小不变。继续播放后，大小随播放进度继续变化。
5. **跳转**：播放到第 2 分钟，把进度条拖到第 15 分钟。
   - 拖动后能在正常时间内起播（与关闭预缓存时相比没有明显变慢）。
   - 等 30 秒后看目录：第 2 分钟附近的分片已删除，`.seg` 文件对应的是第 15 分钟往后约 2 分钟的内容（文件数大致等于 150 秒 ÷ 分片时长）。
   - 再拖回第 14 分钟附近（落在回看 30 秒内）：能立即起播。
6. **切集后清空上一集的缓存**：播放多集剧集的第 1 集 1 分钟，记下会话目录名。
   - 按「下一集」：几秒内旧的 `s<id>` 目录被删除，出现新的会话目录。
   - 在同一集上按「重播」：会话目录名不变（复用），已下载的 `.seg` 文件仍在。
   - 返回退出播放页：目录在几秒内被清空。杀进程后重启 App：`hlscache/` 不存在或为空。
7. **限速环境下对比卡顿**：用路由器限速或电脑热点加 Network Link Conditioner，把带宽限到略高于片源码率（比如码率的 1.2 倍）。
   - 预缓存关闭时播放 10 分钟，记录卡顿（出现缓冲转圈）的次数和总时长。
   - 预缓存开启时播放同一片源同一段 10 分钟，记录同样的数据。
   - 开启后卡顿次数和时长应明显减少；把两组数据附在交付说明里。
8. **详情页小窗和外部播放器**
   - 「设置 → 窗口预览」开启，进入详情页：小窗自动播放，看目录出现会话目录。把小窗切到全屏，再切回小窗：会话目录名不变。按返回离开详情页：目录被清空。
   - 「设置 → 后台」设为「开启」，在小窗播放时按 Home 键：继续播放，会话目录仍在；回到 App 继续播放，目录名不变。
   - 「设置 → 后台」设为「画中画」，在全屏播放页按 Home 键：进入画中画，会话目录仍在，在画中画里按下一集会换成新的会话目录；关闭画中画小窗后，目录被清空。
   - 安装 MX Player，把播放器选为 MX，开启预缓存后播放同一片源：MX 能正常播放；在 MX Player 的「属性 / 信息」里看到的播放地址和关闭预缓存时完全相同（去广告成功时是 `http://127.0.0.1:<端口>/m3u8`；在设备上用 `curl` 取这个地址，分片指向源站，不含 `/hlsseg`）；看目录，没有会话目录，之前内置播放器留下的会话目录也被删除。
   - 存储空间（WebDAV / Alist）里播放一个 m3u8 文件、播放一个迅雷磁力视频：都能正常播放，不出现会话目录。
9. **局域网请求返回 403**（分片代理端点属于 S2，需要 S1、S2、S3 合并后在集成分支上验证）
   - 播放普通片源时，在设备上执行 `adb shell "curl -s http://127.0.0.1:<端口>/m3u8 | head -20"`（端口从 9978 起，被占用时递增；设置页「配置地址」弹窗里的远程控制二维码地址带有实际端口），拿到一个 `/hlsseg?s=...&i=...` 地址。
   - 在设备上 `adb shell curl -s -o /dev/null -w '%{http_code}' '<该地址>'`：返回 200。
   - 在同一局域网的电脑上，把地址里的 `127.0.0.1` 换成电视的局域网 IP 再请求：返回 403。
   - 换集后再用旧地址在设备上请求：返回 410。

## Risks / Trade-offs

- **[风险] 两个播放页同时存在时争用唯一的会话。** 比如详情页 A 的小窗暂停后又打开了另一个播放页 B：B 开始播放会替换 A 的会话，B 关闭时结束会话。回到 A 继续播放，播放器请求旧会话的分片拿到 410，按「出错自动重试」切内核后重新改写，表现为一次短暂的重新加载。→ 这是 S1「同一时间只有一个会话」的直接后果，场景少见，第一期接受，写入 Non-goals；手动验证第 8 项会观察切换时的表现。
- **[风险] 开关打开、去广告关闭时，起播多一次 m3u8 请求。** → 和去广告开启时现有的开销相同；失败回退原地址。
- **[风险] 启动时在主线程删除 `hlscache/`。** → 沿用 `cleanPlayerCache()` 现有的时机和线程。挪到后台会和新会话目录产生竞争（D9）。正常退出时会话目录已经在后台删掉，只有进程被杀时才会留下，最多一个会话（300MB）。
- **[取舍] `startPlayUrl` 默认结束会话，只有 `precached` 的播放保留。** 一处兜底覆盖所有不改写的分支，代价是开关关闭时每次播放多一次空操作调用。
- **[取舍] 特殊链接靠调用点传参排除，而不是按 URL 识别。** 以后新增的特殊链接如果走 `playUrl(url, headers)` 的默认重载，会被当作普通点播；但普通点播的判断本身还要求地址含 `.m3u8` 且内容是带 ENDLIST 的媒体列表，误判的后果最多是这一类链接也被预缓存。
- **[取舍] 关闭画中画小窗时结束会话（D6 第三行）。** 需求只点名了 `onDestroy`，这里多加一处，因为此时播放器已经释放，留着会话只会占磁盘。
- **[依赖] 编译依赖 S1 的 `HlsPrecache`。** S1 的接口骨架会先在 change 分支上提交，`M3u8PlayPlanner` 本身不依赖它，可以先做。

## Migration Plan

- 开关默认关闭，合并后对现有用户没有可观察的变化。
- 回滚：把「预缓存」关掉即可回到原行为；需要彻底回滚时直接 revert 本 change。revert 之后启动清理不再删除 `hlscache/`，残留的目录（正常退出时为空）可以用首页点 App 名称的「清除缓存」清掉。
