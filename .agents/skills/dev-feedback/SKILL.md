---
name: dev-feedback
description: 把 dev-workflow 套件（各个 dev-* skill 和 dev-agent）的问题或功能建议整理成 issue，提交到 GitHub 的 vttmlin/skills。Use when 用户想反馈这些 skill 或 dev-agent 的 bug、使用中遇到的问题，或者提新功能需求。
---

# dev-feedback

反馈统一提交到 GitHub 仓库 `vttmlin/skills`。

## 1. 收集信息

- **类型**：bug 或功能建议
- **涉及的对象**：哪个 skill，或者 `dev-agent`。可以先从对话里推断，再让用户确认
- **版本**：项目 `skills-lock.json` 里该 skill 的 `computedHash`；通过插件安装的，写插件版本；都没有就写「未知」
- **bug**：期望的行为、实际的行为、最小化的复现步骤、相关的状态报告或错误输出
- **功能建议**：要解决什么问题、希望怎么用
- **环境**：操作系统、使用的 AI 工具（Claude Code、Cursor 等）、项目的语言和构建工具

## 2. 脱敏

只保留复现必需的最少内容。去掉密钥、token、内网地址、私有代码、人名和邮箱。需要贴项目代码的，先征得用户同意。

## 3. 查重

用 `gh issue list -R vttmlin/skills --state all --search "<关键词>"` 查找已有的 issue。找到相同的问题时，把它给用户看，建议在原 issue 下补充评论，而不是新建一个。

## 4. 起草并确认

- **标题**：`[<skill 名>] <一句话描述>`
- **label**：bug 用 `bug`，功能建议用 `enhancement`
- **正文**：

```markdown
## 类型
bug / 功能建议

## 涉及
<skill 名或 dev-agent>，版本 <hash 或「未知」>

## 描述
<bug：期望的行为 vs 实际的行为；功能建议：要解决的问题>

## 复现步骤 / 期望的用法
1. ...

## 相关输出
<脱敏后的状态报告或错误输出>

## 环境
<操作系统、AI 工具、项目的语言和构建工具>
```

把完整草稿展示给用户，用户确认后才提交。

## 5. 提交

- **新建 issue**：把正文写进临时文件，然后执行 `gh issue create -R vttmlin/skills --title "<标题>" --label <label> --body-file <文件>`。
- **补充到已有 issue**：执行 `gh issue comment <编号> -R vttmlin/skills --body-file <文件>`。
- **没有 `gh` 或者没有登录**：生成一个预填好的链接 `https://github.com/vttmlin/skills/issues/new?title=<URL 编码>&labels=<label>&body=<URL 编码>`，让用户在浏览器里提交。

出口：把 issue 的链接交给用户。
