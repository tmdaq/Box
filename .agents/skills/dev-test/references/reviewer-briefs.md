# 审查 subagent 模板

所有 reviewer 的共同规则：

- 用全新上下文派发，只给它下面模板里列出的材料，不要转述父对话里的结论。
- 只读：不修改任何文件，不跑构建或测试，不做任何 git 写操作。
- 每条发现都要附上证据：文件路径和行号、引用的 spec 原文。
- 回报格式统一为：一个发现列表，每条写明严重程度（阻塞 / 应修 / 建议）、位置、问题、证据。

## spec reviewer（每个 change 派一个）

```markdown
只对照 OpenSpec 规格检查实现是否相符，不评价代码风格。

Spec: openspec/changes/<id>/ （proposal.md、design.md、tasks.md、specs/）
Diff: 在 <集成分支 worktree 路径> 里执行 `git diff <主干>...<n>-<id>`

逐项检查：
1. delta spec 里的每个 Scenario，是否都有实现和测试覆盖？
2. tasks.md 里每个已勾选的任务，是否真的完成了？
3. 每个新增的测试，在去掉对应实现后是否必然失败？如果每个函数都返回空值，测试仍然能通过，就算一条发现。
4. 是否存在 spec 没有要求的行为改动？
```

## cross-change reviewer（全局派一个）

```markdown
只检查各个 change 之间的交互，不审单个 change 内部的实现。

Changes: <所有 change id 及其 openspec/changes/<id>/ 路径>
Diff: 在 <集成分支 worktree 路径> 里执行 `git diff <主干>...<n>-integration`
Hot files: <项目配置里的 union-merge 和 owner-only-files>

重点检查：
1. 跨 change 接口的签名和行为，是否和 design.md 里约定的一致？
2. 是否有共享状态被多个 change 同时修改：全局配置键、单例、静态或模块级变量？
3. 热点文件合并后，是否出现重复或互相矛盾的内容？
4. 一个 change 是否破坏了另一个 change 的 spec？
```

## code-quality reviewer（全局派一个）

```markdown
检查代码质量和测试，不修改 spec，也不判断实现是否符合需求。

Standards: 项目的 AGENTS.md 或 CLAUDE.md，以及 ub-mode skill
Diff: 在 <集成分支 worktree 路径> 里执行 `git diff <主干>...<n>-integration`
Domain checks: <项目配置里的领域检查项>

重点检查：
1. 边界条件：空值、空集合、外部调用失败、并发访问。
2. 测试是在验证行为，还是在验证实现细节（ub-mode 的 Test Behavior）。
3. 重复代码、超长函数、复杂度过高的分支。
4. 逐条检查 Domain checks 里列出的项。
```
