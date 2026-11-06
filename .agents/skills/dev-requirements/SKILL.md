---
name: dev-requirements
description: dev-workflow 的需求阶段：用 grilling + domain-modeling 拷问到没有遗漏，拆分 sub-issue，为每个 sub-issue 生成 OpenSpec change。Use when issue 已经确认要做，需要把需求定下来并产出 change。
---

# dev-requirements

先读 [../dev-workflow/references/conventions.md](../dev-workflow/references/conventions.md)。输入是 issue 上 `dev-issue` 留下的事实清单。

## 1. 拷问

1. 调用 `grilling` 和 `domain-modeling` 这两个 skill，以事实清单为起点拷问用户：术语写进 `GLOSSARY.md`，决定写进 `docs/adr/`。能从代码里查到答案的问题，自己查，不问用户。
2. 逐项填写 [references/grill-checklist.md](references/grill-checklist.md)。

完成条件：清单每一项都已填满，并且用户明确说了「没有遗漏」。把拷问纪要评论到 issue 上。

## 2. 拆分

1. 根据拷问纪要判断是否需要拆分。满足以下任一条件就拆：涉及多个独立能力、涉及多个模块、一句话说不清验收标准。都不满足的，走**快速通道**：只建一个 change。
2. 输出拆分方案，每个 sub-issue 写清：
   - 标题
   - 对应的 change id：一个 sub-issue 只对应一个 change
   - 依赖关系：改同一条 requirement 也算依赖
   - 模块所有权

完成条件：用户确认了拆分方案。之后按 `issue-tracker.md` 创建 sub-issue，并记录依赖关系。

## 3. 生成 change

1. 没有依赖关系的 change，并行派 subagent 执行 `/opsx:propose`，每个 subagent 只写自己的 `openspec/changes/<id>/`；有依赖的按依赖顺序串行生成。
2. `tasks.md` 按 [references/tasks-format.md](references/tasks-format.md) 的格式写。
3. `design.md` 里要把以下几件事定死：跨 change 的接口签名，以及 `<owner-only-files>` 需要做的所有改动。
4. 对每个 change 运行 `openspec validate <id> --strict`，有错误就修到通过为止。

完成条件：所有 change 都通过校验，用户一次性审完全部 proposal。

## 出口

输出状态报告后，转交 `dev-implement`。
