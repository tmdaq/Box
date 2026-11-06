---
name: dev-test
description: dev-workflow 的测试阶段：把所有 change 合并到集成分支统一测试，并行审查，返工，然后发 PR、归档、清理。Use when 所有 change 都开发完成，需要做集成测试、验收和交付。
---

# dev-test

先读 [../dev-workflow/references/conventions.md](../dev-workflow/references/conventions.md)。

## 1. 集成

从主干拉出集成分支 `<n>-integration`，依次合并各 change 分支。合并冲突由主代理解决，并把冲突记为「拆分边界没划好」，写进状态报告。

## 2. 验证

1. 依次跑 `<test-all>`、`<build>` 和 `<extra-verify>`。
2. 已经配置了 `<quality-tools>` 的，也在这一步只对改动过的文件运行。

## 3. 并行审查

并行派审查 subagent，全部只读、不跑构建，prompt 见 [references/reviewer-briefs.md](references/reviewer-briefs.md)：

- 每个 change 一个 spec reviewer
- 一个 cross-change reviewer
- 一个 code-quality reviewer

## 4. 合并判断

按 `writes` 把第 2 步的构建失败和第 3 步的审查发现归到各自的 change，并标出哪些是共识、哪些互相冲突、哪些证据不足。证据不足的，由主代理亲自读代码复核。

## 5. 返工

每一轮按 change 并行派 `dev-agent`，从集成分支开 worktree 修复，各自的 `writes` 互不相交，prompt 用 [../dev-implement/references/dev-agent-brief.md](../dev-implement/references/dev-agent-brief.md) 的返工写法。修复全部合并后重跑第 2 步，reviewer 只复查问题清单里的项。最多两轮，之后交给用户决定。

## 6. 手动验证

把所有 change 的 `manual-verify` 合并成一张清单，交给用户验证。

完成条件：构建和测试通过，审查没有未决的阻塞项，用户手动验证通过。

## 7. 交付

1. 用 `pr` skill 写 PR（`<n>-integration` → 主干），并关联父 issue 和所有 sub-issue，合并后自动关闭它们。
2. PR 合并后，按依赖顺序逐个执行 `/opsx:archive <id>`，然后执行 `<archive-extra>`。
3. 清理：删除这个 issue 的所有 worktree，删除已经合并的分支。

## 出口

PR 已合并，所有 issue 已关闭，所有 change 已归档，worktree 已清空。输出最终状态报告。
