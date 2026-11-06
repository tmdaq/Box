---
name: dev-implement
description: dev-workflow 的开发阶段：按 tasks.md 给任务分组，每组派一个 dev-agent 在独立 worktree 里按 TDD + ub-mode 实现，再合并回 change 分支。Use when change 已经审批通过，需要开始或继续实现。
---

# dev-implement

先读 [../dev-workflow/references/conventions.md](../dev-workflow/references/conventions.md)。

对每个 change 执行下面的步骤。没有依赖的 change 同时进行，有依赖的按依赖顺序进行。

## 1. 准备 change 分支

从主干拉出 change 分支 `<n>-<id>`，先提交 `design.md` 里定下的 `<owner-only-files>` 改动和跨 change 接口骨架。依赖其他 change 的，从被依赖 change 的骨架提交拉分支。

## 2. 分组并派发

1. 按 [../dev-requirements/references/tasks-format.md](../dev-requirements/references/tasks-format.md) 里的分组规则给任务分组：`writes` 有交集的、或者存在 `depends-on` 关系的任务，归为同一组。
2. 每组派一个 `dev-agent`：
   - 先在 `<worktree-root>` 下用 `git worktree add` 开好 worktree，分支名为 `<n>-<id>-g<k>`，基于 change 分支，然后执行 `<worktree-setup>`
   - 按 [references/dev-agent-brief.md](references/dev-agent-brief.md) 填写 prompt
   - 同时运行的 dev-agent 不超过 `<max-agents>` 个
   - 不要轮询后台运行的 dev-agent，等它们完成后的通知

## 3. 合并

每个 dev-agent 回报后：

1. 核对它的变更文件清单是 `writes` 的子集；超出范围的改动，在合并前先处理掉。
2. 处理它在回报里提出的范围外改动请求和需求疑问：能当场决定的当场决定，不能决定的问用户。
3. 把组分支合并进 change 分支，勾选 `tasks.md` 里对应的任务。

change 内所有组都合并后，在 change 分支上跑一次 `<test-all>`。失败的话，按 `writes` 找到对应的组，再派一个 dev-agent 修复。

## 出口

每个 change 分支的测试都通过；`tasks.md` 除最后的验证项外全部勾选。输出状态报告后，转交 `dev-test`。
