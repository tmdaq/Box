#!/bin/bash
# 用 JDK 17 + 全局构建锁运行 Gradle：多个 worktree 并行开发时，构建排队执行，同一时间只有一个。
#
# 用法：
#   scripts/gradle-test.sh                          跑全部单测
#   scripts/gradle-test.sh com.x.FooTest com.x.Bar  只跑指定测试类
#   VARIANT=Arm64GenericPythonDebug scripts/gradle-test.sh
#   GRADLE_TASK=:app:assembleArm64GenericNormalDebug scripts/gradle-test.sh
set -euo pipefail

export JAVA_HOME="${JAVA_HOME_17:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

# 新 worktree 没有 local.properties（被 .gitignore 忽略），从主 worktree 拷过来
if [ ! -f local.properties ]; then
    main="$(git worktree list --porcelain | awk 'NR==1{print $2}')"
    cp "$main/local.properties" local.properties
fi

variant="${VARIANT:-Arm64GenericNormalDebug}"
task="${GRADLE_TASK:-:app:test${variant}UnitTest}"
args=()
for t in "$@"; do args+=(--tests "$t"); done

lock="$HOME/.gradle/box-build.lock"
mkdir -p "$(dirname "$lock")"
echo "[gradle-test] waiting for build lock: $task" >&2
exec lockf -k "$lock" bash gradlew "$task" ${args[@]+"${args[@]}"}
