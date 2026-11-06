#!/bin/bash
# 在全局锁下执行命令：并行开发时，同一时间只有一个构建或测试在跑，其余排队。
#
# 用法：run-locked.sh <锁名> <命令...>
#   run-locked.sh build npm test -- foo.test.ts
#   run-locked.sh build ./gradlew test --tests FooTest
set -euo pipefail

if [ $# -lt 2 ]; then
    echo "usage: $0 <lock-name> <command...>" >&2
    exit 2
fi
name="$1"; shift
dir="$HOME/.cache/dev-workflow"
mkdir -p "$dir"
lock="$dir/$name.lock"

echo "[run-locked] waiting for lock '$name': $*" >&2
if command -v flock > /dev/null; then
    exec flock "$lock" "$@"
elif command -v lockf > /dev/null; then
    exec lockf -k "$lock" "$@"
else
    until mkdir "$lock.d" 2> /dev/null; do sleep 1; done
    trap 'rmdir "$lock.d"' EXIT
    "$@"
fi
