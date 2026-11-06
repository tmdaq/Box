# 远程控制与推送

## Purpose
TVBox 启动时在本机开启 HTTP 服务，让手机或电脑通过浏览器遥控盒子（搜索、推送播放、远程填写配置、管理存储卡文件），并作为爬虫代理、M3U8 去广告、HLS 预缓存分片代理和直播源加载的基础；同时支持设备间推送和系统广播控制。

## Requirements

### Requirement: 本机 HTTP 服务端口
系统 SHALL 在启动时开启本机 HTTP 服务，默认端口为 9978；端口被占用时 MUST 依次尝试下一个端口，最大到 9999。服务 SHALL 同时提供局域网地址 `http://<盒子IP>:<端口>/` 和本机地址 `http://127.0.0.1:<端口>/`。

#### Scenario: 默认端口可用
- **WHEN** App 启动且 9978 端口未被占用
- **THEN** HTTP 服务监听 9978 端口
- **AND** 局域网可通过 `http://<盒子IP>:9978/` 访问，本机可通过 `http://127.0.0.1:9978/` 访问

#### Scenario: 默认端口被占用
- **WHEN** App 启动且 9978 端口已被占用
- **THEN** 系统依次尝试 9979、9980 …… 直到找到可用端口，最大尝试到 9999

### Requirement: 服务地址二维码
系统 SHALL 在推送页、搜索页和配置地址对话框中显示服务地址的二维码，手机扫码即可打开遥控网页。

#### Scenario: 扫码打开遥控网页
- **WHEN** 用户打开推送页、搜索页或配置地址对话框
- **THEN** 界面显示服务地址的二维码
- **AND** 手机扫描该二维码后在浏览器中打开遥控网页

### Requirement: 遥控网页远程搜索
浏览器打开服务地址后，遥控网页 SHALL 提供「TVBox · 搜索」模块；用户提交关键词后，盒子 MUST 自动打开搜索页并开始搜索该关键词。对应接口为 `POST /action?do=search&word=`。

#### Scenario: 远程提交搜索关键词
- **WHEN** 用户在遥控网页「TVBox · 搜索」模块输入关键词并提交
- **THEN** 盒子自动打开搜索页
- **AND** 以该关键词开始搜索

### Requirement: 遥控网页推送播放地址
遥控网页 SHALL 提供「TVBox · 推送」模块；用户推送播放地址后，盒子 MUST 使用 key 为 `push_agent` 的站点打开详情页播放。对应接口为 `POST /action?do=push&url=`。

#### Scenario: 远程推送播放地址
- **WHEN** 用户在遥控网页「TVBox · 推送」模块提交一个播放地址
- **THEN** 盒子使用 `push_agent` 站点打开该地址的详情页并播放

### Requirement: 遥控网页远程填写配置
遥控网页 SHALL 提供「TVBox · 接口」模块，用于远程填写配置地址、直播地址、EPG 地址；对应接口为 `POST /action?do=api&url=`、`do=live`、`do=epg`、`do=proxys`（分别对应配置 / 直播 / EPG / 代理地址）。盒子上的配置地址对话框打开时，系统 MUST 将远程提交的地址自动填入。

#### Scenario: 远程填写配置地址
- **WHEN** 盒子上的配置地址对话框处于打开状态，且用户在遥控网页「TVBox · 接口」模块提交配置地址
- **THEN** 该地址自动填入盒子上的配置地址输入框

#### Scenario: 远程填写直播、EPG 或代理地址
- **WHEN** 用户通过 `do=live`、`do=epg` 或 `do=proxys` 提交地址
- **THEN** 对应的直播地址、EPG 地址或代理地址被远程填写

### Requirement: 遥控网页本地文件管理
遥控网页 SHALL 提供「本地文件」模块，支持浏览盒子存储卡文件、上传文件、新建文件夹、删除文件夹和删除文件；上传 zip 文件时系统 MUST 自动解压。对应接口为 `GET /file/<路径>`（读取存储卡文件或列出目录）以及 `POST /upload`、`/newFolder`、`/delFolder`、`/delFile`。

#### Scenario: 浏览存储卡目录
- **WHEN** 用户在「本地文件」模块打开某个目录
- **THEN** 网页列出该目录下的文件和文件夹

#### Scenario: 读取存储卡文件
- **WHEN** 客户端请求 `GET /file/<路径>` 且路径指向文件
- **THEN** 服务返回该文件内容

#### Scenario: 上传 zip 文件
- **WHEN** 用户通过「本地文件」模块上传一个 zip 文件
- **THEN** 文件上传到盒子存储卡并自动解压

#### Scenario: 新建与删除
- **WHEN** 用户在「本地文件」模块新建文件夹、删除文件夹或删除文件
- **THEN** 盒子存储卡上对应地创建或删除该文件夹 / 文件

### Requirement: 推送页
首页「推送」入口 SHALL 打开推送页，推送页 MUST 显示遥控二维码和服务地址，并提供「推送剪贴板内容」功能，把盒子剪贴板里的链接当作推送地址直接打开。

#### Scenario: 查看遥控地址
- **WHEN** 用户从首页点击「推送」入口
- **THEN** 推送页显示遥控二维码和服务地址

#### Scenario: 推送剪贴板内容
- **WHEN** 盒子剪贴板中有一个链接，用户在推送页点击「推送剪贴板内容」
- **THEN** 系统把该链接当作推送地址直接打开

### Requirement: 推送到另一台 TVBox
详情页 SHALL 提供「推送」按钮，用户填写对方 TVBox 的 IP 和端口后，系统 MUST 把当前影片的 `id` 和 `sourceKey` 发送到对方的 `/action?do=mirror`，并记住填写的 IP 和端口。接收方收到 `POST /action?do=mirror&id=&sourceKey=` 时 SHALL 直接打开同一部影片的详情页。

#### Scenario: 设备间推送影片
- **WHEN** 用户在详情页点击「推送」并填写另一台 TVBox 的 IP 和端口后确认
- **THEN** 当前影片的 `id` 和 `sourceKey` 被发送到对方的 `/action?do=mirror`
- **AND** 对方 TVBox 直接打开同一部影片的详情页

#### Scenario: 记住对方地址
- **WHEN** 用户再次在详情页点击「推送」
- **THEN** 输入框中预填上次填写的 IP 和端口

### Requirement: 推送链接格式
推送功能 MUST 依赖配置中 key 为 `push_agent` 的站点。链接 `push://地址`（地址做 URL 编码）或 `push://b64:Base64地址` SHALL 被识别为推送链接并交给 `push_agent` 处理。接口 `GET /api/updateUrl?url=...` 也 SHALL 可用于推送播放地址。

#### Scenario: URL 编码的推送链接
- **WHEN** 系统遇到形如 `push://<URL 编码的地址>` 的链接
- **THEN** 该链接被识别为推送链接，解码后的地址交给 `push_agent` 站点处理

#### Scenario: Base64 推送链接
- **WHEN** 系统遇到形如 `push://b64:<Base64 编码的地址>` 的链接
- **THEN** 该链接被识别为推送链接，Base64 解码后的地址交给 `push_agent` 站点处理

#### Scenario: 通过 updateUrl 接口推送
- **WHEN** 客户端请求 `GET /api/updateUrl?url=<地址>`
- **THEN** 该地址作为推送播放地址被打开

### Requirement: HTTP 接口一览
HTTP 服务 SHALL 提供以下接口：

| 路径 | 方法 | 说明 |
| --- | --- | --- |
| `/` | GET | 遥控网页 |
| `/action?do=search&word=` | POST | 远程搜索 |
| `/action?do=api&url=` / `live` / `epg` / `proxys` | POST | 远程填写配置 / 直播 / EPG / 代理地址 |
| `/action?do=push&url=` | POST | 推送播放地址 |
| `/action?do=mirror&id=&sourceKey=` | POST | 打开指定影片的详情页（设备间推送） |
| `/api/updateUrl?url=` | GET | 推送播放地址 |
| `/api/remote/version`、`/index.html` | GET | 探活 |
| `/media` | GET | 正在播放的影片名和集名（JSON） |
| `/proxy?do=...` | GET | 爬虫本地代理（Jar / JS / Python 的 `proxyLocal`），也用于直播源加载 `do=live` |
| `/proxy?go=...` | GET | 通用转发代理 |
| `/m3u8` | GET | 播放器使用的 M3U8 内容：去广告后的结果，开启预缓存时分片地址改写为分片代理 |
| `/hlsseg?s=<会话>&i=<序号>` | GET | 分片代理：为本机播放器提供 HLS 预缓存分片，只接受本机请求 |
| `/dash/...` | GET | 爬虫提供的 DASH（mpd）数据 |
| `/dns-query?name=` | GET | DoH 转发 |
| `/file/<路径>` | GET | 读取存储卡文件或列出目录 |
| `/upload`、`/newFolder`、`/delFolder`、`/delFile` | POST | 存储卡文件管理 |

分片代理 MUST 只接受来自本机回环地址的请求；其他来源的请求 MUST 返回 403，并且不读取预缓存、不请求源站。对本机请求，分片代理 SHALL 原样返回预缓存给出的状态码（包括源站返回的错误码），并以流式方式返回分片字节，不必等整个分片下载完。分片代理的响应 MUST NOT 带 `Content-Encoding`，也 MUST NOT 转发源站的响应头。新增分片代理 MUST NOT 改变表中其他接口的行为。

#### Scenario: 打开遥控网页
- **WHEN** 浏览器请求 `GET /`
- **THEN** 服务返回遥控网页

#### Scenario: 探活
- **WHEN** 客户端请求 `GET /api/remote/version` 或 `GET /index.html`
- **THEN** 服务正常响应，表明盒子服务在线

#### Scenario: 查询正在播放的内容
- **WHEN** 客户端请求 `GET /media`
- **THEN** 服务以 JSON 返回正在播放的影片名和集名

#### Scenario: 爬虫本地代理
- **WHEN** 客户端请求 `GET /proxy?do=...`
- **THEN** 请求交给爬虫（Jar / JS / Python）的 `proxyLocal` 处理并返回结果
- **AND** `do=live` 时用于直播源加载

#### Scenario: 通用转发代理
- **WHEN** 客户端请求 `GET /proxy?go=...`
- **THEN** 服务作为通用转发代理转发该请求

#### Scenario: 获取去广告 M3U8
- **WHEN** 客户端请求 `GET /m3u8`
- **THEN** 服务返回去广告后的 M3U8 内容；开启预缓存时，其中的分片地址指向分片代理 `/hlsseg`

#### Scenario: 获取 DASH 数据
- **WHEN** 客户端请求 `GET /dash/...`
- **THEN** 服务返回爬虫提供的 DASH（mpd）数据

#### Scenario: DoH 转发
- **WHEN** 客户端请求 `GET /dns-query?name=<域名>`
- **THEN** 服务转发该 DoH 查询并返回结果

#### Scenario: 本机播放器获取分片
- **WHEN** 本机播放器请求 `GET /hlsseg?s=<当前预缓存会话>&i=<分片序号>`，并且该分片能从预缓存或源站拿到
- **THEN** 服务返回 200 和该分片的原始字节
- **AND** 分片长度已知时按定长返回，未知时以 chunked 方式边取边返回
- **AND** 即使请求带了 `Accept-Encoding: gzip`，响应也不带 `Content-Encoding`

#### Scenario: 局域网请求分片代理被拒绝
- **WHEN** 局域网里的其他设备请求 `GET /hlsseg?s=<任意会话>&i=<任意序号>`
- **THEN** 服务返回 403
- **AND** 不读取预缓存，也不请求源站

#### Scenario: 源站错误原样返回
- **WHEN** 本机播放器请求某个分片，而源站对该分片返回非 2xx 状态码（如 404、429、503）
- **THEN** 分片代理返回同一个状态码，状态码不在常见列表里时也原样返回
- **AND** 响应不带 `Content-Encoding`，也不带源站的其他响应头

#### Scenario: 会话失效返回 410
- **WHEN** 本机播放器用已结束或已被替换的预缓存会话标识请求 `GET /hlsseg`
- **THEN** 服务返回 410
- **AND** 不请求源站

### Requirement: 系统广播接口
系统 SHALL 允许其他应用（如语音助手、桌面）通过广播控制 TVBox，支持的广播如下：

| Action | Extras | 效果 |
| --- | --- | --- |
| `android.content.movie.search.Action` | `title` | 打开搜索页并搜索 |
| `android.content.movie.detail.Action` | `id`、`sourceKey` | 打开影片详情页 |
| `android.content.movie.custom.web.Action` | `action`（刷新解析 / 刷新直播等） | 通知 App 刷新对应数据 |

收到上述广播时，系统 MUST 执行表中对应的效果。

#### Scenario: 广播搜索
- **WHEN** 其他应用发送 Action 为 `android.content.movie.search.Action`、Extra `title` 为关键词的广播
- **THEN** TVBox 打开搜索页并搜索该关键词

#### Scenario: 广播打开详情
- **WHEN** 其他应用发送 Action 为 `android.content.movie.detail.Action`、带 `id` 和 `sourceKey` 的广播
- **THEN** TVBox 打开对应影片的详情页

#### Scenario: 广播刷新数据
- **WHEN** 其他应用发送 Action 为 `android.content.movie.custom.web.Action`、Extra `action` 为刷新解析或刷新直播等的广播
- **THEN** TVBox 刷新对应的数据
