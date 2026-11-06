# dev-workflow 共享约定

四个阶段 skill（`dev-issue`、`dev-requirements`、`dev-implement`、`dev-test`）开始前都先读本文件。

## 原则

**并行写，排队建，合并验**：调研、写文档、写代码、审查都尽量并行；构建和测试全局只有一个槽位，所有调用排队执行；测试和审查统一在集成分支上做。

## 项目配置

本流程不绑定任何语言或项目，所有项目相关的事实都从下面两个文件读取：

- `docs/agents/dev-workflow.md`：测试命令、构建命令、热点文件、worktree 位置、并发上限、归档附加步骤、领域检查项。
- `docs/agents/issue-tracker.md`：如何读取、创建、评论 issue，以及如何建立父子关系和依赖关系。

任何一个文件不存在，就先运行 `dev-install` 完成安装。各 skill 里的 `<test>`、`<build>`、`<owner-only-files>` 等占位符，指的都是 `docs/agents/dev-workflow.md` 里对应的条目。

## 构建规则

所有构建和测试都只通过项目配置里的 `test`、`test-all`、`build`、`extra-verify` 命令执行。这些命令带有全局锁，会自动排队。

## 命名

| 对象 | 名称 |
|---|---|
| change id | 动词开头，比如 `add-keyword-filter` |
| change 分支 | `<n>-<id>`，`<n>` 是父 issue 编号 |
| 任务组分支和 worktree | `<n>-<id>-g<k>` |
| 集成分支 | `<n>-integration` |

## 状态与恢复

进度只记在父 issue 的评论和各 change 的 `tasks.md` 勾选情况里。每个阶段结束时，在对话里输出状态报告，并把同样的内容评论到父 issue：

- **当前阶段**：skill 名称和阶段内的步骤
- **已完成**
- **阻塞项**（没有就写「无」）
- **下一步最小行动**
