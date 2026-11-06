---
name: dev-issue
description: dev-workflow 的 Issue 阶段：接收 issue，查重，并行调研出事实清单，确认是否要做。Use when 拿到一个新的 issue 或需求，准备开始处理。
---

# dev-issue

先读 [../dev-workflow/references/conventions.md](../dev-workflow/references/conventions.md)。

## 步骤

1. 按 `issue-tracker.md` 读取 issue 的全文和全部讨论。
2. 并行派 2–3 个只读调研 subagent，每个只负责下面一项：
   - 受影响的 `openspec/specs` requirement
   - 相关代码的位置
   - 已有的测试
3. 把调研结果汇总成一份事实清单，每条都附上出处（文件路径和行号，或 spec 里 requirement 的名称）。
4. 根据事实清单判断：这个需求是否已经实现？是否和已有的 requirement 冲突？把判断结论和事实清单一起交给用户。
5. 把事实清单评论到 issue 上，作为 `dev-requirements` 的输入。

## 出口

用户确认这个 issue 要做，并且事实清单已经评论到 issue。输出状态报告后，转交 `dev-requirements`。

如果用户决定不做，按 `issue-tracker.md` 写明原因并关闭 issue。
