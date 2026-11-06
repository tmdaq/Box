# 直播

## Purpose
直播模块从配置或用户设置中获取直播源，解析出分组与频道列表，在电视上用遥控器换台、切换线路、查看 EPG 节目单并支持回看时移播放。

## Requirements

### Requirement: 直播源来源与优先级
系统 SHALL 从配置地址的 `lives` 字段获取直播源；当用户在「设置 → 配置地址」中单独填写了「直播地址」时，系统 MUST 优先使用该直播地址而不是配置中的地址。使用过的直播地址 SHALL 自动加入「直播历史列表」，用户可以在直播设置菜单或配置对话框里切换。

#### Scenario: 单独填写的直播地址优先
- **WHEN** 配置中有 `lives` 地址，且用户在「设置 → 配置地址」中填写了直播地址
- **THEN** 系统使用用户填写的直播地址加载直播

#### Scenario: 仅有配置中的直播源
- **WHEN** 用户没有单独填写直播地址，配置中有 `lives` 字段
- **THEN** 系统使用配置中的 `lives` 加载直播

#### Scenario: 直播地址进入历史列表
- **WHEN** 系统使用某个直播地址
- **THEN** 该地址自动加入「直播历史列表」
- **AND** 用户可以在直播设置菜单的「直播地址」或配置对话框里切换到历史中的地址

### Requirement: lives 字段的写法
系统 SHALL 支持以下三种 `lives` 写法，并 MUST 最终都通过本地代理 `http://127.0.0.1:9978/proxy?do=live&type=txt&ext=...` 下载并解析频道列表：

| 写法 | 说明 |
| --- | --- |
| `proxy://do=live&type=txt&ext=地址` | 旧式代理写法，`ext` 可以是 http / `clan://` 地址或 Base64 编码 |
| `{"type":"0","url":"http://...","epg":"...","playerType":1}` | FongMi 风格写法，可以带 EPG 地址和直播专用播放器 |
| `[{"group":"分组","channels":[{"name":"频道","urls":["..."]}]}]` | 直接内嵌的频道 JSON |

#### Scenario: 旧式代理写法
- **WHEN** `lives` 写成 `proxy://do=live&type=txt&ext=地址`，`ext` 为 http 地址、`clan://` 地址或 Base64 编码
- **THEN** 系统通过本地代理下载并解析该地址对应的频道列表

#### Scenario: FongMi 风格写法
- **WHEN** `lives` 写成 `{"type":"0","url":"http://...","epg":"...","playerType":1}`
- **THEN** 系统通过本地代理下载并解析 `url` 的频道列表
- **AND** 使用其中的 `epg` 作为 EPG 地址、`playerType` 作为直播专用播放器

#### Scenario: 内嵌频道 JSON
- **WHEN** `lives` 直接写成包含 `group` 与 `channels`（`name`、`urls`）的 JSON 数组
- **THEN** 系统按其中的分组和频道显示频道列表

### Requirement: TXT 频道列表格式
系统 SHALL 解析 TXT 格式的频道列表：`分组名,#genre#` 开始一个分组，其后每行为 `频道名,地址`。没有分组的频道 MUST 归到「未分组」；一行里可以用 `#` 分隔多个地址；同名频道出现多次时地址 SHALL 合并成多条线路；支持 http / rtsp / rtmp 地址。示例：

```
央视频道,#genre#
CCTV-1,http://a.m3u8#http://b.m3u8
CCTV-1,rtmp://c
卫视频道,#genre#
湖南卫视,http://d.m3u8
```

#### Scenario: 分组行
- **WHEN** 列表中出现 `央视频道,#genre#`
- **THEN** 其后的频道归入「央视频道」分组，直到出现下一个分组行

#### Scenario: 没有分组的频道
- **WHEN** 频道行之前没有任何 `#genre#` 分组行
- **THEN** 该频道归到「未分组」

#### Scenario: 多地址与同名合并
- **WHEN** 列表中有 `CCTV-1,http://a.m3u8#http://b.m3u8` 和 `CCTV-1,rtmp://c`
- **THEN** 「CCTV-1」频道只出现一次，拥有 3 条线路

### Requirement: M3U 频道列表格式
系统 SHALL 解析以 `#EXTM3U` 开头的 M3U 格式频道列表，并 MUST 按 `#EXTINF` 的 `group-title` 对频道分组。

#### Scenario: 按 group-title 分组
- **WHEN** 频道列表以 `#EXTM3U` 开头，`#EXTINF` 行带有 `group-title="卫视"`
- **THEN** 该频道显示在「卫视」分组下

### Requirement: 分组密码
当分组名写成 `分组名_密码` 时，系统 MUST 在进入该分组前要求输入密码；直播设置中开启「关闭密码」时 SHALL 跳过密码。

#### Scenario: 进入加密分组
- **WHEN** 分组名为 `成人_1234`，用户选择进入该分组，且未开启「关闭密码」
- **THEN** 系统弹出密码输入框，输入正确密码后才能进入

#### Scenario: 关闭密码
- **WHEN** 直播设置的偏好设置中开启了「关闭密码」
- **THEN** 进入加密分组时不再要求输入密码

### Requirement: 直播请求头
系统 SHALL 默认以请求头 `User-Agent: Lavf/59.27.100` 请求直播地址；配置中的 `livePlayHeaders` MUST 能按地址关键字（`flag`）给匹配的直播地址指定请求头。

#### Scenario: 默认请求头
- **WHEN** 直播地址未匹配任何 `livePlayHeaders` 规则
- **THEN** 系统以 `User-Agent: Lavf/59.27.100` 请求该地址

#### Scenario: 按关键字指定请求头
- **WHEN** 配置的 `livePlayHeaders` 中某条规则的 `flag` 包含在直播地址中
- **THEN** 系统使用该规则指定的请求头播放该地址

### Requirement: 遥控器按键
直播播放时系统 SHALL 按下表响应遥控器按键：

| 按键 | 正常直播 | 回看中 |
| --- | --- | --- |
| 上 / 下 | 上一个 / 下一个频道（可以在设置里反转） | 同左 |
| 左 | 打开设置菜单 | 显示节目信息 / 进度条 |
| 右 | 切换到下一条线路 | 显示节目信息 / 进度条 |
| 确定 / 播放键 | 打开频道列表 | 同左 |
| 菜单键 | 打开设置菜单 | 同左 |
| 数字键 | 输入频道号直接换台 | 同左 |
| 返回 | 连按两次退出直播 | — |

#### Scenario: 上下换台
- **WHEN** 正常直播或回看中用户按上 / 下键
- **THEN** 切换到上一个 / 下一个频道
- **AND** 开启「换台反转」后方向相反

#### Scenario: 正常直播左右键
- **WHEN** 正常直播时用户按左键
- **THEN** 打开设置菜单
- **AND** 按右键时切换到当前频道的下一条线路

#### Scenario: 回看中左右键
- **WHEN** 回看中用户按左键或右键
- **THEN** 显示节目信息和进度条

#### Scenario: 打开频道列表与设置菜单
- **WHEN** 用户按确定键或播放键
- **THEN** 打开频道列表
- **AND** 按菜单键时打开设置菜单

#### Scenario: 数字键换台
- **WHEN** 用户用数字键输入频道号
- **THEN** 直接切换到该频道号对应的频道

#### Scenario: 返回键退出
- **WHEN** 正常直播时用户连按两次返回键
- **THEN** 退出直播

### Requirement: 频道列表与跨分组换台
频道列表 SHALL 左边显示分组、右边显示当前分组的频道；开启「跨选分类」后，上下换台 MUST 能跨越分组边界继续切换。

#### Scenario: 跨选分类开启
- **WHEN** 「跨选分类」已开启，当前为某分组最后一个频道，用户按下一个频道
- **THEN** 切换到下一个分组的频道

### Requirement: 记住上次观看的频道
系统 SHALL 记住上次观看的频道，下次进入直播时 MUST 自动播放该频道。

#### Scenario: 再次进入直播
- **WHEN** 用户上次在直播中观看「湖南卫视」后退出，再次进入直播
- **THEN** 系统自动播放「湖南卫视」

### Requirement: 直播设置菜单
直播设置菜单 SHALL 提供以下分组和选项：

| 分组 | 选项 |
| --- | --- |
| 线路选择 | 当前频道的所有线路（屏幕上显示「线路 x/n」） |
| 画面比例 | 默认 / 16:9 / 4:3 / 填充 / 原始 / 裁剪 |
| 播放解码 | 系统 / ijk硬解 / ijk软解 / exo |
| 超时换源 | 关 / 5s / 10s / 15s / 20s / 25s / 30s |
| 偏好设置 | 显示时间、显示网速、换台反转、跨选分类、关闭密码 |
| 直播地址 | 列表历史：切换历史直播地址 |
| 退出直播 | 确定退出 |

#### Scenario: 选择线路
- **WHEN** 用户在「线路选择」中选择当前频道的某条线路
- **THEN** 播放切换到该线路
- **AND** 屏幕上显示「线路 x/n」

#### Scenario: 切换画面比例与解码
- **WHEN** 用户在「画面比例」或「播放解码」中选择一个选项
- **THEN** 直播画面按所选比例显示或以所选解码器播放

#### Scenario: 退出直播
- **WHEN** 用户选择「退出直播」并确定
- **THEN** 退出直播

### Requirement: 超时换源
当「超时换源」设为 5s / 10s / 15s / 20s / 25s / 30s 之一时，系统 MUST 在设定时间内没加载出画面时自动换到下一条线路；设为「关」时 SHALL 不自动换源。

#### Scenario: 超时自动换线路
- **WHEN** 「超时换源」设为 10s，当前线路 10 秒内没有加载出来
- **THEN** 系统自动切换到下一条线路

#### Scenario: 关闭超时换源
- **WHEN** 「超时换源」设为「关」
- **THEN** 线路加载缓慢时系统不自动换源

### Requirement: EPG 地址来源
系统 SHALL 从配置 `lives[].epg` 或设置里填写的 EPG 地址获取节目单，二者都有时 MUST 优先使用设置里的地址；都没有配置时使用默认接口 `https://epg.112114.xyz/`。

#### Scenario: 设置中的 EPG 优先
- **WHEN** 配置 `lives[].epg` 与设置里的 EPG 地址都存在
- **THEN** 系统使用设置里的 EPG 地址

#### Scenario: 默认 EPG 接口
- **WHEN** 没有配置任何 EPG 地址
- **THEN** 系统使用 `https://epg.112114.xyz/`

### Requirement: EPG 地址模板
当 EPG 地址包含 `{name}` 和 `{date}` 时，系统 SHALL 把它们替换成频道名和日期；否则系统 MUST 自动拼成 `地址?ch=频道名&date=yyyy-MM-dd`。接口返回的 `epg_data` 中每条节目包含 `title`、`start`、`end`。

#### Scenario: 含占位符的地址
- **WHEN** EPG 地址为 `http://x/epg?n={name}&d={date}`，查询频道「CCTV-1」
- **THEN** 系统将 `{name}` 替换为频道名、`{date}` 替换为日期后请求

#### Scenario: 不含占位符的地址
- **WHEN** EPG 地址为 `https://epg.112114.xyz/`，查询频道「CCTV-1」在 2024-01-01 的节目
- **THEN** 系统请求 `https://epg.112114.xyz/?ch=CCTV-1&date=2024-01-01`

### Requirement: 节目信息展示
频道信息栏 SHALL 显示当前节目和下一个节目；节目单 MUST 可以按日期查看（昨天 / 今天 / 明天 / 后天等）。

#### Scenario: 信息栏显示节目
- **WHEN** 当前频道获取到 EPG 数据
- **THEN** 频道信息栏显示当前节目和下一个节目

#### Scenario: 按日期查看节目单
- **WHEN** 用户在节目单中选择「昨天」
- **THEN** 显示该频道昨天的节目列表

### Requirement: 频道别名与台标映射
系统 SHALL 内置频道别名到 EPG 标识的映射以及频道台标地址，使同一频道的不同写法 MUST 对应到同一个节目单。

#### Scenario: 不同写法对应同一节目单
- **WHEN** 频道名为「CCTV1」或「CCTV-1综合」
- **THEN** 两者显示同一份节目单和台标

### Requirement: 回看（时移）
地址中带 `PLTV/8888` 的频道 SHALL 支持回看：用户可以在节目单中选择已经播出的节目，系统 MUST 在地址后追加 `?playseek=开始时间-结束时间` 播放；回看状态下显示进度条，可以拖动。

#### Scenario: 选择已播节目回看
- **WHEN** 频道地址包含 `PLTV/8888`，用户在节目单中选择一个已经播出的节目
- **THEN** 系统以 `地址?playseek=开始时间-结束时间` 播放该节目
- **AND** 显示可拖动的进度条

#### Scenario: 不支持回看的频道
- **WHEN** 频道地址不包含 `PLTV/8888`
- **THEN** 不能在节目单中回看已播节目

### Requirement: 播放状态信息显示
系统 SHALL 可以在直播画面上显示网速（MB/s）、系统时间和分辨率；网速和时间 MUST 可以通过偏好设置中的「显示网速」「显示时间」控制。

#### Scenario: 开启显示网速与时间
- **WHEN** 用户在偏好设置中开启「显示网速」和「显示时间」
- **THEN** 直播画面上显示以 MB/s 为单位的网速和系统时间

### Requirement: 启动时进直播
当「设置 → 启动时进直播」打开时，App 启动后 SHALL 直接进入直播。

#### Scenario: 启动直接进入直播
- **WHEN** 「启动时进直播」已打开，用户启动 App
- **THEN** App 直接进入直播界面

### Requirement: 直播专用播放器
配置 `lives[].playerType` SHALL 可以给直播单独指定播放器，直播 MUST 使用该播放器播放。

#### Scenario: 指定直播播放器
- **WHEN** 配置中 `lives[].playerType` 为 1
- **THEN** 直播使用 `playerType` 1 对应的播放器播放
