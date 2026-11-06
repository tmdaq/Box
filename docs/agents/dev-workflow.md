# dev-workflow 项目配置

## 命令

- test: `scripts/gradle-test.sh <测试类完整类名...>`（脚本自带全局锁和 JDK 17 设置）
- test-all: `scripts/gradle-test.sh`
- build: `GRADLE_TASK=:app:assembleArm64GenericNormalDebug scripts/gradle-test.sh`
- extra-verify: 改动涉及 `pyramid/` 或 python flavor 时，执行 `VARIANT=Arm64GenericPythonDebug scripts/gradle-test.sh` 和 `GRADLE_TASK=:app:assembleArm64GenericPythonDebug scripts/gradle-test.sh`；其他情况写「无」
- quality-tools: 无（crapper、dryer、mutator 是否支持 Java 还没确认）

## Worktree

- worktree-root: `../Box-wt`
- worktree-setup: 无（`scripts/gradle-test.sh` 会自动从主 worktree 拷贝 `local.properties`）

## 文件规则

- union-merge: `app/src/main/res/values*/strings.xml`
- owner-only-files: `build.gradle`、`app/build.gradle`、`quickjs/build.gradle`、`pyramid/build.gradle`、`settings.gradle`、`gradle.properties`、`app/src/main/AndroidManifest.xml`

## 调度

- max-agents: 6

## 归档

- archive-extra: 无（同步更新 `docs/功能模块/` 的要求写在 `openspec/config.yaml` 的 archive guidance 里）

## 领域检查项

- 网络请求和磁盘 IO 不在主线程执行
- Activity 或 Context 没有被静态字段、单例或异步回调长期持有
- 遥控器 D-pad 能到达新增的每个可交互控件，弹窗关闭后焦点能回到原来的位置
- 新增的 Hawk 配置键有默认值，老用户升级后数据能兼容
- API 调用兼容 minSdk 21；Java 代码只用 Java 8 语法
