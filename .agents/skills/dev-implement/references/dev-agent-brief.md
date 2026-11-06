# dev-agent 派发模板

`dev-agent` 的工作方式、约束和回报格式已经写在它自己的定义里（`dev-workflow` 的 `agents/dev-agent.md`）。派发时只需要按下面的模板填这一次的具体信息，每一项都必须给出确定的值：

```markdown
Worktree: <绝对路径>
Branch: <组分支名>
Change: openspec/changes/<id>/
Test command: <项目配置里的 test 命令，测试标识放在最后>

Tasks:
<从 tasks.md 原样复制本组所有任务，包括 writes / reads / depends-on / verify / manual-verify 子项>

Writes（本组全部任务 writes 的并集）:
- <路径>

Union-merge files: <项目配置里的 union-merge>
Owner-only files: <项目配置里的 owner-only-files>

Done when: 每个任务都有红、绿、重构三次测试命令的输出，并且已经在本分支提交；`无单测` 的任务写好了手动验证步骤。
```

`dev-test` 返工时用同一个模板，Tasks 一栏换成问题清单（每条注明来源 reviewer、所在文件和行号、证据），Change 一栏仍然填问题所属的 change。
