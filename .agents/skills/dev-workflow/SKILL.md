---
name: dev-workflow
description: 从 issue 到合并交付的通用开发流程入口：判断当前处于哪个阶段，并转交给对应的阶段 skill。Use when 要处理一个 issue 但不确定该从哪个阶段开始，或者中途中断后要继续。
---

# dev-workflow

整套流程分成四个阶段，每个阶段一个 skill，按顺序推进：

| 阶段 | skill | 出口 |
|---|---|---|
| Issue | `dev-issue` | 用户确认这个 issue 要做，事实清单已经评论到 issue |
| 需求 | `dev-requirements` | 每个 change 都通过 `openspec validate --strict`，用户审完全部 proposal |
| 开发 | `dev-implement` | 每个 change 分支的测试都通过 |
| 测试 | `dev-test` | PR 已合并，所有 change 已归档，worktree 已清空 |

共享约定见 [references/conventions.md](references/conventions.md)。

## 安装

`docs/agents/dev-workflow.md` 不存在时，先运行 `dev-install`。

## 判断当前阶段

按下面的顺序检查，第一个成立的条件决定从哪个 skill 开始：

1. 父 issue 上没有 dev-workflow 的状态报告 → `dev-issue`
2. 还没有对应的 `openspec/changes/<id>/`，或者有 change 的 proposal 还没经用户确认 → `dev-requirements`
3. 有 change 的 `tasks.md` 里还存在未勾选的任务（最后的验证项除外） → `dev-implement`
4. 其他情况 → `dev-test`

找到之后，用父 issue 上最近一条状态报告里的「下一步最小行动」，作为那个 skill 的起点。
