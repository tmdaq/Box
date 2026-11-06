# 解析、嗅探与去广告

## Purpose
很多站点返回的不是能直接播放的地址，而是网页地址或需要解析的 VIP 链接；本能力通过「解析接口 + WebView 嗅探」把它们转换成真实的视频地址，并负责广告过滤、特殊链接直接播放与安全 DNS。

## Requirements

### Requirement: 播放结果中的解析相关字段
系统 SHALL 从站点（爬虫）播放结果中读取以下字段决定是否解析以及如何播放：

| 字段 | 含义 |
| --- | --- |
| `parse` | 为 `1`（默认）时需要解析 / 嗅探 |
| `jx` | 为 `1` 时强制使用配置里的解析接口 |
| `playUrl` | 站点自带的解析前缀 |
| `url` | 播放地址 |
| `header` | 请求头 |
| `flag` | 线路标识 |
| `subt` / `subs` | 字幕 |
| `danmaku` | 弹幕 |

`parse` 缺省时 MUST 按 `1` 处理。

#### Scenario: 未返回 parse 字段
- **WHEN** 爬虫播放结果中没有 `parse` 字段
- **THEN** 系统按 `parse=1` 处理，需要解析 / 嗅探

### Requirement: 解析路径判断
系统 SHALL 按以下顺序决定播放路径：

1. `parse=0` 且 `jx=0`：直接播放 `playUrl + url`；
2. `jx=1`，或线路 `flag` 在配置的 `flags`（VIP 线路）里且站点没有自带 `playUrl`：使用默认解析接口，播放时可以在控制栏切换其他解析；
3. 否则使用站点 `playUrl`：`json:地址` 按 JSON 解析处理；`parse:解析名` 使用配置里同名的解析接口；其他当作 Web 嗅探地址。

使用默认解析接口时，用户 MUST 能在控制栏切换其他解析。

#### Scenario: 直接播放
- **WHEN** 播放结果 `parse=0` 且 `jx=0`
- **THEN** 系统直接播放 `playUrl + url`

#### Scenario: jx 强制解析
- **WHEN** 播放结果 `jx=1`
- **THEN** 系统使用默认解析接口
- **AND** 播放时可以在控制栏切换其他解析

#### Scenario: VIP 线路且无站点解析前缀
- **WHEN** 线路 `flag` 在配置的 `flags` 中，且站点没有自带 `playUrl`
- **THEN** 系统使用默认解析接口

#### Scenario: 站点 playUrl 为 json 前缀
- **WHEN** 走站点 `playUrl` 且其值形如 `json:地址`
- **THEN** 系统按 JSON 解析处理该地址

#### Scenario: 站点 playUrl 指定解析名
- **WHEN** 走站点 `playUrl` 且其值形如 `parse:解析名`
- **THEN** 系统使用配置里同名的解析接口

#### Scenario: 站点 playUrl 为其他值
- **WHEN** 走站点 `playUrl` 且其值不是 `json:` 或 `parse:` 开头
- **THEN** 系统把它当作 Web 嗅探地址

### Requirement: 解析接口类型
配置中的解析接口 SHALL 按 `parses[].type` 区分类型，系统 MUST 按类型执行对应解析：

| type | 名称 | 说明 |
| --- | --- | --- |
| 0 | Web 嗅探 | 在隐藏的 WebView 中打开「解析地址 + 视频地址」，拦截网络请求找出视频地址 |
| 1 | JSON 解析 | 请求接口，从返回的 `url`（或 `data.url`）取地址，同时读取 `user-agent`、`referer` |
| 2 | JSON 扩展 | 把多个 JSON 解析合在一起交给爬虫的 `jsonExt` 处理 |
| 3 | JSON 聚合 | 调用爬虫的 `jsonExtMix`，同时混合 JSON 解析和 Web 解析 |
| 4 | 超级解析 | 内置聚合解析：并发请求配置里所有 JSON / Web 解析，取最先成功的结果 |

解析接口可以带 `ext` 扩展参数（JSON 对象）。

#### Scenario: Web 嗅探解析
- **WHEN** 使用 type 为 0 的解析接口
- **THEN** 系统在隐藏的 WebView 中打开「解析地址 + 视频地址」并拦截网络请求找出视频地址

#### Scenario: JSON 解析
- **WHEN** 使用 type 为 1 的解析接口
- **THEN** 系统请求接口并从返回的 `url`（或 `data.url`）取得播放地址
- **AND** 同时读取返回中的 `user-agent` 与 `referer`

#### Scenario: JSON 扩展
- **WHEN** 使用 type 为 2 的解析接口
- **THEN** 系统把多个 JSON 解析合在一起交给爬虫的 `jsonExt` 处理

#### Scenario: JSON 聚合
- **WHEN** 使用 type 为 3 的解析接口
- **THEN** 系统调用爬虫的 `jsonExtMix`，同时混合 JSON 解析与 Web 解析

#### Scenario: 超级解析取最先成功结果
- **WHEN** 使用 type 为 4 的解析接口
- **THEN** 系统并发请求配置里所有 JSON / Web 解析
- **AND** 采用最先成功返回的结果

### Requirement: 内置超级解析与默认解析设置
系统 SHALL 内置一个名为「超级解析」的解析项，`设置 → 默认解析` MUST 允许选择默认解析接口。

#### Scenario: 解析列表包含超级解析
- **WHEN** 用户查看可用解析接口
- **THEN** 列表中包含内置的「超级解析」

#### Scenario: 选择默认解析
- **WHEN** 用户在 `设置 → 默认解析` 选择某个解析接口
- **THEN** 需要使用默认解析接口时系统使用该解析

### Requirement: 嗅探 WebView 选择
`设置 → 嗅探Webview` SHALL 提供「系统自带」与 XWalkView（Crosswalk）两个选项；选择 XWalkView 时，第一次使用 MUST 先下载运行组件。

#### Scenario: 首次使用 XWalkView
- **WHEN** 用户选择 XWalkView 并第一次进行嗅探
- **THEN** 系统需要先下载 XWalkView 运行组件

#### Scenario: 使用系统 WebView
- **WHEN** 用户选择「系统自带」
- **THEN** 嗅探使用系统 WebView

### Requirement: 嗅探过程
嗅探时系统 SHALL 拦截页面请求并用规则判断哪个请求是视频地址，找到后 MUST 自动停止嗅探并开始播放；嗅探到的多个候选地址会排队，当前地址播放失败时依次尝试下一个。

#### Scenario: 嗅探到视频地址
- **WHEN** 嗅探中某个请求被判定为视频地址
- **THEN** 系统停止嗅探并开始播放该地址

#### Scenario: 多候选地址依次尝试
- **WHEN** 嗅探得到多个候选地址且当前地址播放失败
- **THEN** 系统依次尝试队列中的下一个地址

### Requirement: 调试模式下的嗅探
打开调试模式后，嗅探 WebView SHALL 显示在前台并 MUST 加载图片，方便排查。

#### Scenario: 调试模式嗅探
- **WHEN** 调试模式开启时进行嗅探
- **THEN** 嗅探 WebView 显示在前台
- **AND** 页面图片正常加载

### Requirement: 嗅探判断顺序
系统 SHALL 按以下顺序判断一个嗅探到的请求是否为视频地址：

1. 地址包含 `url=http` 或 `.html` 时不算视频；
2. 爬虫开启 `manualVideoCheck` 时，交给爬虫的 `isVideoFormat` 判断；
3. 否则先用通用规则判断是不是视频格式；不是的话，再用当前页面域名对应的 `rules` 匹配，没有对应域名时用 `host` 为 `*` 的通配规则。

包含 `url=http` 或 `.html` 的地址 MUST NOT 被判定为视频。

#### Scenario: 地址含 url=http 或 .html
- **WHEN** 嗅探到的地址包含 `url=http` 或 `.html`
- **THEN** 该地址不被判定为视频

#### Scenario: 爬虫手动判断
- **WHEN** 爬虫开启了 `manualVideoCheck`
- **THEN** 系统用爬虫的 `isVideoFormat` 判断是否为视频

#### Scenario: 通用规则命中
- **WHEN** 爬虫未开启 `manualVideoCheck`，地址符合通用视频格式规则
- **THEN** 该地址被判定为视频

#### Scenario: 按域名规则匹配
- **WHEN** 地址不符合通用规则，且 `rules` 中有当前页面域名对应的规则
- **THEN** 系统用该域名的规则判断

#### Scenario: 回退到通配规则
- **WHEN** 地址不符合通用规则，且 `rules` 中没有当前页面域名对应的规则
- **THEN** 系统用 `host` 为 `*` 的通配规则判断

### Requirement: 规则配置 rules
配置中的 `rules` SHALL 支持以下字段组合：

| 字段 | 作用 |
| --- | --- |
| `host` + `rule` | 该域名下嗅探时，请求地址匹配 rule 中所有正则才算视频；`host` 可以写 `*` 作为通配 |
| `host` + `filter` | 该域名下匹配 filter 的请求会被排除 |
| `hosts` + `regex` | 对这些域名的 M3U8 用正则删除广告片段 |
| `hosts` + `script` | 嗅探页面加载完成后执行的 JS（比如模拟点击播放按钮） |

示例：

```json
"rules": [
  { "host": "www.example.com", "rule": ["mp4", ".m3u8"], "filter": ["ad.m3u8"] },
  { "hosts": ["example1", "example2"], "regex": ["#EXT-X-DISCONTINUITY\\r*\\n*#EXTINF:6.4,[\\s\\S]*?#EXT-X-DISCONTINUITY"] },
  { "name": "剧圈圈", "hosts": ["jqqzx"], "script": ["document.getElementById('playleft').children[0].contentWindow.document.getElementById('start').click()"] }
]
```

`host` + `rule` 规则中，请求地址 MUST 匹配 `rule` 中所有正则才算视频。

#### Scenario: rule 全部匹配
- **WHEN** 在 `host` 对应域名下嗅探，请求地址匹配该规则 `rule` 中的所有正则
- **THEN** 该请求被判定为视频

#### Scenario: rule 部分匹配
- **WHEN** 请求地址只匹配 `rule` 中部分正则
- **THEN** 该请求不按此规则判定为视频

#### Scenario: filter 排除
- **WHEN** 在 `host` 对应域名下嗅探，请求地址匹配 `filter`
- **THEN** 该请求被排除

#### Scenario: regex 删除 M3U8 广告片段
- **WHEN** 播放 `hosts` 中域名的 M3U8
- **THEN** 系统用 `regex` 中的正则删除匹配的广告片段

#### Scenario: script 页面加载后执行
- **WHEN** `hosts` 中域名的嗅探页面加载完成
- **THEN** 系统执行 `script` 中的 JS

### Requirement: 站点 click 字段
站点字段 `click` SHALL 与 `rules.script` 作用相同，写法为 `域名关键字;JS代码` 或直接写 JS 代码，其优先级 MUST 高于 `rules.script`。

#### Scenario: click 与 rules.script 同时存在
- **WHEN** 站点配置了 `click`，且 `rules` 中也有匹配当前页面的 `script`
- **THEN** 系统优先使用站点 `click` 的 JS

#### Scenario: 带域名关键字的 click
- **WHEN** 站点 `click` 写成 `域名关键字;JS代码`
- **THEN** 嗅探页面匹配该域名关键字时执行该 JS 代码

### Requirement: 广告域名拦截
系统 SHALL 内置一份广告域名列表，并把配置里的 `ads` 合并进来；WebView 嗅探中命中这些域名的请求 MUST 直接返回空响应。

#### Scenario: 内置广告域名
- **WHEN** 嗅探页面请求命中内置广告域名
- **THEN** 该请求直接返回空响应

#### Scenario: 配置 ads 域名
- **WHEN** 配置的 `ads` 中包含某域名，嗅探页面请求了该域名
- **THEN** 该请求直接返回空响应

### Requirement: M3U8 去广告
`设置 → 广告过滤` SHALL 默认开启。开启时播放 m3u8 地址（或本地代理地址）MUST 按以下步骤净化：

1. 先下载 m3u8 内容，如果是多码率的主列表，会再请求一次子列表；
2. 分析切片：统计各切片地址的前缀，删掉占比很少的「异类」切片（通常是插播广告）；识别 `#EXT-X-DISCONTINUITY` 等标记包围的广告段；按 `rules.regex` 删除匹配的片段；
3. 净化后的内容通过本地服务 `http://127.0.0.1:9978/m3u8` 交给播放器。

`设置 → 预缓存` 开启且本次播放适用预缓存时（作用范围见 `vod-player`「预缓存的作用范围」），交给播放器的 `/m3u8` 内容 MUST 是预缓存改写后的列表：广告过滤开启且净化成功时，改写的是净化后的内容；广告过滤关闭或净化失败时，改写的是原文，此时同样经过本地 `/m3u8` 交给播放器。改写后列表里的每个分片地址都指向本机的分片代理。预缓存不适用时，本要求的其余行为保持不变。

#### Scenario: 默认开启
- **WHEN** 用户未修改广告过滤设置
- **THEN** 广告过滤处于开启状态

#### Scenario: 多码率主列表
- **WHEN** 广告过滤开启，下载的 m3u8 是多码率主列表
- **THEN** 系统再请求一次子列表并对子列表内容进行净化

#### Scenario: 删除异类切片
- **WHEN** m3u8 中有少量切片的地址前缀与大多数切片不同
- **THEN** 这些占比很少的切片被删除

#### Scenario: 删除标记包围的广告段
- **WHEN** m3u8 中有被 `#EXT-X-DISCONTINUITY` 等标记包围的广告段
- **THEN** 系统识别并删除该广告段

#### Scenario: 净化结果交给播放器
- **WHEN** m3u8 净化完成
- **THEN** 播放器通过 `http://127.0.0.1:9978/m3u8` 播放净化后的内容

#### Scenario: 开启预缓存时分片指向分片代理
- **WHEN** 广告过滤和 `设置 → 预缓存` 都开启，m3u8 净化完成，并且净化后的列表适用预缓存
- **THEN** 播放器通过本地 `/m3u8` 播放的列表里不含被删除的广告分片
- **AND** 列表里的每个分片地址都指向本机的分片代理，而不是源站

#### Scenario: 去广告关闭或净化失败时预缓存仍然生效
- **WHEN** `设置 → 预缓存` 开启，广告过滤关闭或 m3u8 无法净化，并且 m3u8 原文适用预缓存
- **THEN** 播放器同样通过本地 `/m3u8` 播放，列表内容是原文改写后的结果，分片指向本机的分片代理
- **AND** 列表里的分片不做任何删除

#### Scenario: 未开启预缓存时不改变去广告行为
- **WHEN** `设置 → 预缓存` 关闭
- **THEN** 去广告的下载、净化和交给播放器的方式与本要求前面几条场景描述的完全一致，`/m3u8` 里的分片地址仍指向源站

### Requirement: 特殊链接直接播放
系统 SHALL 直接播放以下特殊链接：

| 链接类型 | 处理方式 |
| --- | --- |
| `magnet:` 磁力 | 迅雷 SDK 解析种子，列出视频文件后边下边播 |
| `thunder://` 迅雷 | 同上 |
| `ed2k://` 电驴 | 同上 |
| `.torrent` 种子 | 同上 |
| `ftp://` | 迅雷下载任务播放 |
| `xg://`、`xgplay://`、`tvbox-xg:` 荐片 | 转换成 FTP 地址后播放 |
| `tvbox-drive://` | 存储空间里的文件，直接播放（可带认证请求头） |
| 部分分段 mp4（`ziyuantt.com` 带 `&url=a|b|c`） | 按分段依次播放 |

磁力、迅雷、电驴、种子链接 MUST 先列出视频文件，再边下边播。

#### Scenario: 磁力 / 迅雷 / 电驴 / 种子链接
- **WHEN** 播放地址是 `magnet:`、`thunder://`、`ed2k://` 或 `.torrent` 链接
- **THEN** 系统通过迅雷 SDK 解析并列出其中的视频文件
- **AND** 用户选择后边下边播

#### Scenario: FTP 链接
- **WHEN** 播放地址是 `ftp://` 链接
- **THEN** 系统通过迅雷下载任务播放

#### Scenario: 荐片链接
- **WHEN** 播放地址是 `xg://`、`xgplay://` 或 `tvbox-xg:` 链接
- **THEN** 系统把它转换成 FTP 地址后播放

#### Scenario: 存储空间文件
- **WHEN** 播放地址是 `tvbox-drive://` 链接
- **THEN** 系统直接播放存储空间里的该文件，可带认证请求头

#### Scenario: 分段 mp4
- **WHEN** 播放地址为 `ziyuantt.com` 且带 `&url=a|b|c` 形式的分段
- **THEN** 系统按分段依次播放

### Requirement: 安全 DNS
`设置 → 安全DNS` SHALL 允许为全局网络请求开启 DNS over HTTPS，可选腾讯、阿里、360、Google、AdGuard、Quad9；本地服务 MUST 提供 `/dns-query` 端点，可以把 DoH 查询转发给爬虫等使用。

#### Scenario: 开启安全 DNS
- **WHEN** 用户在 `设置 → 安全DNS` 选择某个 DoH 服务商
- **THEN** 全局网络请求通过该服务商的 DNS over HTTPS 解析域名

#### Scenario: 本地 DoH 端点
- **WHEN** 爬虫等组件请求本地服务的 `/dns-query` 端点
- **THEN** 本地服务把 DoH 查询转发并返回结果
