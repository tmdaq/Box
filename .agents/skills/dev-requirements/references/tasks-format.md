# tasks.md 格式

任务行保持 OpenSpec 的复选框格式，`/opsx:apply` 才能识别。每个任务的字段写成任务行下面缩进的子项：

```markdown
## 1. 关键字过滤

- [ ] 1.1 新增关键字匹配逻辑
  - writes: src/filter/keyword_filter.<ext>, test/filter/keyword_filter_test.<ext>
  - reads: openspec/changes/add-keyword-filter/specs/<capability>/spec.md
  - depends-on: 无
  - verify: <测试标识，比如测试类名、测试文件或测试名>
- [ ] 1.2 设置页增加过滤开关
  - writes: src/settings/settings_page.<ext>, <union-merge 里的资源文件>
  - reads: src/settings/config_keys.<ext>
  - depends-on: 1.1
  - verify: 无单测
  - manual-verify: 打开设置 → 过滤，切换开关后退出并重新进入，开关状态保持不变

## 2. 验证

- [ ] 2.1 验证：构建 + 测试通过，并附手动验证结果
```

## 字段说明

| 字段 | 必填 | 含义 |
|---|---|---|
| `writes` | 是 | 会新建或修改的文件，必须写完整路径。dev-agent 只能写这些文件 |
| `reads` | 是 | 实现前需要读的 spec、设计文档和代码 |
| `depends-on` | 是 | 必须先完成的任务编号；没有依赖就写 `无` |
| `verify` | 是 | 传给项目配置里 `test` 命令的测试标识；确实写不出可靠单测的，写 `无单测`，并补上 `manual-verify` |
| `manual-verify` | 按需 | 可以照着一步步操作的手动验证步骤 |

## 分组规则

`dev-implement` 分组时，`writes` 有交集的任务、或者存在 `depends-on` 关系的任务，归为同一组，交给同一个 dev-agent。所以 `writes` 写得越精确，能并行的任务就越多。

## 热点文件

- 项目配置里 `union-merge` 列出的文件：可以直接写进 `writes`，合并时会自动保留各分支的追加。
- 项目配置里 `owner-only-files` 列出的文件：不要写进任何任务的 `writes`，这些改动统一在 `design.md` 里定下，由主代理在开发阶段开始前提交。
