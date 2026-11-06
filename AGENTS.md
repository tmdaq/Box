# Box

Android TV 影视聚合播放器（TVBox fork），Java 为主、少量 Kotlin。系统行为以 `openspec/specs/` 为准；`docs/功能模块/` 是面向人的说明，其中 `11-构建与技术架构.md` 讲模块划分和构建变体。

## 构建与测试

- 构建和测试统一用 `scripts/gradle-test.sh`，用法见脚本开头的注释。它负责设置 JDK 17（系统默认的 JDK 8 跑不了 AGP 7.4）、全局构建锁，以及给新 worktree 补上 `local.properties`。
- 默认变体是 `Arm64GenericNormalDebug`；改动涉及 `pyramid/` 或 python flavor 时换成 `Arm64GenericPythonDebug`。
- 测试放在 `app/src/test/java`，使用 JUnit 4 + Mockito 4 + Robolectric，示例见 `app/src/test/java/com/github/tvbox/osc/util/`：
  - **纯逻辑**：写普通 JUnit 测试。`android.*` 的桩方法会返回默认值（`returnDefaultValues`）。
  - **依赖真实 Android 行为**（`Uri`、`Context` 等）：用 Robolectric，并加 `@Config(application = Application.class)`，这样测试不会启动 `App` 的初始化。
  - **改没有测试的老代码**：先写 characterization test 锁住现有行为，再动代码。
  - **UI、播放器、遥控器焦点**：写清楚手动验证步骤，交付时附上验证结果。

## 开发流程

处理 issue 或需求时使用 `dev-workflow` skill，项目配置见 `docs/agents/dev-workflow.md`。

## Agent skills

### Issue tracker

Issues live in GitHub Issues on `tmdaq/Box` (a fork; never file upstream). See `docs/agents/issue-tracker.md`.

### Triage labels

Default five roles: needs-triage, needs-info, ready-for-agent, ready-for-human, wontfix. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: root `GLOSSARY.md` + `docs/adr/`. See `docs/agents/domain.md`.
