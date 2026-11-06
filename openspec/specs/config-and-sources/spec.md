# 配置与数据源

## Purpose
TVBox 的全部内容都由一个 JSON「配置地址」驱动。本能力定义配置地址的填写、格式识别、解密、缓存容错，以及配置中站点、爬虫引擎、其他配置字段和首页数据源切换的行为。

## Requirements

### Requirement: 配置地址对话框
系统 SHALL 在 `设置 → 配置地址` 提供配置地址对话框，允许同时填写以下 4 项：

| 项 | 说明 |
| --- | --- |
| 配置地址 | 点播配置 JSON |
| 直播地址 | 可单独指定，覆盖配置里的 `lives` |
| EPG 地址 | 节目单接口 |
| 代理地址 | 会被保存，但当前不被其他功能读取 |

#### Scenario: 单独指定直播地址
- **WHEN** 用户在对话框中填写了直播地址
- **THEN** 系统使用该直播地址，覆盖配置 JSON 中的 `lives`

#### Scenario: 填写代理地址
- **WHEN** 用户在对话框中填写代理地址并保存
- **THEN** 系统保存该代理地址
- **AND** 该代理地址不影响其他功能的行为

### Requirement: 地址历史列表
系统 MUST 为配置地址、直播地址、EPG 地址各自保留历史列表，用户可从历史中快速切换。

#### Scenario: 从历史切换配置地址
- **WHEN** 用户打开配置地址的历史列表并选择其中一条
- **THEN** 该条地址被填入配置地址输入项

#### Scenario: 直播与 EPG 历史独立
- **WHEN** 用户分别使用过多个直播地址和 EPG 地址
- **THEN** 直播地址与 EPG 地址各有独立的历史列表可供选择

### Requirement: 扫码远程填写地址
配置地址对话框 SHALL 显示一个二维码，用户用手机扫码后可在浏览器里远程填写地址（详见远程控制与推送能力）。

#### Scenario: 手机扫码填写
- **WHEN** 用户用手机扫描对话框中的二维码
- **THEN** 手机浏览器打开远程填写页面，用户可在其中提交地址

### Requirement: 支持的地址格式
系统 SHALL 识别并处理以下配置地址格式：

| 格式 | 说明 |
| --- | --- |
| `http(s)://...` | 网络配置 |
| 不带协议的地址 | 自动补上 `http://` |
| `clan://localhost/xxx` | 本地存储卡上的文件 |
| `file://xxx`、`./xxx` | 输入时自动转换成 `clan://localhost/xxx` |
| `地址;pk;密钥` | 加密配置，用 `;pk;` 后面的密钥做 AES-ECB 解密 |

#### Scenario: 网络地址
- **WHEN** 用户输入 `https://example.com/config.json`
- **THEN** 系统通过网络加载该配置

#### Scenario: 不带协议的地址
- **WHEN** 用户输入 `example.com/config.json`
- **THEN** 系统自动补全为 `http://example.com/config.json` 后加载

#### Scenario: 本地文件地址转换
- **WHEN** 用户输入 `file://tv/config.json` 或 `./tv/config.json`
- **THEN** 系统将其转换为 `clan://localhost/tv/config.json`
- **AND** 从本地存储卡读取该文件

#### Scenario: 带密钥的加密地址
- **WHEN** 用户输入 `https://example.com/config;pk;mykey`
- **THEN** 系统请求 `;pk;` 之前的地址，并用 `mykey` 对返回内容做 AES-ECB 解密

### Requirement: 配置内容的加密与包裹识别
系统 MUST 自动识别配置内容的格式并还原为 JSON：

- 明文 JSON：直接使用；
- 图片包裹：内容中有 `xxxxxxxx**` 标记（8 位字母），标记后的部分按 Base64 解码；
- AES-CBC：以 `2423` 开头的十六进制内容，从内容里取 key 和 iv 解密；
- AES-ECB：地址带 `;pk;密钥` 时，用该密钥解密。

#### Scenario: 明文 JSON
- **WHEN** 配置内容本身是合法 JSON
- **THEN** 系统直接使用该内容

#### Scenario: 图片包裹
- **WHEN** 配置内容中包含 8 位字母加 `**` 的标记
- **THEN** 系统将标记之后的部分按 Base64 解码得到配置

#### Scenario: AES-CBC 加密
- **WHEN** 配置内容是以 `2423` 开头的十六进制字符串
- **THEN** 系统从内容中取出 key 和 iv 并做 AES-CBC 解密得到配置

#### Scenario: AES-ECB 加密
- **WHEN** 配置地址带有 `;pk;密钥`
- **THEN** 系统用该密钥对内容做 AES-ECB 解密得到配置

### Requirement: 配置缓存与网络失败回退
系统 SHALL 将解析成功的配置按地址的 MD5 缓存到应用私有目录；启动时可先使用缓存以加快首页加载；网络请求失败时 MUST 自动回退到本地缓存。

#### Scenario: 启动时使用缓存
- **WHEN** 应用启动且该配置地址存在本地缓存
- **THEN** 系统可先用缓存配置加载首页

#### Scenario: 网络失败回退
- **WHEN** 请求配置地址时网络失败
- **AND** 该地址存在本地缓存
- **THEN** 系统使用本地缓存的配置

### Requirement: 站点类型
系统 SHALL 从配置的 `sites`（或 `video.sites`）数组加载站点，并按站点字段 `type` 决定接口协议：

| type | 说明 |
| --- | --- |
| `0` | 苹果CMS XML 接口（`ac=videolist`） |
| `1` | 苹果CMS JSON 接口（`ac=detail`） |
| `3` | 爬虫（Spider），由 Jar / JS / Python 实现 |
| `4` | 远程 T4 接口：HTTP 接口，以 `filter`、`extend`、`ac`、`ids` 等参数请求 |

#### Scenario: 从 video.sites 加载
- **WHEN** 配置中没有顶层 `sites`，但有 `video.sites` 数组
- **THEN** 系统从 `video.sites` 加载站点

#### Scenario: 苹果CMS XML 站点
- **WHEN** 站点 `type` 为 `0`
- **THEN** 系统以 `ac=videolist` 请求该站点的 XML 接口

#### Scenario: 苹果CMS JSON 站点
- **WHEN** 站点 `type` 为 `1`
- **THEN** 系统以 `ac=detail` 请求该站点的 JSON 接口

#### Scenario: 远程 T4 站点
- **WHEN** 站点 `type` 为 `4`
- **THEN** 系统以 `filter`、`extend`、`ac`、`ids` 等参数请求该 HTTP 接口

### Requirement: 站点字段与默认值
系统 SHALL 按下表解析站点字段，未填写的字段 MUST 使用表中默认值：

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `key` | 必填 | 站点唯一标识；以 `py_` 开头的站点默认不可筛选 |
| `name` | 同 key | 显示名称 |
| `type` | 必填 | 见站点类型 |
| `api` | 必填 | 接口地址或爬虫类名 / 脚本地址 |
| `searchable` | 1 | 是否参与搜索 |
| `quickSearch` | 1 | 是否参与详情页快搜 |
| `filterable` | 1 | 是否支持分类筛选 |
| `hide` | 0 | 是否在站点列表中隐藏 |
| `playUrl` | 空 | 站点自带的解析地址，支持 `json:` / `parse:解析名` 前缀 |
| `ext` | 空 | 传给爬虫的扩展参数 |
| `jar` | 空 | 站点单独使用的 Jar（不填则用全局 `spider`） |
| `playerType` | -1 | 站点指定播放器（-1 表示使用全局设置） |
| `categories` | 空 | 只显示列出的分类 |
| `click` | 空 | 嗅探时要模拟点击的元素选择器 |
| `style` | 空 | 海报样式，如 `{"type":"rect","ratio":1.33}`，type 可为 rect / list 等 |

#### Scenario: 省略 name
- **WHEN** 站点未填写 `name`
- **THEN** 站点显示名称与 `key` 相同

#### Scenario: py_ 开头的站点默认不可筛选
- **WHEN** 站点 `key` 以 `py_` 开头且未显式配置 `filterable`
- **THEN** 该站点默认不支持分类筛选

#### Scenario: 隐藏站点
- **WHEN** 站点配置 `hide` 为 1
- **THEN** 该站点不出现在站点列表中

#### Scenario: 不参与搜索
- **WHEN** 站点配置 `searchable` 为 0
- **THEN** 该站点不参与搜索

#### Scenario: 不参与快搜
- **WHEN** 站点配置 `quickSearch` 为 0
- **THEN** 该站点不参与详情页快搜

#### Scenario: 站点未指定 jar
- **WHEN** 爬虫站点未配置 `jar`
- **THEN** 该站点使用全局 `spider` 指定的 Jar

#### Scenario: 站点播放器使用全局设置
- **WHEN** 站点 `playerType` 为 -1 或未配置
- **THEN** 该站点使用全局播放器设置

#### Scenario: 站点自带解析地址
- **WHEN** 站点配置 `playUrl` 为 `json:` 或 `parse:解析名` 前缀的地址
- **THEN** 系统按该前缀使用站点自带的解析地址

### Requirement: 爬虫引擎选择
对于爬虫站点，系统 SHALL 按 `api` 的后缀选择爬虫引擎：

| 条件 | 引擎 |
| --- | --- |
| `api` 以 `.js` 结尾或含 `.js?` | JS 爬虫，运行在 QuickJS |
| `api` 含 `.py` | Python 爬虫，只有 `python` 构建变体可用 |
| 其他 | Jar 爬虫 |

#### Scenario: JS 爬虫
- **WHEN** 爬虫站点 `api` 为 `https://example.com/site.js?v=1`
- **THEN** 系统使用 QuickJS 运行该 JS 爬虫

#### Scenario: Python 爬虫
- **WHEN** 爬虫站点 `api` 含 `.py`
- **AND** 当前安装的是 `python` 构建变体
- **THEN** 系统使用 Python 引擎运行该爬虫

#### Scenario: Jar 爬虫
- **WHEN** 爬虫站点 `api` 既不匹配 `.js` 也不含 `.py`
- **THEN** 系统以 Jar 爬虫方式加载该站点

### Requirement: Jar 加载与校验
系统 SHALL 支持全局 `spider` 和站点单独的 `jar`，并 MUST 能同时加载多个 Jar；Jar 地址支持 `地址;md5;校验值` 格式与图片包裹格式。

#### Scenario: 多个 Jar 并存
- **WHEN** 配置了全局 `spider`，且某些站点另外配置了各自的 `jar`
- **THEN** 系统同时加载这些 Jar，各站点使用各自对应的 Jar

#### Scenario: MD5 一致时使用缓存
- **WHEN** Jar 地址为 `地址;md5;校验值`
- **AND** 本地缓存 Jar 的 MD5 与校验值一致
- **THEN** 系统直接使用缓存，不重新下载

#### Scenario: 图片包裹的 Jar
- **WHEN** Jar 地址返回的是图片包裹格式的内容
- **THEN** 系统解出其中的 Jar 并加载

### Requirement: Jar 缓存与清理
系统 SHALL 常驻缓存已加载的 Jar，配置项 `jarCache`（默认 `"true"`）控制加载时是否优先使用一周内的 Jar 缓存；用户可在首页点击 App 名称手动清理；长时间（超过一周）没有手动清理时系统 MUST 自动清理缓存，带 md5 的 Jar 除外；Jar 加载失败时系统 MUST 立即改用上一次缓存的 Jar。

#### Scenario: 手动清理 Jar 缓存
- **WHEN** 用户在首页点击 App 名称
- **THEN** 系统清理当前站点的 Jar 缓存并重新加载首页

#### Scenario: 自动清理过期缓存
- **WHEN** 某个不带 md5 的 Jar 缓存长时间未被手动清理
- **THEN** 系统自动丢弃该缓存并重新下载

#### Scenario: 带 md5 的 Jar 不自动清理
- **WHEN** Jar 地址带 `;md5;校验值`
- **THEN** 系统不因时间长短自动清理该 Jar 缓存

#### Scenario: 加载失败回退缓存
- **WHEN** Jar 下载或加载失败
- **AND** 本地存在上一次缓存的 Jar
- **THEN** 系统立即改用该缓存的 Jar

#### Scenario: 关闭 Jar 缓存
- **WHEN** 配置中 `jarCache` 为 `"false"`
- **THEN** 系统跳过缓存优先加载，直接重新下载 Jar

### Requirement: 其他配置字段
系统 SHALL 识别配置 JSON 中的以下顶层字段：

| 字段 | 说明 |
| --- | --- |
| `spider` | 全局爬虫 Jar 地址 |
| `wallpaper` | 壁纸地址，可在设置里「换张壁纸」 |
| `jarCache` | 是否缓存 Jar，默认 `"true"` |
| `parses` | 解析接口列表（见解析嗅探与去广告能力） |
| `flags` | 需要走解析的线路标识（VIP 线路） |
| `lives` | 直播源（见直播能力） |
| `livePlayHeaders` | 直播请求头，可以按线路 flag 匹配 |
| `rules` | 嗅探 / 过滤 / 去广告 / 点击脚本规则 |
| `ads` | 需要拦截的广告域名（会和内置列表合并） |
| `ijk` | IJK 播放器参数分组（软解 / 硬解等），不配置时使用内置默认值 |

#### Scenario: 广告域名合并
- **WHEN** 配置中提供了 `ads` 域名列表
- **THEN** 系统将其与内置广告域名列表合并后用于拦截

#### Scenario: 未配置 ijk
- **WHEN** 配置中没有 `ijk` 字段
- **THEN** 系统使用内置默认的 IJK 参数分组（软解码 / 硬解码）

#### Scenario: 直播请求头按线路匹配
- **WHEN** 配置了 `livePlayHeaders` 且其中某项匹配当前直播线路的 flag
- **THEN** 系统对该直播线路的请求使用对应请求头

### Requirement: 首页数据源切换
系统 SHALL 允许用户通过 `设置 → 数据源` 切换首页使用的站点，也 MUST 支持在首页按遥控器菜单键弹出站点切换。

#### Scenario: 设置中切换数据源
- **WHEN** 用户在 `设置 → 数据源` 选择另一个站点
- **THEN** 首页改用该站点的内容

#### Scenario: 首页菜单键切换
- **WHEN** 用户在首页按遥控器菜单键
- **THEN** 系统弹出站点切换列表

### Requirement: 数据源显示开关
系统 SHALL 通过 `设置 → 数据源显示` 控制首页是否显示当前站点名称。

#### Scenario: 打开数据源显示
- **WHEN** 用户打开「数据源显示」
- **THEN** 首页显示当前站点名称

### Requirement: 内置推送站点
系统 SHALL 内置 `push_agent` 站点，用于接收推送链接（见远程控制与推送能力）。

#### Scenario: 接收推送链接
- **WHEN** 用户向应用推送一个链接
- **THEN** 系统通过内置的 `push_agent` 站点处理该链接
