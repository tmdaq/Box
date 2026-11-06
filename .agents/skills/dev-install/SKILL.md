---
name: dev-install
description: 把 dev-workflow 套件安装到当前项目：补齐套件和依赖的 skill，初始化 OpenSpec，注册 dev-agent，生成项目配置。Use when 第一次在项目里使用 dev-workflow，或者要升级、修复它的安装。
---

# dev-install

所有操作都是项目级的，在仓库根目录执行。每一步都先检查，已经满足的就跳过，所以重复执行也是安全的。

## 1. 环境

逐项检查，缺什么就把安装命令告诉用户，等用户装好再继续：

- 当前目录是 git 仓库
- `node` 和 `npx` 可用
- `openspec` CLI 可用；没有的话，安装命令是 `npm install -g @fission-ai/openspec`
- issue 放在 GitHub 或 GitLab 的，`gh` 或 `glab` 已经登录

## 2. 套件 skill

`dev-workflow`、`dev-issue`、`dev-requirements`、`dev-implement`、`dev-test` 必须装在同一个 skills 目录下，因为阶段 skill 要通过 `../dev-workflow/` 读取共享约定。缺少的用下面的命令补上：

```bash
npx skills add vttmlin/skills -s dev-workflow dev-issue dev-requirements dev-implement dev-test -y
```

已经装过的 skill 是装在哪些 agent 下的，补装时就用 `-a` 指定同样的 agent。通过 Claude Code 插件安装的，跳过这一步。

## 3. 依赖的 skill

| skill | 来源 | 被谁使用 |
|---|---|---|
| `grilling`、`domain-modeling` | mattpocock/skills | `dev-requirements` 的拷问 |
| `pr` | mattpocock/skills | `dev-test` 的交付 |
| `setup-matt-pocock-skills` | mattpocock/skills | 生成 `docs/agents/issue-tracker.md` |
| `test-driven-development` | obra/superpowers | `dev-agent` |
| `ub-mode` | unclebob/ub-mode | `dev-agent`、code-quality reviewer |

缺少的用下面的命令补上：

```bash
npx skills add mattpocock/skills -s grilling domain-modeling pr setup-matt-pocock-skills -y
npx skills add obra/superpowers -s test-driven-development -y
npx skills add unclebob/ub-mode -y
```

## 4. OpenSpec

没有 `openspec/` 目录时，执行 `openspec init --tools <用户正在使用的工具，比如 claude,cursor>`。

## 5. dev-agent

执行本 skill 目录下的 `scripts/link-agent.sh`。它会找到 `dev-workflow` 实际安装的位置，在 `.claude/agents/dev-agent.md` 建一个相对路径的软链接。通过 Claude Code 插件安装的，dev-agent 已经自动注册，跳过这一步。

完成后提醒用户：要重启会话，`dev-agent` 才会出现在可用的 agent 列表里。

## 6. Issue tracker

没有 `docs/agents/issue-tracker.md` 时，请用户自己运行 `/setup-matt-pocock-skills`。这个 skill 只能由用户触发，模型无法调用。等用户运行完再继续。

## 7. 项目配置

没有 `docs/agents/dev-workflow.md` 时，按 [references/setup.md](references/setup.md) 生成。

## 8. 验证

逐项确认：

- 第 2、3 步列出的 skill 都在 skills 目录里
- `.claude/agents/dev-agent.md` 能正确解析到 `dev-workflow/agents/dev-agent.md`
- `docs/agents/issue-tracker.md` 和 `docs/agents/dev-workflow.md` 都存在，并且 `docs/agents/dev-workflow.md` 里每一项都填了确定的值
- 项目配置里的 `test` 命令，对一个已有的测试能跑通；项目里还没有任何测试的，跑一次 `test-all` 确认命令本身可用

出口：上面每一项都通过。告诉用户安装已经完成，处理 issue 时从 `dev-issue` 开始，中途断了就用 `dev-workflow` 判断当前阶段。
