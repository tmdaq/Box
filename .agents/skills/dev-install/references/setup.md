# Setup

生成 `docs/agents/dev-workflow.md`，由 `dev-install` 的第 7 步调用。做法是：先探查，再写出草稿给用户看，用户确认后才写入。

## 1. 探查项目

依次查清这几件事：

- **构建工具和测试框架**：如何只运行指定的测试，如何运行全部测试，如何构建，有哪些变体或平台。
- **新 worktree 能否直接构建**：被 `.gitignore` 忽略、但构建必需的文件（本地配置、密钥、SDK 路径等）。
- **热点文件**：多个功能都会改到的文件，可以从 git 历史里高频修改的文件里找线索。
- **已有的质量工具**：lint、覆盖率、变异测试等。

## 2. 写 `docs/agents/dev-workflow.md`

先给用户看草稿，确认后再写入。模板如下，每一项都必须填上确定的值：

```markdown
# dev-workflow 项目配置

## 命令

- test: <只运行指定测试的命令，末尾跟测试标识。必须在全局锁下执行>
- test-all: <运行全部测试的命令>
- build: <构建命令>
- extra-verify: <额外的变体或平台验证命令；没有就写「无」>
- quality-tools: <质量工具的命令；没有就写「无」>

## Worktree

- worktree-root: <放 worktree 的目录，建议放在仓库外面>
- worktree-setup: <新 worktree 构建前要执行的命令，比如拷贝被 gitignore 的本地配置；没有就写「无」>

## 文件规则

- union-merge: <可以按行合并的追加型文件，在 .gitattributes 里设置 merge=union>
- owner-only-files: <只能由主代理在开发阶段开始前修改的文件，比如依赖清单、全局配置>

## 调度

- max-agents: <同时运行的 dev-agent 数上限，建议 4–6>

## 归档

- archive-extra: </opsx:archive 之后还要做的事；没有就写「无」>

## 领域检查项

- <code-quality reviewer 额外要检查的、这个项目特有的问题>
```

## 3. 构建锁

所有构建和测试调用必须全局串行，否则多个 worktree 同时构建会互相抢资源。

- 项目里已经有带锁的封装脚本，就直接把它填进 `test` 和 `build`。
- 没有的话，用 `dev-workflow` 自带的 `scripts/run-locked.sh` 包一层：`<dev-workflow 目录>/scripts/run-locked.sh build <原命令>`。这个脚本在 Linux 上用 flock，在 macOS 上用 lockf，都没有时退回到 mkdir 锁。

## 4. 收尾

- 把 `union-merge` 里的文件写进 `.gitattributes`。
- 在 `openspec/config.yaml` 的 `rules.tasks` 里加一条：任务格式遵循 `dev-requirements` 的 `references/tasks-format.md`。
- 在 `AGENTS.md`（或 `CLAUDE.md`）里加一行：处理 issue 时使用 `dev-workflow` skill，项目配置见 `docs/agents/dev-workflow.md`。
