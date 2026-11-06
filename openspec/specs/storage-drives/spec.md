# 存储空间（本地 / 网盘）

## Purpose
存储空间模块让用户从首页「文件」入口添加并浏览本地文件夹、WebDAV 与 Alist 网盘，选中视频文件后直接交给播放器播放并支持续播。

## Requirements

### Requirement: 支持的存储类型
系统 SHALL 支持添加以下三种存储空间，添加时填写的内容如下：

| 类型 | 添加时填写 |
| --- | --- |
| 本地 | 用文件选择器选一个本地文件夹 |
| WebDAV | 空间名称、WebDAV 地址、初始路径、用户名、密码 |
| Alist | 空间名称、Alist 地址、初始路径、密码 |

同一个本地文件夹 MUST NOT 被重复添加。Alist 空间通过 Alist 的 `/api/fs/list` 接口列出文件。

#### Scenario: 添加 WebDAV 空间
- **WHEN** 用户填写空间名称、WebDAV 地址、初始路径、用户名、密码并确认
- **THEN** 空间列表中出现该 WebDAV 空间

#### Scenario: 添加 Alist 空间
- **WHEN** 用户填写空间名称、Alist 地址、初始路径、密码并确认
- **THEN** 空间列表中出现该 Alist 空间
- **AND** 进入该空间时通过 `/api/fs/list` 列出目录内容

#### Scenario: 重复添加本地文件夹
- **WHEN** 用户用文件选择器选择一个已经添加过的本地文件夹
- **THEN** 系统不会再添加一个相同的本地空间

### Requirement: 空间持久保存
添加的存储空间 SHALL 保存在本地数据库里，下次打开 MUST 直接显示在空间列表中。

#### Scenario: 重新打开文件入口
- **WHEN** 用户添加空间后退出，下次再从首页「文件」入口进入
- **THEN** 之前添加的空间直接显示在空间列表中

### Requirement: 目录浏览与导航
用户 SHALL 能逐级进入目录；按返回键 MUST 回到上一级目录；按「主页」按钮回到空间列表。

#### Scenario: 进入与返回目录
- **WHEN** 用户在空间中进入子目录后按返回键
- **THEN** 回到上一级目录

#### Scenario: 回到空间列表
- **WHEN** 用户在任意目录中按「主页」按钮
- **THEN** 回到空间列表

### Requirement: 文件排序
系统 SHALL 支持按名字升序 / 降序、按修改时间升序 / 降序排序文件，所选排序方式 MUST 被保存，下次浏览时沿用。

#### Scenario: 按修改时间降序
- **WHEN** 用户选择按修改时间降序
- **THEN** 当前目录的文件按修改时间从新到旧排列

#### Scenario: 排序方式被保存
- **WHEN** 用户选择了某种排序方式后再次进入存储空间
- **THEN** 文件仍按该排序方式排列

### Requirement: 删除空间
系统 SHALL 提供删除模式；在删除模式下选中某个空间时 MUST 将其从空间列表移除。

#### Scenario: 移除空间
- **WHEN** 用户进入删除模式并选中一个空间
- **THEN** 该空间从空间列表中移除

### Requirement: 本地存储权限
使用本地存储需要存储权限，第一次使用时系统 MUST 申请存储权限。

#### Scenario: 首次使用本地存储
- **WHEN** 用户第一次使用本地存储且尚未授予存储权限
- **THEN** 系统申请存储权限

### Requirement: 播放存储中的视频
用户选中视频文件后，系统 SHALL 以 `tvbox-drive://` 地址交给播放器播放；播放 WebDAV 文件时 MUST 自动带上 `authorization: Basic ...` 认证请求头；不支持的文件类型 MUST 提示「Media Unsupported」。

#### Scenario: 播放视频文件
- **WHEN** 用户在存储空间中选中一个视频文件
- **THEN** 系统以 `tvbox-drive://` 地址打开播放器播放该文件

#### Scenario: WebDAV 认证
- **WHEN** 用户播放 WebDAV 空间中的视频
- **THEN** 播放请求自动带上 `authorization: Basic ...` 请求头

#### Scenario: 不支持的文件
- **WHEN** 用户选中一个不支持的文件类型
- **THEN** 系统提示「Media Unsupported」

### Requirement: 播放进度与播放器功能
系统 SHALL 以文件地址为单位单独记录播放进度，再次播放同一文件时 MUST 可以续播；播放器的缩放、倍速、片头片尾等功能在存储空间播放时都可以使用。

#### Scenario: 续播
- **WHEN** 用户播放某文件到一半退出，之后再次播放同一文件
- **THEN** 从上次记录的进度继续播放

#### Scenario: 使用播放器功能
- **WHEN** 用户播放存储空间中的视频
- **THEN** 可以使用缩放、倍速、片头片尾等播放器功能

### Requirement: 与远程控制配合管理文件
在手机浏览器打开远程控制页面的「本地文件」模块时，用户 SHALL 能向盒子存储卡上传文件、新建或删除文件夹、删除文件；上传的 zip 包 MUST 自动解压。详见远程控制与推送功能。

#### Scenario: 上传 zip 包
- **WHEN** 用户在远程控制页面的「本地文件」模块上传一个 zip 包
- **THEN** 该 zip 包在盒子存储卡上自动解压

#### Scenario: 管理文件夹与文件
- **WHEN** 用户在「本地文件」模块新建文件夹、删除文件夹或删除文件
- **THEN** 盒子存储卡上对应执行该操作
