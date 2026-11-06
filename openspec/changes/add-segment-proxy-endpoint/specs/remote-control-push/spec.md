## MODIFIED Requirements

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
